package org.atmkg.service.sync;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.atmkg.core.model.MappingResult;
import org.atmkg.core.model.SourceRef;

/**
 * Application-level notice that a source record's change has already been committed to the graph projection.
 * This is deliberately separate from the source-side ChangeEvent discovery signal.
 */
public final class GraphChangeNotice {
    public enum Operation { UPSERT, DELETE }

    private final SourceRef sourceRef;
    private final Operation operation;
    private final List<String> entityUids;
    private final List<String> relationshipUids;
    private final List<String> anchorEntityUids;
    private final Instant occurredAt;

    public GraphChangeNotice(SourceRef sourceRef, Operation operation, List<String> entityUids,
                             List<String> relationshipUids, Instant occurredAt) {
        this(sourceRef, operation, entityUids, relationshipUids,
                operation == Operation.UPSERT ? distinct(entityUids) : List.of(), occurredAt);
    }

    private GraphChangeNotice(SourceRef sourceRef, Operation operation, List<String> entityUids,
                              List<String> relationshipUids, List<String> anchorEntityUids, Instant occurredAt) {
        this.sourceRef = Objects.requireNonNull(sourceRef, "sourceRef");
        this.operation = Objects.requireNonNull(operation, "operation");
        this.entityUids = List.copyOf(Objects.requireNonNull(entityUids, "entityUids"));
        this.relationshipUids = List.copyOf(Objects.requireNonNull(relationshipUids, "relationshipUids"));
        this.anchorEntityUids = List.copyOf(Objects.requireNonNull(anchorEntityUids, "anchorEntityUids"));
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }

    /** Builds an UPSERT notice exclusively from the MappingResult that was committed to GraphStore. */
    public static GraphChangeNotice forUpsert(SourceRef sourceRef, MappingResult result, Instant occurredAt) {
        Objects.requireNonNull(result, "result");
        List<String> entityUids = result.getEntities().stream().map(entity -> entity.getUid()).toList();
        List<String> relationshipUids = result.getRelationships().stream()
                .map(relationship -> relationship.getUid()).toList();
        LinkedHashSet<String> anchors = new LinkedHashSet<>(entityUids);
        result.getRelationships().forEach(relationship -> {
            anchors.add(relationship.getSourceUid());
            anchors.add(relationship.getTargetUid());
        });
        return new GraphChangeNotice(sourceRef, Operation.UPSERT, entityUids, relationshipUids,
                List.copyOf(anchors), occurredAt);
    }

    public static GraphChangeNotice forDelete(SourceRef sourceRef, Instant occurredAt) {
        return new GraphChangeNotice(sourceRef, Operation.DELETE, List.of(), List.of(), List.of(), occurredAt);
    }

    private static List<String> distinct(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(Objects.requireNonNull(values, "values")));
    }

    public SourceRef getSourceRef() { return sourceRef; }
    public Operation getOperation() { return operation; }
    public List<String> getEntityUids() { return entityUids; }
    public List<String> getRelationshipUids() { return relationshipUids; }
    public List<String> getAnchorEntityUids() { return anchorEntityUids; }
    public Instant getOccurredAt() { return occurredAt; }
}
