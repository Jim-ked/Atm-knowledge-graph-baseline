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
 * 新增 Excel/JDBC source 时只编辑 {@code config/sources.yaml}：复制一个 {@code - sourceId} 块，
 * 填 adapter 和 objects，不需要修改本类。这里仅解析 sourceId/adapter 并保留整个 JsonNode；
 * files/sheet 由 ExcelSourceAdapter 校验，table/view 由 JdbcSourceAdapter 校验。
 *
 * <p>只有所有 adapter 共享的顶层 schema 需要新增必填字段时才改 Java。把业务字段或本体 IRI 加到这里会
 * 让物理读取层耦合业务语义。报“缺少 sources 数组/重复 sourceId”查本类和 YAML 顶层；报 object 字段错误
 * 去对应 Adapter。
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
