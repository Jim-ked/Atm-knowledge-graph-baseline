package org.atmkg.tools.review;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Expands bound parameters only for copy/paste display; its output is never sent to the Driver. */
final class BrowserCypherRenderer {
    private static final Pattern DRIVER_PARAMETER = Pattern.compile("\\$([A-Za-z][A-Za-z0-9_]*)");

    private BrowserCypherRenderer() {}

    static String render(String parameterizedCypher, Map<String, Object> parameters) {
        Matcher matcher = DRIVER_PARAMETER.matcher(parameterizedCypher);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!parameters.containsKey(name)) throw new IllegalArgumentException("Browser Cypher 缺少参数：" + name);
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(literal(parameters.get(name))));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private static String literal(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return "'" + escape(text) + "'";
        if (value instanceof Boolean bool) return bool.toString();
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof java.math.BigInteger
                || value instanceof java.math.BigDecimal) return value.toString();
        if (value instanceof Float number) {
            if (!Float.isFinite(number)) throw new IllegalArgumentException("Browser Cypher 不支持非有限浮点数");
            return number.toString();
        }
        if (value instanceof Double number) {
            if (!Double.isFinite(number)) throw new IllegalArgumentException("Browser Cypher 不支持非有限浮点数");
            return number.toString();
        }
        throw new IllegalArgumentException("Browser Cypher 不支持参数类型：" + value.getClass().getName());
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\': escaped.append("\\\\"); break;
                case '\'': escaped.append("\\'"); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                case '\b': escaped.append("\\b"); break;
                case '\f': escaped.append("\\f"); break;
                default:
                    if (ch < 0x20) escaped.append(String.format(Locale.ROOT, "\\u%04X", (int) ch));
                    else escaped.append(ch);
            }
        }
        return escaped.toString();
    }
}
