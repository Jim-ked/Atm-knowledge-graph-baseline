package org.atmkg.fixture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.atmkg.core.model.SourceRecord;
import org.atmkg.core.spi.SourceAdapter;

/** Development-only adapter for generated fixture CSV files. */
public final class CsvFixtureSourceAdapter implements SourceAdapter {
    private final String sourceId;
    private final Path directory;
    private final Map<String, String> keyFields;

    public CsvFixtureSourceAdapter(String sourceId, Path directory, Map<String, String> keyFields) {
        this.sourceId = sourceId;
        this.directory = directory;
        this.keyFields = Map.copyOf(keyFields);
    }

    @Override
    public Iterable<SourceRecord> readAll(String objectName) {
        return load(objectName);
    }

    @Override
    public Optional<SourceRecord> readByKey(String objectName, String sourceKey) {
        for (SourceRecord record : load(objectName)) if (record.getSourceKey().equals(sourceKey)) return Optional.of(record);
        return Optional.empty();
    }

    @Override
    public Iterable<SourceRecord> scanChangedSince(String objectName, Instant since) {
        return readAll(objectName); // fixture has no temporal source; production adapters implement real watermarks.
    }

    private List<SourceRecord> load(String objectName) {
        String keyField = keyFields.get(objectName);
        if (keyField == null) throw new IllegalArgumentException("未配置 fixture 主键字段：" + objectName);
        Path file = directory.resolve(objectName + ".csv");
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty()) return List.of();
            List<String> headers = parseLine(lines.get(0));
            int keyIndex = headers.indexOf(keyField);
            if (keyIndex < 0) throw new IllegalStateException(objectName + " 缺少主键列 " + keyField);
            List<SourceRecord> out = new ArrayList<>();
            for (int i = 1; i < lines.size(); i++) {
                if (lines.get(i).isBlank()) continue;
                List<String> values = parseLine(lines.get(i));
                Map<String, Object> fields = new LinkedHashMap<>();
                for (int c = 0; c < headers.size(); c++) fields.put(headers.get(c), c < values.size() ? values.get(c) : "");
                String key = String.valueOf(fields.get(keyField));
                out.add(new SourceRecord(sourceId, objectName, key, fields, null));
            }
            return out;
        } catch (IOException ex) {
            throw new IllegalStateException("fixture 读取失败：" + file, ex);
        }
    }

    private List<String> parseLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') { current.append('"'); i++; }
                else quoted = !quoted;
            } else if (ch == ',' && !quoted) { out.add(current.toString()); current.setLength(0); }
            else current.append(ch);
        }
        out.add(current.toString());
        return out;
    }
}
