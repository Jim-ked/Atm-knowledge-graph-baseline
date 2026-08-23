package org.atmkg.infra.source.config;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * 普通需求不改此值对象。新增表/文件去 {@code config/sources.yaml}，新增字段语义去
 * {@code mapping/字段映射.xlsx}，新增本体术语去正式 TTL。
 *
 * <p>只有 SourceConfig 与 Adapter 之间必须传递新的“所有 adapter 共用”信息时才考虑修改。不要给它增加
 * Airport/Route/Airspace 字段；误加会形成第二套 mapping。配置值不对先看 SourceConfig 加载的原始 JsonNode。
 */
public final class ConfiguredSource {
    private final String sourceId;
    private final String adapter;
    private final JsonNode config;

    ConfiguredSource(String sourceId, String adapter, JsonNode config) {
        this.sourceId = requireText(sourceId, "sourceId");
        this.adapter = requireText(adapter, "adapter");
        this.config = Objects.requireNonNull(config, "config");
    }

    public String getSourceId() { return sourceId; }
    public String getAdapter() { return adapter; }
    public JsonNode getConfig() { return config; }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        return value.trim();
    }
}
