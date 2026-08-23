package org.atmkg.core.model;

import java.util.Objects;

/** One logical source object participating in a coordinated full rebuild. */
public final class SourceScope {
    private final String sourceId;
    private final String objectName;

    public SourceScope(String sourceId, String objectName) {
        this.sourceId = requireText(sourceId, "sourceId");
        this.objectName = requireText(objectName, "objectName");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        return value;
    }

    public String getSourceId() { return sourceId; }
    public String getObjectName() { return objectName; }
}
