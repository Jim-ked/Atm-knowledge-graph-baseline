package org.atmkg.service.change;

import java.util.Objects;
import org.atmkg.core.model.GraphDTO;

/** One named association query result, kept separate by the node that anchored it. */
public final class AssociationQueryResult {
    private final String anchorUid;
    private final String anchorKind;
    private final String queryId;
    private final GraphDTO graph;

    public AssociationQueryResult(String anchorUid, String anchorKind, String queryId, GraphDTO graph) {
        this.anchorUid = requireText(anchorUid, "anchorUid");
        this.anchorKind = requireText(anchorKind, "anchorKind");
        this.queryId = requireText(queryId, "queryId");
        this.graph = Objects.requireNonNull(graph, "graph");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        return value;
    }

    public String getAnchorUid() { return anchorUid; }
    public String getAnchorKind() { return anchorKind; }
    public String getQueryId() { return queryId; }
    public GraphDTO getGraph() { return graph; }
}
