package org.atmkg.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class GraphRelationship {
    private final String uid;
    private final String predicateIri;
    private final String sourceUid;
    private final String targetUid;
    private final Map<String, Object> properties;
    private final Map<String, Object> provenance;

    public GraphRelationship(String uid, String predicateIri, String sourceUid, String targetUid, Map<String, Object> properties, Map<String, Object> provenance) {
        this.uid = Objects.requireNonNull(uid);
        this.predicateIri = Objects.requireNonNull(predicateIri);
        this.sourceUid = Objects.requireNonNull(sourceUid);
        this.targetUid = Objects.requireNonNull(targetUid);
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(properties)));
        this.provenance = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(provenance)));
    }

    public String getUid() { return uid; }
    public String getPredicateIri() { return predicateIri; }
    public String getSourceUid() { return sourceUid; }
    public String getTargetUid() { return targetUid; }
    public Map<String, Object> getProperties() { return properties; }
    public Map<String, Object> getProvenance() { return provenance; }
}
