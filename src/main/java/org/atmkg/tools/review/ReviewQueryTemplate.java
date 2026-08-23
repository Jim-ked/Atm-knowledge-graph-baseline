package org.atmkg.tools.review;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ReviewQueryTemplate {
    enum Locator { NONE, SINGLE, PAIR }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9_]*)}}");
    private static final Pattern DRIVER_PARAMETER = Pattern.compile("\\$([A-Za-z][A-Za-z0-9_]*)");

    private final String name;
    private final String title;
    private final Locator locator;
    private final List<String> parameters;
    private final List<String> literalParameters;
    private final String cypher;

    ReviewQueryTemplate(String name, String title, Locator locator, List<String> parameters,
                        List<String> literalParameters, String cypher) {
        this.name = requireText(name, "name");
        this.title = requireText(title, "title");
        this.locator = Objects.requireNonNull(locator, "locator");
        this.parameters = List.copyOf(parameters);
        this.literalParameters = List.copyOf(literalParameters);
        this.cypher = requireText(cypher, "cypher");
        for (String literal : this.literalParameters) {
            if (!this.cypher.contains("{{" + literal + "}}")) {
                throw new IllegalArgumentException("模板 " + name + " 未使用 literal 参数：" + literal);
            }
        }
        Set<String> usedParameters = new LinkedHashSet<>();
        Matcher parameterMatcher = DRIVER_PARAMETER.matcher(this.cypher);
        while (parameterMatcher.find()) usedParameters.add(parameterMatcher.group(1));
        for (String used : usedParameters) {
            if (!this.parameters.contains(used)) {
                throw new IllegalArgumentException("模板 " + name + " 使用了未声明参数：" + used);
            }
        }
        for (String declared : this.parameters) {
            if (!usedParameters.contains(declared)) {
                throw new IllegalArgumentException("模板 " + name + " 声明了未使用参数：" + declared);
            }
        }
    }

    String name() { return name; }
    String title() { return title; }
    Locator locator() { return locator; }
    List<String> parameters() { return parameters; }
    List<String> literalParameters() { return literalParameters; }
    String cypher() { return cypher; }

    String render(Map<String, String> literals) {
        String rendered = cypher;
        for (String name : literalParameters) {
            String value = literals.get(name);
            if (value == null || !value.matches("[1-9][0-9]*")) {
                throw new IllegalArgumentException(name + " 必须是大于 0 的整数");
            }
            rendered = rendered.replace("{{" + name + "}}", value);
        }
        Matcher unresolved = PLACEHOLDER.matcher(rendered);
        if (unresolved.find()) throw new IllegalArgumentException("缺少 literal 参数：" + unresolved.group(1));
        return rendered;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        return value;
    }
}
