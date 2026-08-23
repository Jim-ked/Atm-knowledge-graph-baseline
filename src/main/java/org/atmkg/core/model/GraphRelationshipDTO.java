package org.atmkg.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class GraphRelationshipDTO {
    private final String id;
    private final String source;
    private final String target;
    private final String type;
    private final Map<String, Object> properties;

    public GraphRelationshipDTO(String id, String source, String target, String type, Map<String, Object> properties) {
        this.id = Objects.requireNonNull(id);
        this.source = Objects.requireNonNull(source);
        this.target = Objects.requireNonNull(target);
        this.type = Objects.requireNonNull(type);
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(properties)));
    }

    public String getId() { return id; }
    public String getSource() { return source; }
    public String getTarget() { return target; }
    public String getType() { return type; }
    public Map<String, Object> getProperties() { return properties; }
}
