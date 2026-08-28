package org.atmkg.infra.source.compose;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Generic rules for turning physical rows into logical source records. */
public record RecordCompositionSpec(RecordMode recordMode, List<String> keyFields,
                                    List<String> groupBy, String orderBy) {
    public RecordCompositionSpec {
        recordMode = Objects.requireNonNull(recordMode, "recordMode");
        keyFields = fields(keyFields, "keyFields", true);
        groupBy = fields(groupBy, "groupBy", false);
        orderBy = orderBy == null ? "" : orderBy.trim();
        if (recordMode == RecordMode.GROUP_FIRST && groupBy.isEmpty()) {
            throw new IllegalArgumentException("group_first 必须配置 groupBy");
        }
        if (recordMode != RecordMode.ROW && orderBy.isBlank()) {
            throw new IllegalArgumentException(recordMode.configValue() + " 必须配置 orderBy");
        }
    }

    public static RecordMode parseMode(String value) {
        String normalized = value == null || value.isBlank()
                ? "ROW" : value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        try {
            return RecordMode.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("recordMode 仅支持 row / group_first / adjacent_next", ex);
        }
    }

    private static List<String> fields(List<String> input, String name, boolean required) {
        if (input == null) {
            if (required) throw new IllegalArgumentException(name + " 不能为空");
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String value : input) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 只能包含非空字符串");
            out.add(value.trim());
        }
        if (required && out.isEmpty()) throw new IllegalArgumentException(name + " 不能为空");
        return List.copyOf(out);
    }

    public enum RecordMode {
        ROW("row"), GROUP_FIRST("group_first"), ADJACENT_NEXT("adjacent_next");

        private final String configValue;

        RecordMode(String configValue) {
            this.configValue = configValue;
        }

        public String configValue() {
            return configValue;
        }
    }
}
