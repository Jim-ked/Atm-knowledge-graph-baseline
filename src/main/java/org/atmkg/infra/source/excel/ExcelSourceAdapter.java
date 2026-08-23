package org.atmkg.infra.source.excel;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.atmkg.core.model.SourceRecord;
import org.atmkg.core.spi.SourceAdapter;
import org.atmkg.infra.source.config.ConfiguredSource;

/**
 * XLSX 物理读取边界。
 *
 * <p>它不知道 Airport、Route、Airspace 等航空语义，只按 sources.yaml 指定的文件模式、Sheet、
 * 主键字段和通用行组装规则产生 SourceRecord。业务字段如何映射到 TTL 仍由 mapping/字段映射.xlsx 决定。
 *
 * <p>同一 objectName 可以匹配多个同结构工作簿；文件名不参与 sourceKey。ADJACENT_NEXT 模式仅表示
 * “同组当前行 + 下一行”这种通用二维表结构，末行没有 next 时不产生记录。
 */
public final class ExcelSourceAdapter implements SourceAdapter {
    private static final String ADAPTER = "excel";
    private static final String SYNTHETIC_SOURCE_KEY = "__sourceKey";

    private final String sourceId;
    private final Path rootDirectory;
    private final Map<String, ObjectSpec> objects;
    private final DataFormatter formatter = new DataFormatter();

    public ExcelSourceAdapter(ConfiguredSource source, Path projectRoot) {
        if (source == null) throw new IllegalArgumentException("source 不能为空");
        if (!ADAPTER.equalsIgnoreCase(source.getAdapter())) {
            throw new IllegalArgumentException("ExcelSourceAdapter 不能读取 adapter=" + source.getAdapter());
        }
        this.sourceId = source.getSourceId();
        Path base = projectRoot == null ? Path.of(".") : projectRoot;
        String root = optionalText(source.getConfig(), "root", ".");
        this.rootDirectory = base.resolve(root).normalize();
        this.objects = parseObjects(source.getConfig().get("objects"));
    }

    @Override
    public Iterable<SourceRecord> readAll(String objectName) {
        ObjectSpec spec = requireObject(objectName);
        List<SourceRecord> out = new ArrayList<>();
        Set<String> sourceKeys = new LinkedHashSet<>();
        for (Path file : matchingFiles(spec.filesPattern)) {
            for (SourceRecord record : readFile(file, objectName, spec)) {
                if (!sourceKeys.add(record.getSourceKey())) {
                    throw new IllegalStateException("同一 sourceObject 出现重复 sourceKey："
                            + sourceId + "/" + objectName + "/" + record.getSourceKey());
                }
                out.add(record);
            }
        }
        return out;
    }

    /** Returns the physical XLSX files selected by an object definition for human preview only. */
    public List<Path> matchingFilesForPreview(String objectName) {
        return List.copyOf(matchingFiles(requireObject(objectName).filesPattern));
    }

    @Override
    public Optional<SourceRecord> readByKey(String objectName, String sourceKey) {
        if (sourceKey == null || sourceKey.isBlank()) throw new IllegalArgumentException("sourceKey 不能为空");
        for (SourceRecord record : readAll(objectName)) {
            if (record.getSourceKey().equals(sourceKey)) return Optional.of(record);
        }
        return Optional.empty();
    }

    @Override
    public Iterable<SourceRecord> scanChangedSince(String objectName, Instant since) {
        if (since == null) throw new IllegalArgumentException("since 不能为空");
        List<SourceRecord> out = new ArrayList<>();
        for (SourceRecord record : readAll(objectName)) {
            Instant timestamp = record.getSourceTimestamp();
            if (timestamp == null || timestamp.isAfter(since)) out.add(record);
        }
        return out;
    }

    private List<SourceRecord> readFile(Path file, String objectName, ObjectSpec spec) {
        try (InputStream input = Files.newInputStream(file); Workbook workbook = new XSSFWorkbook(input)) {
            Sheet sheet = selectSheet(workbook, spec, file);
            List<RowData> rows = readRows(file, sheet, spec);
            Instant timestamp = Files.getLastModifiedTime(file).toInstant();
            if (spec.mode == RecordMode.ROW) return ordinaryRecords(objectName, rows, spec, timestamp);
            if (spec.mode == RecordMode.GROUP_FIRST) return groupFirstRecords(objectName, rows, spec, timestamp, file, sheet.getSheetName());
            return adjacentRecords(objectName, rows, spec, timestamp, file, sheet.getSheetName());
        } catch (IOException ex) {
            throw new IllegalStateException("Excel 读取失败：" + file, ex);
        }
    }

