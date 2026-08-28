package org.atmkg.infra.source.compose;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.atmkg.core.model.SourceRecord;
import org.atmkg.infra.source.compose.RecordCompositionSpec.RecordMode;

/** Composes generic physical rows without source- or ontology-specific knowledge. */
public final class RecordComposer {
    private static final String SYNTHETIC_SOURCE_KEY = "__sourceKey";

    public Iterable<SourceRecord> compose(String sourceId, String objectName, Iterable<RawRecord> rawRecords,
                                          RecordCompositionSpec spec) {
        String source = requiredText(sourceId, "sourceId");
        String object = requiredText(objectName, "objectName");
        Objects.requireNonNull(rawRecords, "rawRecords");
        Objects.requireNonNull(spec, "spec");
        if (spec.recordMode() == RecordMode.ROW) {
            return () -> new RowIterator(rawRecords.iterator(), source, object, spec.keyFields());
        }

        List<RawRecord> rows = materialize(rawRecords);
        if (spec.recordMode() == RecordMode.GROUP_FIRST) {
            return groupFirst(source, object, rows, spec);
        }
        return adjacentNext(source, object, rows, spec);
    }

    private List<SourceRecord> groupFirst(String sourceId, String objectName, List<RawRecord> rows,
                                          RecordCompositionSpec spec) {
        Map<String, List<RawRecord>> groups = groups(rows, spec.groupBy());
        List<SourceRecord> out = new ArrayList<>();
        for (Map.Entry<String, List<RawRecord>> entry : groups.entrySet()) {
            List<RawRecord> groupRows = entry.getValue();
            groupRows.sort((left, right) -> compareOrder(left, right, spec.orderBy()));
            if (groupRows.size() > 1 && compareOrder(groupRows.get(0), groupRows.get(1), spec.orderBy()) == 0) {
                RawRecord first = groupRows.get(0);
                RawRecord second = groupRows.get(1);
                throw new IllegalStateException("GROUP_FIRST 无法唯一确定组内首行：group=" + entry.getKey()
                        + " / " + spec.orderBy() + "=" + stringValue(value(first.fields(), spec.orderBy()))
                        + " @ " + first.location() + " / " + second.location());
            }
            out.add(ordinary(sourceId, objectName, groupRows.get(0), spec.keyFields()));
        }
        return List.copyOf(out);
    }

    private List<SourceRecord> adjacentNext(String sourceId, String objectName, List<RawRecord> rows,
                                            RecordCompositionSpec spec) {
        Map<String, List<RawRecord>> groups = spec.groupBy().isEmpty()
                ? Map.of("__all__", new ArrayList<>(rows)) : groups(rows, spec.groupBy());
        List<SourceRecord> out = new ArrayList<>();
        for (Map.Entry<String, List<RawRecord>> entry : groups.entrySet()) {
            List<RawRecord> groupRows = entry.getValue();
            groupRows.sort((left, right) -> compareOrder(left, right, spec.orderBy()));
            for (int index = 1; index < groupRows.size(); index++) {
                RawRecord previous = groupRows.get(index - 1);
                RawRecord current = groupRows.get(index);
                if (compareOrder(previous, current, spec.orderBy()) == 0) {
                    throw new IllegalStateException("ADJACENT_NEXT 排序字段重复，无法唯一确定相邻顺序：group="
                            + entry.getKey() + " / " + spec.orderBy() + "="
                            + stringValue(value(current.fields(), spec.orderBy()))
                            + " @ " + previous.location() + " / " + current.location());
                }
            }
            for (int index = 0; index + 1 < groupRows.size(); index++) {
                out.add(adjacent(sourceId, objectName, groupRows.get(index), groupRows.get(index + 1),
                        spec.keyFields()));
            }
        }
        return List.copyOf(out);
    }

