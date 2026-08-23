package org.atmkg.core.model;

public final class GraphStoreStats {
    private final long entityCount;
    private final long relationshipCount;

    public GraphStoreStats(long entityCount, long relationshipCount) {
        this.entityCount = entityCount;
        this.relationshipCount = relationshipCount;
    }

    public long getEntityCount() { return entityCount; }
    public long getRelationshipCount() { return relationshipCount; }
}