    private List<SourceRecord> ordinaryRecords(String objectName, List<RowData> rows, ObjectSpec spec, Instant timestamp) {
        List<SourceRecord> out = new ArrayList<>();
        for (RowData row : rows) {
            String key = sourceKey(row.fields, spec.keyFields, row.location());
            Map<String, Object> fields = new LinkedHashMap<>(row.fields);
            fields.put(SYNTHETIC_SOURCE_KEY, key);
            out.add(new SourceRecord(sourceId, objectName, key, fields, timestamp));
        }
        return out;
    }

    private List<SourceRecord> groupFirstRecords(String objectName, List<RowData> rows, ObjectSpec spec,
                                                Instant timestamp, Path file, String sheetName) {
        Map<String, List<RowData>> groups = new LinkedHashMap<>();
        for (RowData row : rows) {
            String group = sourceKey(row.fields, spec.groupBy, row.location());
            groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(row);
        }
        List<SourceRecord> out = new ArrayList<>();
        for (Map.Entry<String, List<RowData>> entry : groups.entrySet()) {
            List<RowData> groupRows = entry.getValue();
            groupRows.sort((left, right) -> compareOrder(left, right, spec.orderBy));
            if (groupRows.size() > 1 && compareOrder(groupRows.get(0), groupRows.get(1), spec.orderBy) == 0) {
                throw new IllegalStateException("GROUP_FIRST 无法唯一确定组内首行："
                        + file + " / " + sheetName + " / group=" + entry.getKey()
                        + " / " + spec.orderBy + "=" + value(groupRows.get(0).fields, spec.orderBy));
            }
            RowData first = groupRows.get(0);
            String key = sourceKey(first.fields, spec.keyFields, first.location());
            Map<String, Object> fields = new LinkedHashMap<>(first.fields);
            fields.put(SYNTHETIC_SOURCE_KEY, key);
            out.add(new SourceRecord(sourceId, objectName, key, fields, timestamp));
        }
        return out;
    }

    private List<SourceRecord> adjacentRecords(String objectName, List<RowData> rows, ObjectSpec spec,
                                               Instant timestamp, Path file, String sheetName) {
        Map<String, List<RowData>> groups = new LinkedHashMap<>();
        for (RowData row : rows) {
            String group = spec.groupBy.isEmpty() ? "__all__" : sourceKey(row.fields, spec.groupBy, row.location());
            groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(row);
        }
        List<SourceRecord> out = new ArrayList<>();
        for (Map.Entry<String, List<RowData>> entry : groups.entrySet()) {
            List<RowData> groupRows = entry.getValue();
            groupRows.sort((left, right) -> compareOrder(left, right, spec.orderBy));
            for (int i = 1; i < groupRows.size(); i++) {
                if (compareOrder(groupRows.get(i - 1), groupRows.get(i), spec.orderBy) == 0) {
                    throw new IllegalStateException("ADJACENT_NEXT 排序字段重复，无法唯一确定相邻顺序："
                            + file + " / " + sheetName + " / group=" + entry.getKey()
                            + " / " + spec.orderBy + "=" + value(groupRows.get(i).fields, spec.orderBy));
                }
            }
            for (int i = 0; i + 1 < groupRows.size(); i++) {
                RowData current = groupRows.get(i);
                RowData next = groupRows.get(i + 1);
                String key = sourceKey(current.fields, spec.keyFields, current.location());
                Map<String, Object> currentFields = new LinkedHashMap<>(current.fields);
                currentFields.put(SYNTHETIC_SOURCE_KEY, sourceKey(current.fields, spec.keyFields, current.location()));
                Map<String, Object> nextFields = new LinkedHashMap<>(next.fields);
                nextFields.put(SYNTHETIC_SOURCE_KEY, sourceKey(next.fields, spec.keyFields, next.location()));

                Map<String, Object> fields = new LinkedHashMap<>(current.fields);
                fields.put("current", currentFields);
                fields.put("next", nextFields);
                fields.put(SYNTHETIC_SOURCE_KEY, key);
                out.add(new SourceRecord(sourceId, objectName, key, fields, timestamp));
            }
        }
        return out;
    }

    private int compareOrder(RowData left, RowData right, String field) {
        String a = stringValue(value(left.fields, field));
        String b = stringValue(value(right.fields, field));
        try {
            return new BigDecimal(a).compareTo(new BigDecimal(b));
        } catch (NumberFormatException ignored) {
            return a.compareTo(b);
        }
    }

