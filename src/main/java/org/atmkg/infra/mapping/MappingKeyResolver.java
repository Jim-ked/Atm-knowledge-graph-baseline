package org.atmkg.infra.mapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.atmkg.core.error.MappingExecutionException;

/** Resolves the workbook's single- or multi-field business-key expression. */
public final class MappingKeyResolver {
    private MappingKeyResolver() {}

    /**
     * Resolves a semicolon-separated list of source paths. A single path keeps the
     * historical trimmed value; multiple values use the source-key escaping rules.
     */
    public static String resolve(Map<String, Object> fields, String expression) {
        if (fields == null) throw new MappingExecutionException("业务键字段不存在：" + expression);
        List<String> paths = paths(expression);
        List<String> values = new ArrayList<>(paths.size());
        for (String path : paths) {
            Object value = readPath(fields, path);
            String text = value == null ? "" : String.valueOf(value).trim();
            if (text.isBlank()) {
                throw new MappingExecutionException("业务键字段缺失或为空：" + path);
            }
            values.add(text);
        }
        if (values.size() == 1) return values.get(0);
        return String.join("|", values.stream().map(MappingKeyResolver::escape).toList());
    }

    /** Optional endpoint form preserves existing relationship rows whose alternative locator is absent. */
    public static Optional<String> resolveOptional(Map<String, Object> fields, String expression) {
        List<String> paths = paths(expression);
        for (String path : paths) {
            Object value = readPath(fields, path);
            if (value == null || String.valueOf(value).trim().isBlank()) return Optional.empty();
        }
        return Optional.of(resolve(fields, expression));
    }

    private static List<String> paths(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new MappingExecutionException("业务键字段路径不能为空");
        }
        String[] parts = expression.split(";", -1);
        List<String> paths = new ArrayList<>(parts.length);
        for (String part : parts) {
            String path = part.trim();
            if (path.isBlank()) throw new MappingExecutionException("业务键字段路径不能为空：" + expression);
            paths.add(path);
        }
        return List.copyOf(paths);
    }

    @SuppressWarnings("unchecked")
    private static Object readPath(Map<String, Object> fields, String path) {
        Object current = fields;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = ((Map<String, Object>) map).get(part);
            if (current == null) return null;
        }
        return current;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("|", "\\|");
    }
}
