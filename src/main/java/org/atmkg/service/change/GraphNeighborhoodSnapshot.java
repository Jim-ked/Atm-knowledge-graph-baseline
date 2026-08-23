package org.atmkg.service.change;

import java.util.Objects;
import org.atmkg.core.model.GraphDTO;

/** The complete current one-hop GraphDTO returned for one explicit anchor UID. */
public final class GraphNeighborhoodSnapshot {
    private final String anchorUid;
    private final GraphDTO currentGraph;

    public GraphNeighborhoodSnapshot(String anchorUid, GraphDTO currentGraph) {
        if (anchorUid == null || anchorUid.isBlank()) throw new IllegalArgumentException("anchorUid 不能为空");
        this.anchorUid = anchorUid;
        this.currentGraph = Objects.requireNonNull(currentGraph, "currentGraph");
    }

    public String getAnchorUid() { return anchorUid; }
    public GraphDTO getCurrentGraph() { return currentGraph; }
}
