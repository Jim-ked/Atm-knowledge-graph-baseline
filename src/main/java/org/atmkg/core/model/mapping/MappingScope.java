package org.atmkg.core.model.mapping;

import java.util.Objects;

/** A logical mapping scope identified by source and source object. */
public record MappingScope(String sourceId, String sourceObject) {
    public MappingScope {
        sourceId = require(sourceId, "sourceId");
        sourceObject = require(sourceObject, "sourceObject");
    }

    private static String require(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        return value.trim();
    }

    public String getSourceId() { return sourceId; }
    public String getSourceObject() { return sourceObject; }
}