    private List<RowData> readRows(Path file, Sheet sheet, ObjectSpec spec) {
        int headerIndex = spec.headerRow - 1;
        Row headerRow = sheet.getRow(headerIndex);
        if (headerRow == null) throw new IllegalStateException("Excel 缺少表头：" + file + " / " + sheet.getSheetName());
        List<String> headers = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        int lastColumn = headerRow.getLastCellNum();
        if (lastColumn < 1) throw new IllegalStateException("Excel 表头为空：" + file + " / " + sheet.getSheetName());
        for (int column = 0; column < lastColumn; column++) {
            String name = cell(headerRow, column).trim();
            if (name.isBlank()) throw new IllegalStateException("Excel 表头存在空字段：" + file + " / " + sheet.getSheetName()
                    + " / column=" + (column + 1));
            if (!names.add(name)) throw new IllegalStateException("Excel 表头字段重复：" + file + " / " + sheet.getSheetName()
                    + " / " + name);
            headers.add(name);
        }

        List<RowData> out = new ArrayList<>();
        for (int index = headerIndex + 1; index <= sheet.getLastRowNum(); index++) {
            Row row = sheet.getRow(index);
            if (row == null) continue;
            Map<String, Object> fields = new LinkedHashMap<>();
            boolean any = false;
            for (int column = 0; column < headers.size(); column++) {
                String text = cell(row, column);
                if (!text.isBlank()) any = true;
                fields.put(headers.get(column), text);
            }
            if (any) out.add(new RowData(fields, file, sheet.getSheetName(), index + 1));
        }
        return out;
    }

    private Sheet selectSheet(Workbook workbook, ObjectSpec spec, Path file) {
        Sheet sheet;
        if (spec.sheetName != null) {
            sheet = workbook.getSheet(spec.sheetName);
        } else {
            int zeroBased = spec.sheetIndex - 1;
            sheet = zeroBased >= 0 && zeroBased < workbook.getNumberOfSheets() ? workbook.getSheetAt(zeroBased) : null;
        }
        if (sheet == null) {
            String locator = spec.sheetName != null ? spec.sheetName : String.valueOf(spec.sheetIndex);
            throw new IllegalStateException("Excel 找不到 Sheet：" + file + " / " + locator);
        }
        return sheet;
    }

