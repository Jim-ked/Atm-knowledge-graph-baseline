package org.atmkg.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class GraphEntity {
    private final String uid;
    private final String classIri;
    private final String caption;
    private final Map<String, Object> properties;
    private final Map<String, Object> provenance;

    public GraphEntity(String uid, String classIri, String caption, Map<String, Object> properties, Map<String, Object> provenance) {
        this.uid = Objects.requireNonNull(uid);
        this.classIri = Objects.requireNonNull(classIri);
        this.caption = caption;
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(properties)));
        this.provenance = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(provenance)));
    }

    public String getUid() { return uid; }
    public String getClassIri() { return classIri; }
    public String getCaption() { return caption; }
    public Map<String, Object> getProperties() { return properties; }
    public Map<String, Object> getProvenance() { return provenance; }
}
