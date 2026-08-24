package org.atmkg.infra.neo4j;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.atmkg.core.error.GraphStoreException;

/** 把通用 mapping 值转换为 Neo4j 属性可接受的标量或数组；不解释业务字段语义。 */
final class Neo4jValueNormalizer {
    private Neo4jValueNormalizer() {}

    static Map<String, Object> normalizeProperties(Map<String, Object> values) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Object normalized = normalize(entry.getValue());
            if (normalized != null) out.put(entry.getKey(), normalized);
        }
        return out;
    }

    static Object normalize(Object value) {
        if (value == null) return null;
        if (value instanceof String || value instanceof Boolean
                || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double
                || value instanceof LocalDate || value instanceof LocalTime || value instanceof LocalDateTime
                || value instanceof OffsetTime || value instanceof OffsetDateTime || value instanceof ZonedDateTime) {
            return value;
        }
        if (value instanceof BigDecimal) return ((BigDecimal) value).doubleValue();
        if (value instanceof BigInteger) return ((BigInteger) value).longValueExact();
        if (value instanceof Collection<?>) {
            ArrayList<Object> out = new ArrayList<>();
            for (Object item : (Collection<?>) value) {
                Object normalized = normalize(item);
                if (normalized instanceof Map<?, ?> || normalized instanceof Collection<?>) {
                    throw new GraphStoreException("Neo4j 属性不支持嵌套集合/Map");
                }
                out.add(normalized);
            }
            return out;
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            ArrayList<Object> out = new ArrayList<>(length);
            for (int i = 0; i < length; i++) out.add(normalize(java.lang.reflect.Array.get(value, i)));
            return out;
        }
        if (value instanceof Map<?, ?>) {
            throw new GraphStoreException("Neo4j 节点/关系属性不支持 Map 值：" + value.getClass().getName());
        }
        throw new GraphStoreException("不支持的 Neo4j 属性类型：" + value.getClass().getName());
    }
}