    private List<Path> matchingFiles(String pattern) {
        if (!Files.isDirectory(rootDirectory)) {
            throw new IllegalStateException("Excel 数据根目录不存在：" + rootDirectory);
        }
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        try (var stream = Files.walk(rootDirectory)) {
            List<Path> files = stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        Path relative = rootDirectory.relativize(path);
                        return matcher.matches(relative) || matcher.matches(path.getFileName());
                    })
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
            if (files.isEmpty()) throw new IllegalStateException("Excel 文件匹配为空：" + rootDirectory + " / " + pattern);
            return files;
        } catch (IOException ex) {
            throw new IllegalStateException("Excel 文件扫描失败：" + rootDirectory, ex);
        }
    }

    private ObjectSpec requireObject(String objectName) {
        if (objectName == null || objectName.isBlank()) throw new IllegalArgumentException("objectName 不能为空");
        ObjectSpec spec = objects.get(objectName);
        if (spec == null) throw new IllegalArgumentException("未配置 Excel sourceObject：" + objectName);
        return spec;
    }

    private Map<String, ObjectSpec> parseObjects(JsonNode objectsNode) {
        if (objectsNode == null || !objectsNode.isObject()) {
            throw new IllegalArgumentException("Excel 数据源必须配置 objects 对象");
        }
        Map<String, ObjectSpec> out = new LinkedHashMap<>();
        objectsNode.fields().forEachRemaining(entry -> out.put(entry.getKey(), ObjectSpec.parse(entry.getKey(), entry.getValue())));
        if (out.isEmpty()) throw new IllegalArgumentException("Excel 数据源 objects 不能为空");
        return Map.copyOf(out);
    }

    private String sourceKey(Map<String, Object> fields, List<String> keyFields, String location) {
        List<String> values = new ArrayList<>();
        for (String field : keyFields) {
            String text = stringValue(value(fields, field));
            if (text.isBlank()) throw new IllegalStateException("sourceKey 字段为空：" + field + " @ " + location);
            values.add(escapeKey(text));
        }
        return String.join("|", values);
    }

    @SuppressWarnings("unchecked")
    private Object value(Map<String, Object> fields, String path) {
        Object current = fields;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = ((Map<String, Object>) map).get(part);
            if (current == null) return null;
        }
        return current;
    }

    private String cell(Row row, int column) {
        Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }

    private static String escapeKey(String value) {
        return value.replace("\\", "\\\\").replace("|", "\\|");
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String optionalText(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return fallback;
        if (!value.isTextual() || value.textValue().isBlank()) throw new IllegalArgumentException(field + " 必须是非空字符串");
        return value.textValue().trim();
    }

    private enum RecordMode { ROW, GROUP_FIRST, ADJACENT_NEXT }

    private static final class ObjectSpec {
        private final String filesPattern;
        private final String sheetName;
        private final int sheetIndex;
        private final int headerRow;
        private final RecordMode mode;
        private final List<String> keyFields;
        private final List<String> groupBy;
        private final String orderBy;

        private ObjectSpec(String filesPattern, String sheetName, int sheetIndex, int headerRow, RecordMode mode,
                           List<String> keyFields, List<String> groupBy, String orderBy) {
            this.filesPattern = filesPattern;
            this.sheetName = sheetName;
            this.sheetIndex = sheetIndex;
            this.headerRow = headerRow;
            this.mode = mode;
            this.keyFields = keyFields;
            this.groupBy = groupBy;
            this.orderBy = orderBy;
        }

        private static ObjectSpec parse(String objectName, JsonNode node) {
            if (node == null || !node.isObject()) throw new IllegalArgumentException(objectName + " 必须是对象");
            String files = requiredText(node, "files", objectName);
            JsonNode sheet = node.get("sheet");
            String sheetName = null;
            int sheetIndex = 0;
            if (sheet == null) throw new IllegalArgumentException(objectName + " 缺少 sheet");
            if (sheet.isTextual() && !sheet.textValue().isBlank()) sheetName = sheet.textValue().trim();
            else if (sheet.isIntegralNumber() && sheet.canConvertToInt() && sheet.intValue() >= 1) sheetIndex = sheet.intValue();
            else throw new IllegalArgumentException(objectName + ".sheet 必须是 Sheet 名称或从 1 开始的序号");

            int headerRow = optionalPositiveInt(node, "headerRow", 1, objectName);
            String modeText = optionalText(node, "recordMode", "row").replace('-', '_').toUpperCase();
            RecordMode mode;
            try {
                mode = RecordMode.valueOf(modeText);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(objectName + ".recordMode 仅支持 row / group_first / adjacent_next", ex);
            }
            List<String> keyFields = requiredStringList(node, "keyFields", objectName);
            List<String> groupBy = optionalStringList(node, "groupBy", objectName);
            String orderBy = optionalText(node, "orderBy", "");
            if (mode == RecordMode.GROUP_FIRST && groupBy.isEmpty()) {
                throw new IllegalArgumentException(objectName + " 使用 group_first 时必须配置 groupBy");
            }
            if ((mode == RecordMode.GROUP_FIRST || mode == RecordMode.ADJACENT_NEXT) && orderBy.isBlank()) {
                throw new IllegalArgumentException(objectName + " 使用 " + modeText.toLowerCase() + " 时必须配置 orderBy");
            }
            return new ObjectSpec(files, sheetName, sheetIndex, headerRow, mode, keyFields, groupBy, orderBy);
        }

        private static String requiredText(JsonNode node, String field, String objectName) {
            JsonNode value = node.get(field);
            if (value == null || !value.isTextual() || value.textValue().isBlank()) {
                throw new IllegalArgumentException(objectName + "." + field + " 必须是非空字符串");
            }
            return value.textValue().trim();
        }

        private static int optionalPositiveInt(JsonNode node, String field, int fallback, String objectName) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) return fallback;
            if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1) {
                throw new IllegalArgumentException(objectName + "." + field + " 必须是正整数");
            }
            return value.intValue();
        }

        private static List<String> requiredStringList(JsonNode node, String field, String objectName) {
            List<String> values = optionalStringList(node, field, objectName);
            if (values.isEmpty()) throw new IllegalArgumentException(objectName + "." + field + " 不能为空");
            return values;
        }

        private static List<String> optionalStringList(JsonNode node, String field, String objectName) {
            JsonNode array = node.get(field);
            if (array == null || array.isNull()) return List.of();
            if (!array.isArray()) throw new IllegalArgumentException(objectName + "." + field + " 必须是字符串数组");
            List<String> values = new ArrayList<>();
            for (JsonNode item : array) {
                if (!item.isTextual() || item.textValue().isBlank()) {
                    throw new IllegalArgumentException(objectName + "." + field + " 只能包含非空字符串");
                }
                values.add(item.textValue().trim());
            }
            return List.copyOf(values);
        }
    }

    private record RowData(Map<String, Object> fields, Path file, String sheet, int rowNumber) {
        private String location() { return file + " / " + sheet + " / row=" + rowNumber; }
    }
}
