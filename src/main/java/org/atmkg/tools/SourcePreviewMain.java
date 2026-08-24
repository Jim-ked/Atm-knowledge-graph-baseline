package org.atmkg.tools;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.atmkg.core.model.SourceRecord;
import org.atmkg.core.spi.SourceAdapter;
import org.atmkg.infra.source.SourceAdapterFactory;
import org.atmkg.infra.source.config.ConfiguredSource;
import org.atmkg.infra.source.config.SourceConfig;
import org.atmkg.infra.source.excel.ExcelSourceAdapter;

/**
 * Excel/JDBC 读取不符合预期时从本工具进入；不要为新增字段修改本类。无参数会优先
 * {@code config/sources.local.yaml}，检查正式源必须执行
 * {@code tools\source-preview.cmd config\sources.yaml <sourceId> <objectName> 5}。
 *
 * <p>只有人工输出格式或支持哪个既有 Adapter 的工具范围经确认变化时才改 Java。它只看 SourceRecord，
 * 不判断 mapping，也不写 Neo4j；字段已读到但图中没有应继续查字段映射.xlsx。limit 限制工具消费的
 * SourceRecord 数量，提前停止时会关闭可关闭的 iterator，不增加数据库厂商专用 SQL。
 */
public final class SourcePreviewMain {
    private static final SourceAdapterFactory SOURCE_ADAPTERS = new SourceAdapterFactory();

    private SourcePreviewMain() {}

    public static void main(String[] args) {
        try {
            if (args.length == 0) {
                interactive();
                return;
            }
            if (args.length < 3 || args.length > 4) {
                usage();
                return;
            }
            preview(Path.of(args[0]).toAbsolutePath().normalize(), args[1], args[2],
                    args.length == 4 ? positiveInt(args[3]) : 5);
        } catch (RuntimeException ex) {
            System.err.println("SourceRecord 预览失败：" + ex.getMessage());
            System.exit(1);
        }
    }

    private static void interactive() {
        Path configFile = selectConfigFile();
        if (configFile == null) {
            System.out.println("==== 数据源人工核验 ====");
            System.out.println("未找到 config\\sources.local.yaml 或 config\\sources.yaml。");
            return;
        }
        SourceConfig config = SourceConfig.load(configFile);
        List<PreviewEntry> entries = entries(config);
        System.out.println("==== 数据源人工核验 ====");
        if (entries.isEmpty()) {
            System.out.println("当前配置没有可检查的数据源入口。");
            System.out.println("配置文件：" + configFile);
            System.out.println("0. 退出");
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            PreviewEntry entry = entries.get(i);
            System.out.println((i + 1) + ". " + humanLabel(entry.source(), entry.objectConfig()));
            System.out.println("   技术标识（排障用）：" + entry.source().getSourceId() + " / " + entry.objectName());
        }
        System.out.println("0. 退出");
        System.out.print("输入编号：");
        int choice = readChoice(entries.size());
        if (choice == 0) return;
        PreviewEntry entry = entries.get(choice - 1);
        preview(configFile, entry.source().getSourceId(), entry.objectName(), 5);
    }

    private static Path selectConfigFile() {
        Path local = Path.of("config", "sources.local.yaml").toAbsolutePath().normalize();
        if (java.nio.file.Files.isRegularFile(local)) return local;
        Path formal = Path.of("config", "sources.yaml").toAbsolutePath().normalize();
        return java.nio.file.Files.isRegularFile(formal) ? formal : null;
    }

    private static List<PreviewEntry> entries(SourceConfig config) {
        List<PreviewEntry> entries = new ArrayList<>();
        for (ConfiguredSource source : config.getSources().values()) {
            JsonNode objects = source.getConfig().path("objects");
            if (!objects.isObject()) continue;
            objects.fieldNames().forEachRemaining(objectName ->
                    entries.add(new PreviewEntry(source, objectName, objects.path(objectName))));
        }
        return entries;
    }

    private static int readChoice(int size) {
        try {
            String line = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
            int choice = Integer.parseInt(line == null ? "0" : line.trim());
            if (choice < 0 || choice > size) throw new NumberFormatException();
            return choice;
        } catch (IOException | NumberFormatException ex) {
            throw new IllegalArgumentException("请输入菜单中的数字编号");
        }
    }