    private Map<String, List<RawRecord>> groups(List<RawRecord> rows, List<String> groupBy) {
        Map<String, List<RawRecord>> groups = new LinkedHashMap<>();
        for (RawRecord row : rows) {
            String group = sourceKey(row.fields(), groupBy, row.location());
            groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(row);
        }
        return groups;
    }

    private SourceRecord adjacent(String sourceId, String objectName, RawRecord current, RawRecord next,
                                  List<String> keyFields) {
        String currentKey = sourceKey(current.fields(), keyFields, current.location());
        String nextKey = sourceKey(next.fields(), keyFields, next.location());
        Map<String, Object> currentFields = withSourceKey(current.fields(), currentKey);
        Map<String, Object> nextFields = withSourceKey(next.fields(), nextKey);
        Map<String, Object> fields = new LinkedHashMap<>(current.fields());
        fields.put("current", currentFields);
        fields.put("next", nextFields);
        fields.put(SYNTHETIC_SOURCE_KEY, currentKey);
        return new SourceRecord(sourceId, objectName, currentKey, fields, current.sourceTimestamp());
    }

    private SourceRecord ordinary(String sourceId, String objectName, RawRecord row, List<String> keyFields) {
        String key = sourceKey(row.fields(), keyFields, row.location());
        return new SourceRecord(sourceId, objectName, key, withSourceKey(row.fields(), key), row.sourceTimestamp());
    }

    private Map<String, Object> withSourceKey(Map<String, Object> source, String key) {
        Map<String, Object> fields = new LinkedHashMap<>(source);
        fields.put(SYNTHETIC_SOURCE_KEY, key);
        return fields;
    }

    private int compareOrder(RawRecord left, RawRecord right, String field) {
        String leftValue = stringValue(value(left.fields(), field));
        String rightValue = stringValue(value(right.fields(), field));
        try {
            return new BigDecimal(leftValue).compareTo(new BigDecimal(rightValue));
        } catch (NumberFormatException ignored) {
            return leftValue.compareTo(rightValue);
        }
    }

    private String sourceKey(Map<String, Object> fields, List<String> keyFields, String location) {
        List<String> values = new ArrayList<>();
        for (String field : keyFields) {
            String text = stringValue(value(fields, field));
            if (text.isBlank()) throw new IllegalStateException("sourceKey 字段为空：" + field + " @ " + location);
            values.add(text.replace("\\", "\\\\").replace("|", "\\|"));
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

    private List<RawRecord> materialize(Iterable<RawRecord> records) {
        Iterator<RawRecord> iterator = records.iterator();
        List<RawRecord> out = new ArrayList<>();
        try {
            while (iterator.hasNext()) out.add(iterator.next());
            return out;
        } finally {
            close(iterator);
        }
    }

    private static String requiredText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        return value.trim();
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static void close(Object closeable) {
        if (!(closeable instanceof AutoCloseable resource)) return;
        try {
            resource.close();
        } catch (Exception ignored) {
            // Preserve the original composition failure.
        }
    }

    private final class RowIterator implements Iterator<SourceRecord>, AutoCloseable {
        private final Iterator<RawRecord> delegate;
        private final String sourceId;
        private final String objectName;
        private final List<String> keyFields;
        private boolean closed;

        private RowIterator(Iterator<RawRecord> delegate, String sourceId, String objectName,
                            List<String> keyFields) {
            this.delegate = delegate;
            this.sourceId = sourceId;
            this.objectName = objectName;
            this.keyFields = keyFields;
        }

        @Override
        public boolean hasNext() {
            if (closed) return false;
            try {
                boolean available = delegate.hasNext();
                if (!available) close();
                return available;
            } catch (RuntimeException ex) {
                close();
                throw ex;
            }
        }

        @Override
        public SourceRecord next() {
            if (!hasNext()) throw new NoSuchElementException();
            try {
                return ordinary(sourceId, objectName, delegate.next(), keyFields);
            } catch (RuntimeException ex) {
                close();
                throw ex;
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            RecordComposer.close(delegate);
        }
    }
}
