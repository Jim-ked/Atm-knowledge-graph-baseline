package org.atmkg.infra.source.config;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * 一个已解析的数据源定义。
 *
 * <p>这里只保存物理接入信息：sourceId、adapter 以及该 adapter 自己的配置节点。
 * 航空业务字段、本体类和关系语义不得放到这里；它们仍由 mapping/字段映射.xlsx 解释。
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
