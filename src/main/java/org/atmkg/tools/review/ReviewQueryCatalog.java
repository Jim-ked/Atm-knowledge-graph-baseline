package org.atmkg.tools.review;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal parser for the deliberately small, stable review/queries.yaml schema. */
final class ReviewQueryCatalog {
    private final Map<String, ReviewQueryTemplate> templates;
    private final String sourceText;

    private ReviewQueryCatalog(Map<String, ReviewQueryTemplate> templates, String sourceText) {
        if (templates.isEmpty()) throw new IllegalArgumentException("queries.yaml 没有查询模板");
        this.templates = Collections.unmodifiableMap(new LinkedHashMap<>(templates));
        this.sourceText = sourceText;
    }

    static ReviewQueryCatalog load(Path path) {
        try {
            return parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalStateException("无法读取 Review 查询模板：" + path, ex);
        }
    }

    private static ReviewQueryCatalog parse(String source) {
        Map<String, ReviewQueryTemplate> templates = new LinkedHashMap<>();
        Builder current = null;
        boolean inTemplates = false;
        boolean inCypher = false;
        for (String line : source.split("\\R", -1)) {
            if (line.equals("templates:")) {
                inTemplates = true;
                continue;
            }
            if (!inTemplates) continue;
            if (line.matches("  [A-Za-z][A-Za-z0-9_]*:")) {
                if (current != null) templates.put(current.name, current.build());
                current = new Builder(line.trim().substring(0, line.trim().length() - 1));
                inCypher = false;
                continue;
            }
            if (current == null) continue;
            if (line.startsWith("    title:")) {
                current.title = scalar(line);
                inCypher = false;
            } else if (line.startsWith("    locator:")) {
                current.locator = ReviewQueryTemplate.Locator.valueOf(scalar(line).toUpperCase());
                inCypher = false;
            } else if (line.startsWith("    parameters:")) {
                current.parameters = inlineList(scalar(line));
                inCypher = false;
            } else if (line.startsWith("    literal_parameters:")) {
                current.literals = inlineList(scalar(line));
                inCypher = false;
            } else if (line.equals("    cypher: |")) {
                inCypher = true;
            } else if (inCypher) {
                if (line.startsWith("      ")) current.cypher.append(line.substring(6));
                else if (!line.isBlank()) throw new IllegalArgumentException("queries.yaml Cypher 缩进错误：" + line);
                current.cypher.append('\n');
            }
        }
        if (current != null) templates.put(current.name, current.build());
        return new ReviewQueryCatalog(templates, source);
    }

    Map<String, ReviewQueryTemplate> templates() { return templates; }
    String sourceText() { return sourceText; }

    ReviewQueryTemplate template(String name) {
        ReviewQueryTemplate template = templates.get(name);
        if (template == null) throw new IllegalArgumentException("未知 Review 查询模板：" + name);
        return template;
    }

    private static String scalar(String line) {
        int colon = line.indexOf(':');
        return colon < 0 ? "" : line.substring(colon + 1).trim();
    }

    private static List<String> inlineList(String raw) {
        if (!raw.startsWith("[") || !raw.endsWith("]")) {
            throw new IllegalArgumentException("queries.yaml 参数列表必须使用 [a, b] 格式：" + raw);
        }
        String body = raw.substring(1, raw.length() - 1).trim();
        if (body.isEmpty()) return List.of();
        List<String> values = new ArrayList<>();
        for (String value : body.split(",")) {
            String item = value.trim();
            if (!item.matches("[A-Za-z][A-Za-z0-9_]*")) throw new IllegalArgumentException("非法参数名：" + item);
            values.add(item);
        }
        return values;
    }

    private static final class Builder {
        private final String name;
        private String title;
        private ReviewQueryTemplate.Locator locator;
        private List<String> parameters = List.of();
        private List<String> literals = List.of();
        private final StringBuilder cypher = new StringBuilder();

        private Builder(String name) { this.name = name; }

        private ReviewQueryTemplate build() {
            return new ReviewQueryTemplate(name, title, locator, parameters, literals, cypher.toString().trim());
        }
    }
}
