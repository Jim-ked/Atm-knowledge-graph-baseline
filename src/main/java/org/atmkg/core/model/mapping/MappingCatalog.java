package org.atmkg.core.model.mapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class MappingCatalog {
    private final List<EntityMappingSpec> entities;
    private final List<PropertyMappingSpec> properties;
    private final List<RelationshipMappingSpec> relationships;

    public MappingCatalog(List<EntityMappingSpec> entities, List<PropertyMappingSpec> properties,
                          List<RelationshipMappingSpec> relationships) {
        this.entities = List.copyOf(Objects.requireNonNull(entities));
        this.properties = List.copyOf(Objects.requireNonNull(properties));
        this.relationships = List.copyOf(Objects.requireNonNull(relationships));
    }

    public List<EntityMappingSpec> getEntities() { return entities; }
    public List<PropertyMappingSpec> getProperties() { return properties; }
    public List<RelationshipMappingSpec> getRelationships() { return relationships; }

    public List<EntityMappingSpec> entityMappingsFor(String sourceId, String sourceObject) {
        List<EntityMappingSpec> out = new ArrayList<>();
        for (EntityMappingSpec spec : entities) {
            if (spec.getSourceId().equals(sourceId) && spec.getSourceObject().equals(sourceObject)) out.add(spec);
        }
        return out;
    }

    public List<PropertyMappingSpec> propertyMappingsFor(String sourceId, String sourceObject, String classIri) {
        List<PropertyMappingSpec> out = new ArrayList<>();
        for (PropertyMappingSpec spec : properties) {
            if (spec.getSourceId().equals(sourceId)
                    && spec.getSourceObject().equals(sourceObject)
                    && spec.getClassIri().equals(classIri)) out.add(spec);
        }
        return out;
    }

    public List<RelationshipMappingSpec> relationshipMappingsFor(String sourceId, String sourceObject) {
        List<RelationshipMappingSpec> out = new ArrayList<>();
        for (RelationshipMappingSpec spec : relationships) {
            if (spec.getSourceId().equals(sourceId) && spec.getSourceObject().equals(sourceObject)) out.add(spec);
        }
        return out;
    }

    public Optional<EntityMappingSpec> uniqueEntityMapping(String sourceId, String classIri) {
        EntityMappingSpec found = null;
        for (EntityMappingSpec spec : entities) {
            if (!spec.getSourceId().equals(sourceId) || !spec.getClassIri().equals(classIri)) continue;
            if (found != null) return Optional.empty();
            found = spec;
        }
        return Optional.ofNullable(found);
    }

}
