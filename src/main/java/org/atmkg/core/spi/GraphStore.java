package org.atmkg.core.spi;

import java.util.Collection;
import java.util.Optional;
import org.atmkg.core.model.GraphEntity;
import org.atmkg.core.model.GraphRelationship;
import org.atmkg.core.model.GraphStoreStats;
import org.atmkg.core.model.MappingResult;
import org.atmkg.core.model.SourceRef;

/** Persistence boundary for the graph projection. Neo4j-specific details must stay behind this SPI. */
public interface GraphStore {
    void initializeSchema();
    void upsertEntities(Collection<GraphEntity> entities);
    void upsertRelationships(Collection<GraphRelationship> relationships);

    /**
     * Atomically replaces the projection produced by one authoritative source record.
     * Obsolete relationships/properties from the previous projection must not survive.
     */
    void replaceProjection(SourceRef sourceRef, MappingResult currentProjection);

    /** Remove the graph projection of one source record, e.g. after authoritative deletion. */
    void deleteProjection(SourceRef sourceRef);

    void deleteEntity(String uid);
    void deleteRelationship(String uid);
    Optional<GraphEntity> findEntity(String uid);

    /** Project-scoped maintenance operation used by explicit full rebuild only. */
    void clearProject();
    GraphStoreStats stats();
}
