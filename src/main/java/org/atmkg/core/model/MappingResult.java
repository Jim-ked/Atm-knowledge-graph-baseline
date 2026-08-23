package org.atmkg.core.model;

import java.util.List;
import java.util.Objects;

public final class MappingResult {
    private final List<GraphEntity> entities;
    private final List<GraphRelationship> relationships;

    public MappingResult(List<GraphEntity> entities, List<GraphRelationship> relationships) {
        this.entities = List.copyOf(Objects.requireNonNull(entities));
        this.relationships = List.copyOf(Objects.requireNonNull(relationships));
    }

    public List<GraphEntity> getEntities() { return entities; }
    public List<GraphRelationship> getRelationships() { return relationships; }
}
