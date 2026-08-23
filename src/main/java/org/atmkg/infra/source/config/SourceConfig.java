package org.atmkg.infra.source.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * sources.yaml 的最小加载器。
 *
 * <p>本类只识别 sourceId 与 adapter；root、objects、table、sheet 等 adapter 专属字段原样交给
 * 对应 SourceAdapter 解释，从而避免把 Excel/JDBC/JSON/CSV 的物理细节带入 Core。
 */
public final class SourceConfig {
    private final Map<String, ConfiguredSource> sources;

    private SourceConfig(Map<String, ConfiguredSource> sources) {
        this.sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
    }

    public static SourceConfig load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("数据源配置文件不存在：" + file);
        }
        try {
            JsonNode root = new YAMLMapper().readTree(file.toFile());
            JsonNode array = root == null ? null : root.get("sources");
            if (array == null || !array.isArray()) throw new IllegalArgumentException("sources.yaml 缺少 sources 数组");
            Map<String, ConfiguredSource> parsed = new LinkedHashMap<>();
            for (JsonNode item : array) {
                if (!item.isObject()) throw new IllegalArgumentException("sources 中每一项都必须是对象");
                String sourceId = text(item, "sourceId");
                String adapter = text(item, "adapter");
                if (parsed.putIfAbsent(sourceId, new ConfiguredSource(sourceId, adapter, item.deepCopy())) != null) {
                    throw new IllegalArgumentException("重复 sourceId：" + sourceId);
                }
            }
            return new SourceConfig(parsed);
        } catch (IOException ex) {
            throw new IllegalArgumentException("数据源配置读取失败：" + file, ex);
        }
    }

    public Map<String, ConfiguredSource> getSources() { return sources; }

    public ConfiguredSource requireSource(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) throw new IllegalArgumentException("sourceId 不能为空");
        ConfiguredSource source = sources.get(sourceId.trim());
        if (source == null) throw new IllegalArgumentException("未配置数据源：" + sourceId);
        return source;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " 必须是非空字符串");
        }
        return value.textValue().trim();
    }
}