    private static void preview(Path configFile, String sourceId, String objectName, int limit) {
        Path projectRoot = Path.of(".").toAbsolutePath().normalize();
        SourceConfig config = SourceConfig.load(configFile);
        ConfiguredSource source = config.requireSource(sourceId);
        SourceAdapter adapter = SOURCE_ADAPTERS.create(source, projectRoot);
        preview(source, objectName, adapter, limit);
    }

    static List<SourceRecord> preview(ConfiguredSource source, String objectName,
                                      SourceAdapter adapter, int limit) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(adapter, "adapter");

        System.out.println("==== 1. 从哪里读取 ====");
        printPhysicalLocator(source, objectName, adapter);

        System.out.println();
        System.out.println("==== 2. 读取结果 ====");
        List<SourceRecord> records = readLimited(adapter.readAll(objectName), limit);
        System.out.println("本次读取 SourceRecord：" + records.size() + " 条（limit=" + limit + "）");

        for (int i = 0; i < records.size() && i < limit; i++) {
            SourceRecord record = records.get(i);
            System.out.println();
            System.out.println("【记录 " + (i + 1) + "】");
            System.out.println("记录标识：" + record.getSourceKey());
            JsonNode object = source.getConfig().path("objects").path(objectName);
            String mode = object.path("recordMode").asText("row").replace('-', '_').toUpperCase();
            if ("ADJACENT_NEXT".equals(mode)) printAdjacentRecord(record);
            else printFields(record.getFields(), "  ");
        }

