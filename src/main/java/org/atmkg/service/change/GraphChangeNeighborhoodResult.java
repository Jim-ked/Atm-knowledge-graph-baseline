package org.atmkg.service.change;

import java.util.List;
import java.util.Objects;
import org.atmkg.service.sync.GraphChangeNotice;

/** Per-notice projection result; snapshots remain separate so consumers can replace each anchor neighborhood. */
public final class GraphChangeNeighborhoodResult {
    public enum Status { QUERIED, SKIPPED_DELETE, SKIPPED_NO_ANCHOR }

    private final GraphChangeNotice notice;
    private final Status status;
    private final List<GraphNeighborhoodSnapshot> snapshots;

    GraphChangeNeighborhoodResult(GraphChangeNotice notice, Status status,
                                  List<GraphNeighborhoodSnapshot> snapshots) {
        this.notice = Objects.requireNonNull(notice, "notice");
        this.status = Objects.requireNonNull(status, "status");
        this.snapshots = List.copyOf(Objects.requireNonNull(snapshots, "snapshots"));
    }

    public GraphChangeNotice getNotice() { return notice; }
    public Status getStatus() { return status; }
    public List<GraphNeighborhoodSnapshot> getSnapshots() { return snapshots; }
}
