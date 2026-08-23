package org.atmkg.service.query;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.atmkg.core.error.QueryExecutionException;
import org.atmkg.core.model.QuerySpec;

/**
 * 新增 queryId 只改 {@code queries/query-templates.yaml}：复制现有模板，type 填 NEIGHBORS/K_HOP，
 * relationshipTypes/classFilters IRI 到正式 TTL 查证，并运行 QueryTemplateRegistryTest。
 *
 * <p>只有模板 schema 经批准要增加所有查询共用的安全字段时才写 Java。接受 raw Cypher/SQL/script 会绕过
 * QuerySpec 校验；放宽未知字段会让拼错配置静默失效。启动时报模板错误先查 queryId 重复、type/direction、
 * K_HOP depth 和空列表项，不要改 Neo4jQueryService。
 */
public final class QueryTemplateRegistry {
    private static final Set<String> ROOT_FIELDS = Set.of("templates");
    private static final Set<String> TEMPLATE_FIELDS = Set.of(
            "type", "direction", "depth", "relationshipTypes", "classFilters");

    private final Map<String, QueryTemplate> templates;

    private QueryTemplateRegistry(Map<String, QueryTemplate> templates) {
        this.templates = Collections.unmodifiableMap(new LinkedHashMap<>(templates));
    }

    public static QueryTemplateRegistry load(Path file) {
        Objects.requireNonNull(file, "file");
        YAMLFactory factory = YAMLFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        try {
            JsonNode root = new YAMLMapper(factory).readTree(file.toFile());
            return parse(root);
        } catch (IOException ex) {
            throw new IllegalArgumentException(
                    "查询模板配置读取失败或包含重复 queryId：" + file.toAbsolutePath(), ex);
        }
    }

    public QuerySpec resolve(String queryId, String startUid) {
        String id = requireRuntimeText(queryId, "queryId");
        String anchor = requireRuntimeText(startUid, "startUid");
        QueryTemplate template = templates.get(id);
        if (template == null) throw new QueryExecutionException("未知命名查询：" + id);
        return template.toQuerySpec(anchor);
    }

    private static QueryTemplateRegistry parse(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw invalid("根节点必须是 object");
        }
        rejectUnknownFields(root, ROOT_FIELDS, "根节点");
        JsonNode definitions = root.get("templates");
        if (definitions == null || !definitions.isObject()) {
            throw invalid("templates 必须是 object");
        }

        Map<String, QueryTemplate> parsed = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = definitions.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String queryId = requireConfigText(entry.getKey(), "queryId");
            JsonNode definition = entry.getValue();
            if (definition == null || !definition.isObject()) {
                throw invalid("模板 " + queryId + " 必须是 object");
            }
            rejectUnknownFields(definition, TEMPLATE_FIELDS, "模板 " + queryId);
            QueryTemplate previous = parsed.put(queryId, parseTemplate(queryId, definition));
            if (previous != null) throw invalid("重复 queryId：" + queryId);
        }
        return new QueryTemplateRegistry(parsed);
    }

    private static QueryTemplate parseTemplate(String queryId, JsonNode definition) {
        String typeText = requiredTextField(definition, "type", queryId);
        QuerySpec.Type type;
        try {
            type = QuerySpec.Type.valueOf(typeText);
        } catch (IllegalArgumentException ex) {
            throw invalid("模板 " + queryId + " 的 type 未知：" + typeText);
        }
        if (type != QuerySpec.Type.NEIGHBORS && type != QuerySpec.Type.K_HOP) {
            throw invalid("模板 " + queryId + " 的 type 只允许 NEIGHBORS 或 K_HOP");
        }

        QuerySpec.Direction direction = QuerySpec.Direction.BOTH;
        JsonNode directionNode = definition.get("direction");
        if (directionNode != null) {
            String directionText = textValue(directionNode, "模板 " + queryId + " 的 direction");
            try {
                direction = QuerySpec.Direction.valueOf(directionText);
            } catch (IllegalArgumentException ex) {
                throw invalid("模板 " + queryId + " 的 direction 非法：" + directionText);
            }
        }

        Integer depth = optionalDepth(definition.get("depth"), queryId);
        if (type == QuerySpec.Type.K_HOP && depth == null) {
            throw invalid("模板 " + queryId + " 的 K_HOP 必须配置 depth >= 1");
        }
        if (type == QuerySpec.Type.NEIGHBORS && depth != null) {
            throw invalid("模板 " + queryId + " 的 NEIGHBORS 不允许配置 depth");
        }

        return new QueryTemplate(type, direction, depth,
                stringSet(definition.get("relationshipTypes"), "relationshipTypes", queryId),
                stringSet(definition.get("classFilters"), "classFilters", queryId));
    }

    private static Integer optionalDepth(JsonNode node, String queryId) {
        if (node == null) return null;
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 1) {
            throw invalid("模板 " + queryId + " 的 depth 必须是 >= 1 的整数");
        }
        return node.intValue();
    }

    private static Set<String> stringSet(JsonNode node, String field, String queryId) {
        if (node == null) return Set.of();
        if (!node.isArray()) throw invalid("模板 " + queryId + " 的 " + field + " 必须是数组");
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode item : node) {
            values.add(textValue(item, "模板 " + queryId + " 的 " + field + " 项"));
        }
        return Collections.unmodifiableSet(values);
    }

    private static String requiredTextField(JsonNode node, String field, String queryId) {
        JsonNode value = node.get(field);
        if (value == null) throw invalid("模板 " + queryId + " 缺少 " + field);
        return textValue(value, "模板 " + queryId + " 的 " + field);
    }

    private static String textValue(JsonNode node, String name) {
        if (node == null || !node.isTextual()) throw invalid(name + " 必须是非空字符串");
        return requireConfigText(node.textValue(), name);
    }

    private static String requireConfigText(String value, String name) {
        if (value == null || value.isBlank()) throw invalid(name + " 不能为空");
        return value.trim();
    }

    private static String requireRuntimeText(String value, String name) {
        if (value == null || value.isBlank()) throw new QueryExecutionException(name + " 不能为空");
        return value.trim();
    }

    private static void rejectUnknownFields(JsonNode node, Set<String> allowed, String location) {
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) throw invalid(location + " 包含未知字段：" + field);
        });
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("查询模板配置无效：" + message);
    }

    private record QueryTemplate(QuerySpec.Type type, QuerySpec.Direction direction, Integer depth,
                                 Set<String> relationshipTypes, Set<String> classFilters) {
        private QuerySpec toQuerySpec(String startUid) {
            return new QuerySpec(type, startUid, null, depth, relationshipTypes, classFilters,
                    direction, null, Map.of());
        }
    }
}