        System.out.println();
        System.out.println("==== 3. 人工只核对这几项 ====");
        if ("jdbc".equalsIgnoreCase(source.getAdapter())) printJdbcTips();
        else printTips(source.getConfig().path("objects").path(objectName).path("recordMode").asText("row"));
        return records;
    }

    static List<SourceRecord> readLimited(Iterable<SourceRecord> records, int limit) {
        Objects.requireNonNull(records, "records");
        if (limit < 1) throw new IllegalArgumentException("limit 必须是正整数：" + limit);
        Iterator<SourceRecord> iterator = records.iterator();
        List<SourceRecord> result = new ArrayList<>(limit);
        Throwable failure = null;
        try {
            while (result.size() < limit && iterator.hasNext()) result.add(iterator.next());
            return List.copyOf(result);
        } catch (RuntimeException | Error ex) {
            failure = ex;
            throw ex;
        } finally {
            closeIterator(iterator, failure);
        }
    }

    private static void closeIterator(Iterator<?> iterator, Throwable failure) {
        if (!(iterator instanceof AutoCloseable closeable)) return;
        try {
            closeable.close();
        } catch (Exception ex) {
            if (failure != null) failure.addSuppressed(ex);
            else throw new IllegalStateException("预览读取资源关闭失败", ex);
        }
    }

    private static void printPhysicalLocator(ConfiguredSource source, String objectName, SourceAdapter adapter) {
        JsonNode config = source.getConfig();
        JsonNode object = config.path("objects").path(objectName);
        System.out.println("sourceId=" + source.getSourceId());
        System.out.println("sourceObject=" + objectName);
        System.out.println("adapter=" + source.getAdapter().toLowerCase(Locale.ROOT));
        if ("jdbc".equalsIgnoreCase(source.getAdapter())) {
            if (object.hasNonNull("table")) System.out.println("表：" + object.path("table").asText());
            if (object.hasNonNull("view")) System.out.println("视图：" + object.path("view").asText());
            System.out.println("keyFields：" + object.path("keyFields"));
            if (object.hasNonNull("watermarkField")) {
                System.out.println("watermarkField：" + object.path("watermarkField").asText());
            }
            return;
        }
        JsonNode root = config.get("root");
        if (root != null && !root.isNull()) System.out.println("根目录：" + root.asText());
        if (object.isMissingNode() || !object.isObject()) return;
        if (adapter instanceof ExcelSourceAdapter excel) {
            System.out.println("实际匹配文件：");
            for (Path file : excel.matchingFilesForPreview(objectName)) System.out.println("  " + file);
        }
        System.out.println("Sheet：" + object.path("sheet").asText());
        System.out.println("读取方式：" + humanMode(object.path("recordMode").asText("row")));
        System.out.println("keyFields：" + object.path("keyFields"));
        if (object.has("groupBy")) System.out.println("groupBy：" + object.path("groupBy"));
        if (object.has("orderBy")) System.out.println("orderBy：" + object.path("orderBy"));
    }

    private static String humanLabel(ConfiguredSource source, JsonNode object) {
        if ("jdbc".equalsIgnoreCase(source.getAdapter())) {
            String relation = object.hasNonNull("table")
                    ? object.path("table").asText() : object.path("view").asText("?");
            return "JDBC / " + relation;
        }
        return object.path("files").asText("(未配置文件模式)") + " / Sheet "
                + object.path("sheet").asText("?") + " / " + humanMode(object.path("recordMode").asText("row"));
    }

    private static String humanMode(String mode) {
        return switch (mode.replace('-', '_').toUpperCase()) {
            case "GROUP_FIRST" -> "每组只取首行";
            case "ADJACENT_NEXT" -> "当前行 + 下一行";
            default -> "逐行读取";
        };
    }

    private static void printAdjacentRecord(SourceRecord record) {
        Map<String, Object> fields = record.getFields();
        printNestedRow("当前行", fields.get("current"));
        printNestedRow("下一行", fields.get("next"));
    }

    @SuppressWarnings("unchecked")
    private static void printNestedRow(String title, Object value) {
        System.out.println();
        System.out.println(title + "：");
        if (value instanceof Map<?, ?> nested) printFields((Map<String, Object>) nested, "  ");
        else System.out.println("  （无）");
    }

    private static void printFields(Map<String, Object> values, String indent) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if ("__sourceKey".equals(entry.getKey())) {
                System.out.println(indent + "行标识 = " + entry.getValue());
            } else if (!(entry.getValue() instanceof Map<?, ?>)) {
                System.out.println(indent + entry.getKey() + " = " + String.valueOf(entry.getValue()));
            }
        }
    }

    private static void printTips(String mode) {
        switch (mode.replace('-', '_').toUpperCase()) {
            case "GROUP_FIRST" -> {
                System.out.println("1. 同组多行是否只生成一条记录。");
                System.out.println("2. 生成的记录是否确实来自排序后的第一行。");
                System.out.println("3. SourceRecord 字段是否符合父级记录预期。");
            }
            case "ADJACENT_NEXT" -> {
                System.out.println("1. 当前行和下一行是否确实相邻。");
                System.out.println("2. 两行的行标识是否正确。");
                System.out.println("3. 末行是否不再产生空记录。");
            }
            default -> {
                System.out.println("1. Excel 一条有效行是否对应一条记录。");
                System.out.println("2. sourceKey 是否稳定且来自配置字段。");
                System.out.println("3. 字段内容是否与原表一致。");
            }
        }
        System.out.println("核对 3～5 条即可，不要求逐行人工检查。");
    }

    private static void printJdbcTips() {
        System.out.println("1. sourceKey 是否稳定且来自 keyFields。");
        System.out.println("2. 字段名和字段值是否与表或视图一致。");
        System.out.println("3. 配置 watermarkField 时，时间值是否正确。");
        System.out.println("核对 3～5 条即可，不要求读取全表。");
    }

    @SuppressWarnings("unchecked")
    private static void printMap(Map<String, Object> values, String indent) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                System.out.println(indent + entry.getKey() + ":");
                printMap((Map<String, Object>) nested, indent + "  ");
            } else {
                System.out.println(indent + entry.getKey() + " = " + String.valueOf(value));
            }
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        return value.trim();
    }

    private static int positiveInt(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("limit 必须是正整数：" + value, ex);
        }
    }

    private static void usage() {
        System.err.println("用法：SourcePreviewMain <sources.yaml> <sourceId> <objectName> [limit]");
        System.err.println("示例：tools\\source-preview.cmd config\\sources.local.yaml excel-main route-segment 5");
        System.err.println("JDBC：tools\\source-preview.cmd config\\sources.yaml jdbc-main example-object 5");
    }

    private record PreviewEntry(ConfiguredSource source, String objectName, JsonNode objectConfig) {}
}
