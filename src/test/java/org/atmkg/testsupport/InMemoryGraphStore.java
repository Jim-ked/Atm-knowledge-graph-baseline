package org.atmkg.testsupport;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.atmkg.core.model.GraphEntity;
import org.atmkg.core.model.GraphRelationship;
import org.atmkg.core.model.GraphProjectionSnapshot;
import org.atmkg.core.model.GraphStoreStats;
import org.atmkg.core.model.MappingResult;
import org.atmkg.core.model.SourceRef;
import org.atmkg.core.spi.GraphStore;

/** Test double only. Production code must use GraphStore SPI rather than depend on this class. */
public final class InMemoryGraphStore implements GraphStore {
    private final Map<String, GraphEntity> entities = new LinkedHashMap<>();
    private final Map<String, GraphRelationship> relationships = new LinkedHashMap<>();

    public void initializeSchema() {}

    public void upsertEntities(Collection<GraphEntity> values) {
        for (GraphEntity value : values) entities.put(value.getUid(), value);
    }

    public void upsertRelationships(Collection<GraphRelationship> values) {
        for (GraphRelationship value : values) {
            if (!entities.containsKey(value.getSourceUid()) || !entities.containsKey(value.getTargetUid())) {
                throw new IllegalStateException("关系端点不存在：" + value.getUid());
            }
            relationships.put(value.getUid(), value);
        }
    }

    public void replaceProjection(SourceRef ref, MappingResult current) {
        var currentUids = current.getEntities().stream().map(GraphEntity::getUid).collect(java.util.stream.Collectors.toSet());
        var staleUids = entities.values().stream()
                .filter(e -> hasRef(e.getProvenance(), ref) && !currentUids.contains(e.getUid()))
                .map(GraphEntity::getUid)
                .collect(java.util.stream.Collectors.toSet());
        boolean hasForeignIncidentRelationship = relationships.values().stream().anyMatch(r ->
                (staleUids.contains(r.getSourceUid()) || staleUids.contains(r.getTargetUid()))
                        && !hasRef(r.getProvenance(), ref));
        if (hasForeignIncidentRelationship) {
            throw new IllegalStateException("拒绝删除仍被其他源记录关系引用的实体：" + ref);
        }
        relationships.values().removeIf(r -> hasRef(r.getProvenance(), ref));
        entities.values().removeIf(e -> hasRef(e.getProvenance(), ref) && !currentUids.contains(e.getUid()));
        upsertEntities(current.getEntities());
        upsertRelationships(current.getRelationships());
    }

    public GraphProjectionSnapshot deleteProjection(SourceRef ref) {
        var entityUids = entities.values().stream()
                .filter(entity -> hasRef(entity.getProvenance(), ref))
                .map(GraphEntity::getUid)
                .toList();
        var ownedRelationships = relationships.values().stream()
                .filter(relationship -> hasRef(relationship.getProvenance(), ref))
                .toList();
        var anchors = new java.util.LinkedHashSet<String>(entityUids);
        ownedRelationships.forEach(relationship -> {
            anchors.add(relationship.getSourceUid());
            anchors.add(relationship.getTargetUid());
        });
        GraphProjectionSnapshot snapshot = new GraphProjectionSnapshot(entityUids,
                ownedRelationships.stream().map(GraphRelationship::getUid).toList(), java.util.List.copyOf(anchors));
        replaceProjection(ref, new MappingResult(java.util.List.of(), java.util.List.of()));
        return snapshot;
    }
    public void deleteEntity(String uid) {
        entities.remove(uid);
        relationships.values().removeIf(r -> r.getSourceUid().equals(uid) || r.getTargetUid().equals(uid));
    }
    public void deleteRelationship(String uid) { relationships.remove(uid); }
    public Optional<GraphEntity> findEntity(String uid) { return Optional.ofNullable(entities.get(uid)); }
    public void clearProject() { entities.clear(); relationships.clear(); }
    public GraphStoreStats stats() { return new GraphStoreStats(entities.size(), relationships.size()); }

    public Collection<GraphRelationship> relationships() { return relationships.values(); }

    private boolean hasRef(Map<String, Object> provenance, SourceRef ref) {
        return ref.getSourceId().equals(provenance.get("sourceId"))
                && ref.getObjectName().equals(provenance.get("sourceObject"))
                && ref.getSourceKey().equals(provenance.get("sourceKey"));
    }
}
