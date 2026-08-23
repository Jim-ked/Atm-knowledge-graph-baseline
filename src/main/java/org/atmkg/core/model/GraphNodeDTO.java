package org.atmkg.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class GraphNodeDTO {
    private final String id;
    private final List<String> labels;
    private final String kind;
    private final String caption;
    private final Map<String, Object> properties;

    public GraphNodeDTO(String id, List<String> labels, String kind, String caption, Map<String, Object> properties) {
        this.id = Objects.requireNonNull(id);
        this.labels = List.copyOf(Objects.requireNonNull(labels));
        this.kind = kind;
        this.caption = caption;
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(properties)));
    }

    public String getId() { return id; }
    public List<String> getLabels() { return labels; }
    public String getKind() { return kind; }
    public String getCaption() { return caption; }
    public Map<String, Object> getProperties() { return properties; }
}
