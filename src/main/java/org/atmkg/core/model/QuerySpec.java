package org.atmkg.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class QuerySpec {
    public enum Type { ENTITY, NEIGHBORS, K_HOP, PATH, NAMED }
    public enum Direction { OUTGOING, INCOMING, BOTH }

    private final Type type;
    private final String startUid;
    private final String targetUid;
    private final Integer depth;
    private final Set<String> relationshipTypes;
    private final Set<String> classFilters;
    private final Direction direction;
    private final String queryId;
    private final Map<String, Object> parameters;

    public QuerySpec(Type type, String startUid, String targetUid, Integer depth,
                     Set<String> relationshipTypes, Set<String> classFilters,
                     Direction direction, String queryId, Map<String, Object> parameters) {
        this.type = type;
        this.startUid = startUid;
        this.targetUid = targetUid;
        this.depth = depth;
        this.relationshipTypes = immutableSet(relationshipTypes);
        this.classFilters = immutableSet(classFilters);
        this.direction = direction == null ? Direction.BOTH : direction;
        this.queryId = queryId;
        this.parameters = parameters == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    }

    private static Set<String> immutableSet(Set<String> values) {
        return values == null ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    public Type getType() { return type; }
    public String getStartUid() { return startUid; }
    public String getTargetUid() { return targetUid; }
    public Integer getDepth() { return depth; }
    public Set<String> getRelationshipTypes() { return relationshipTypes; }
    public Set<String> getClassFilters() { return classFilters; }
    public Direction getDirection() { return direction; }
    public String getQueryId() { return queryId; }
    public Map<String, Object> getParameters() { return parameters; }
}
