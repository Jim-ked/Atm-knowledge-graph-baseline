package org.atmkg.core.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class SourceRecord {
    private final String sourceId;
    private final String objectName;
    private final String sourceKey;
    private final Map<String, Object> fields;
    private final Instant sourceTimestamp;

    public SourceRecord(String sourceId, String objectName, String sourceKey, Map<String, Object> fields, Instant sourceTimestamp) {
        this.sourceId = Objects.requireNonNull(sourceId);
        this.objectName = Objects.requireNonNull(objectName);
        this.sourceKey = Objects.requireNonNull(sourceKey);
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(fields)));
        this.sourceTimestamp = sourceTimestamp;
    }

    public String getSourceId() { return sourceId; }
    public String getObjectName() { return objectName; }
    public String getSourceKey() { return sourceKey; }
    public Map<String, Object> getFields() { return fields; }
    public Instant getSourceTimestamp() { return sourceTimestamp; }
}
