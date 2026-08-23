package org.atmkg.core.model;

import java.util.Objects;

/** Stable physical-source identity used to reconcile one source record's graph projection. */
public final class SourceRef {
    private final String sourceId;
    private final String objectName;
    private final String sourceKey;

    public SourceRef(String sourceId, String objectName, String sourceKey) {
        this.sourceId = requireText(sourceId, "sourceId");
        this.objectName = requireText(objectName, "objectName");
        this.sourceKey = requireText(sourceKey, "sourceKey");
    }

    public static SourceRef from(SourceRecord record) {
        Objects.requireNonNull(record, "record");
        return new SourceRef(record.getSourceId(), record.getObjectName(), record.getSourceKey());
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        return value;
    }

    public String getSourceId() { return sourceId; }
    public String getObjectName() { return objectName; }
    public String getSourceKey() { return sourceKey; }
}
