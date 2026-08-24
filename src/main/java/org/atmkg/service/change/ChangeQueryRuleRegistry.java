package org.atmkg.service.change;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 新增 kind 规则只改 {@code queries/change-query-rules.yaml}：左侧从 API GraphDTO/Viewer 实际
 * {@code GraphNodeDTO.kind} 取值，右侧 queryId 必须先存在于 {@code queries/query-templates.yaml}。
 *
 * <p>只有这张 kind→queryId 表的严格解析机制有 bug 才写 Java；不要加入 sourceId/table 条件 DSL。
 * KgServiceMain 会在正式启动时加载本 Registry，配置缺失或非法必须启动失败；当前结果只输出控制台摘要，
 * 没有 durable outward sink。
 */
public final class ChangeQueryRuleRegistry {
    private static final Set<String> ROOT_FIELDS = Set.of("associations");

    private final Map<String, List<String>> associations;

    private ChangeQueryRuleRegistry(Map<String, List<String>> associations) {
        this.associations = Collections.unmodifiableMap(new LinkedHashMap<>(associations));
    }

    public static ChangeQueryRuleRegistry load(Path file) {
        Objects.requireNonNull(file, "file");
        YAMLFactory factory = YAMLFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        try {
            JsonNode root = new YAMLMapper(factory).readTree(file.toFile());
            return parse(root);
        } catch (IOException ex) {
            throw new IllegalArgumentException(
                    "变化关联配置读取失败或包含重复 kind：" + file.toAbsolutePath(), ex);
        }
    }

    public List<String> queryIdsFor(String nodeKind) {
        if (nodeKind == null || nodeKind.isBlank()) return List.of();
        return associations.getOrDefault(nodeKind, List.of());
    }

    private static ChangeQueryRuleRegistry parse(JsonNode root) {
        if (root == null || !root.isObject()) throw invalid("根节点必须是 object");
        root.fieldNames().forEachRemaining(field -> {
            if (!ROOT_FIELDS.contains(field)) throw invalid("根节点包含未知字段：" + field);
        });
        JsonNode definitions = root.get("associations");
        if (definitions == null || !definitions.isObject()) {
            throw invalid("associations 必须是 object");
        }

        Map<String, List<String>> parsed = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = definitions.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String kind = requireText(entry.getKey(), "kind");
            JsonNode queryIds = entry.getValue();
            if (queryIds == null || !queryIds.isArray()) {
                throw invalid(kind + " 必须配置 queryId 数组");
            }
            List<String> values = new ArrayList<>();
            for (JsonNode queryId : queryIds) {
                if (!queryId.isTextual()) throw invalid(kind + " 的 queryId 必须是非空字符串");
                values.add(requireText(queryId.textValue(), kind + " 的 queryId"));
            }
            List<String> previous = parsed.put(kind, List.copyOf(values));
            if (previous != null) throw invalid("重复 kind：" + kind);
        }
        return new ChangeQueryRuleRegistry(parsed);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw invalid(name + " 不能为空");
        return value.trim();
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("变化关联配置无效：" + message);
    }
}
