package org.atmkg.core.model;

import java.time.Instant;
import java.util.Objects;

public final class ChangeEvent {
    public enum Operation { UPSERT, DELETE }

    private final String eventId;
    private final String sourceId;
    private final String objectName;
    private final String sourceKey;
    private final Operation operation;
    private final Instant occurredAt;

    public ChangeEvent(String eventId, String sourceId, String objectName, String sourceKey, Operation operation, Instant occurredAt) {
        this.eventId = Objects.requireNonNull(eventId);
        this.sourceId = Objects.requireNonNull(sourceId);
        this.objectName = Objects.requireNonNull(objectName);
        this.sourceKey = Objects.requireNonNull(sourceKey);
        this.operation = Objects.requireNonNull(operation);
        this.occurredAt = Objects.requireNonNull(occurredAt);
    }

    public String getEventId() { return eventId; }
    public String getSourceId() { return sourceId; }
    public String getObjectName() { return objectName; }
    public String getSourceKey() { return sourceKey; }
    public Operation getOperation() { return operation; }
    public Instant getOccurredAt() { return occurredAt; }
}
