package org.atmkg.tools;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.atmkg.core.model.SourceScope;
import org.atmkg.core.spi.SyncService;
import org.atmkg.infra.neo4j.Neo4jConnectionSettings;
import org.atmkg.infra.neo4j.Neo4jDriverFactory;
import org.atmkg.infra.ontology.JenaOntologyService;
import org.atmkg.infra.source.config.ConfiguredSource;
import org.atmkg.service.sync.SyncRuntimeConfig;
import org.neo4j.driver.Driver;

/** Small operator-facing entry point for explicit synchronization actions. */
public final class SyncControlMain {
    private SyncControlMain() {}

    public static void main(String[] args) throws IOException {
        Path root = args.length == 0
                ? Path.of(".").toAbsolutePath().normalize()
                : Path.of(args[0]).toAbsolutePath().normalize();
        SyncRuntimeAssembler.AssemblyPlan plan = SyncRuntimeAssembler.plan(root);
        List<SourceEntry> entries = sourceEntries(plan);
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
             PrintWriter output = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true)) {
            if (entries.isEmpty()) {
                run(input, output, null, entries, plan.sync());
                return;
            }

            var schema = new JenaOntologyService().load(root.resolve("ontology/atm_knowledge_graph.ttl"));
            Neo4jConnectionSettings neo4j = Neo4jConnectionSettings.fromEnvironment("atm-knowledge-graph", 500);
            Driver driver = Neo4jDriverFactory.create(neo4j);
            SyncRuntimeAssembler.SyncAssembly assembly = null;
            try {
                assembly = SyncRuntimeAssembler.assembleEnabled(plan, schema, driver, neo4j);
                run(input, output, assembly.syncService(), entries, plan.sync());
            } finally {
                if (assembly != null) assembly.runtime().close();
                driver.close();
            }
        }
    }

    static void run(BufferedReader input, PrintWriter output, SyncService syncService,
                    List<SourceEntry> entries, SyncRuntimeConfig config) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(config, "config");
        output.println("==== 知识图谱同步 ====");
        if (entries.isEmpty()) {
            output.println();
            output.println("当前未配置正式数据源");
            return;
        }
        Objects.requireNonNull(syncService, "syncService");

        while (true) {
            output.println();
            output.println("1. 全量重建当前正式数据源");
            output.println("2. 同步一个数据源入口");
            output.println("3. 重新同步一条记录");
            output.println("4. 补偿扫描");
            output.println("5. 查看当前同步配置");
            output.println("0. 退出");
            String choice = prompt(input, output, "输入编号：");
            if (choice == null || "0".equals(choice)) return;
            try {
                switch (choice) {
                    case "1" -> fullRebuild(input, output, syncService, entries);
                    case "2" -> fullSync(input, output, syncService, entries);
                    case "3" -> resync(input, output, syncService, entries);
                    case "4" -> compensate(input, output, syncService, entries);
                    case "5" -> showConfig(output, entries, config);
                    default -> output.println("编号无效，请重新选择。");
                }
            } catch (RuntimeException ex) {
                String message = ex.getMessage();
                output.println("操作失败：" + (message == null || message.isBlank() ? ex.getClass().getSimpleName() : message));
            }
        }
    }

    private static void fullRebuild(BufferedReader input, PrintWriter output, SyncService syncService,
                                    List<SourceEntry> entries) throws IOException {
        output.println("将参与全量重建的数据源入口：");
        for (SourceEntry entry : entries) output.println("  - " + entry.identity());
        output.println("警告：将清空当前项目图投影后重新构建。");
        String confirmation = prompt(input, output, "请输入“确认重建”继续：");
        if (!"确认重建".equals(confirmation)) {
            output.println("已取消全量重建。");
            return;
        }
        syncService.fullRebuild(entries.stream()
                .map(entry -> new SourceScope(entry.sourceId(), entry.sourceObject())).toList());
        output.println("全量重建完成。");
    }

    private static void fullSync(BufferedReader input, PrintWriter output, SyncService syncService,
                                 List<SourceEntry> entries) throws IOException {
        SourceEntry entry = choose(input, output, entries, "选择要同步的数据源入口：");
        if (entry == null) return;
        syncService.fullSync(entry.sourceId(), entry.sourceObject());
        output.println("数据源入口同步完成：" + entry.identity());
    }

    private static void resync(BufferedReader input, PrintWriter output, SyncService syncService,
                               List<SourceEntry> entries) throws IOException {
        SourceEntry entry = choose(input, output, entries, "选择记录所属的数据源入口：");
        if (entry == null) return;
        String sourceKey = prompt(input, output, "输入 sourceKey：");
        if (sourceKey == null || sourceKey.isBlank()) {
            output.println("sourceKey 不能为空。");
            return;
        }
        syncService.resync(entry.sourceId(), entry.sourceObject(), sourceKey.trim());
        output.println("记录重新同步完成：" + entry.identity() + "/" + sourceKey.trim());
    }

    private static void compensate(BufferedReader input, PrintWriter output, SyncService syncService,
                                   List<SourceEntry> entries) throws IOException {
        List<SourceEntry> eligible = entries.stream().filter(SourceEntry::changedScanSupported).toList();
        if (eligible.isEmpty()) {
            output.println("当前没有支持补偿扫描的数据源入口。");
            return;
        }
        SourceEntry entry = choose(input, output, eligible, "选择要补偿扫描的数据源入口：");
        if (entry == null) return;
        String value = prompt(input, output, "输入起始时间（ISO-8601，例如 2026-08-23T00:00:00Z）：");
        if (value == null || value.isBlank()) {
            output.println("起始时间不能为空。");
            return;
        }
        Instant since;
        try {
            since = Instant.parse(value.trim());
        } catch (DateTimeParseException ex) {
            output.println("起始时间格式无效，未执行补偿扫描。");
            return;
        }
        syncService.compensateSince(entry.sourceId(), entry.sourceObject(), since);
        output.println("补偿扫描完成：" + entry.identity());
    }

    private static void showConfig(PrintWriter output, List<SourceEntry> entries, SyncRuntimeConfig config) {
        output.println("当前正式同步配置：");
        for (SourceEntry entry : entries) {
            output.println("  " + entry.sourceId() + " / " + entry.sourceObject() + " / " + entry.adapter()
                    + " / watermark=" + (entry.watermarkConfigured() ? "是" : "否"));
        }
        output.println("polling 是否启用：" + (config.isPollingEnabled() ? "是" : "否"));
        output.println("polling 间隔秒数：" + config.getPollingInterval().toSeconds());
        output.println("polling scope 数量：" + config.getPollingScopes().size());
    }

    private static SourceEntry choose(BufferedReader input, PrintWriter output, List<SourceEntry> entries,
                                      String title) throws IOException {
        output.println(title);
        for (int i = 0; i < entries.size(); i++) {
            SourceEntry entry = entries.get(i);
            output.println((i + 1) + ". " + entry.sourceObject() + " [" + entry.sourceId()
                    + ", " + entry.adapter() + "]");
        }
        output.println("0. 返回");
        String raw = prompt(input, output, "输入编号：");
        if (raw == null || "0".equals(raw)) return null;
        try {
            int index = Integer.parseInt(raw) - 1;
            if (index >= 0 && index < entries.size()) return entries.get(index);
        } catch (NumberFormatException ignored) {
            // Report the same concise message for all invalid menu input.
        }
        output.println("编号无效，未执行操作。");
        return null;
    }

    private static String prompt(BufferedReader input, PrintWriter output, String text) throws IOException {
        output.print(text);
        output.flush();
        String value = input.readLine();
        return value == null ? null : value.trim();
    }

    static List<SourceEntry> sourceEntries(SyncRuntimeAssembler.AssemblyPlan plan) {
        List<SourceEntry> entries = new ArrayList<>();
        for (ConfiguredSource source : plan.sources().getSources().values()) {
            JsonNode objects = source.getConfig().get("objects");
            objects.fields().forEachRemaining(item -> {
                JsonNode watermark = item.getValue().get("watermarkField");
                boolean watermarkConfigured = watermark != null && watermark.isTextual()
                        && !watermark.textValue().isBlank();
                boolean changedScanSupported = "excel".equals(source.getAdapter().toLowerCase(Locale.ROOT))
                        || ("jdbc".equals(source.getAdapter().toLowerCase(Locale.ROOT)) && watermarkConfigured);
                entries.add(new SourceEntry(source.getSourceId(), item.getKey(), source.getAdapter(),
                        watermarkConfigured, changedScanSupported));
            });
        }
        return List.copyOf(entries);
    }

    record SourceEntry(String sourceId, String sourceObject, String adapter,
                       boolean watermarkConfigured, boolean changedScanSupported) {
        private String identity() { return sourceId + "/" + sourceObject; }
    }
}
