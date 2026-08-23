package org.atmkg.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class GraphDTO {
    private final String schemaVersion;
    private final List<GraphNodeDTO> nodes;
    private final List<GraphRelationshipDTO> relationships;
    private final Map<String, Object> meta;

    public GraphDTO(String schemaVersion, List<GraphNodeDTO> nodes, List<GraphRelationshipDTO> relationships, Map<String, Object> meta) {
        this.schemaVersion = Objects.requireNonNull(schemaVersion);
        this.nodes = List.copyOf(Objects.requireNonNull(nodes));
        this.relationships = List.copyOf(Objects.requireNonNull(relationships));
        this.meta = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(meta)));
    }

    public String getSchemaVersion() { return schemaVersion; }
    public List<GraphNodeDTO> getNodes() { return nodes; }
    public List<GraphRelationshipDTO> getRelationships() { return relationships; }
    public Map<String, Object> getMeta() { return meta; }
}
