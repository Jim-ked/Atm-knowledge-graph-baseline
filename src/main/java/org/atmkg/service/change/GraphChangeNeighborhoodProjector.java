package org.atmkg.service.change;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.atmkg.core.model.QuerySpec;
import org.atmkg.core.spi.QueryService;
import org.atmkg.service.sync.GraphChangeNotice;

/** Queries an independent complete current one-hop snapshot for every explicit UPSERT anchor. */
public final class GraphChangeNeighborhoodProjector implements Consumer<GraphChangeNotice> {
    private final QueryService queryService;
    private final Consumer<GraphChangeNeighborhoodResult> resultConsumer;

    public GraphChangeNeighborhoodProjector(QueryService queryService) {
        this(queryService, result -> {});
    }

    public GraphChangeNeighborhoodProjector(QueryService queryService,
                                            Consumer<GraphChangeNeighborhoodResult> resultConsumer) {
        this.queryService = Objects.requireNonNull(queryService, "queryService");
        this.resultConsumer = Objects.requireNonNull(resultConsumer, "resultConsumer");
    }

    @Override
    public void accept(GraphChangeNotice notice) {
        resultConsumer.accept(project(notice));
    }

    public GraphChangeNeighborhoodResult project(GraphChangeNotice notice) {
        Objects.requireNonNull(notice, "notice");
        if (notice.getOperation() == GraphChangeNotice.Operation.DELETE) {
            return new GraphChangeNeighborhoodResult(notice,
                    GraphChangeNeighborhoodResult.Status.SKIPPED_DELETE, List.of());
        }
        if (notice.getAnchorEntityUids().isEmpty()) {
            return new GraphChangeNeighborhoodResult(notice,
                    GraphChangeNeighborhoodResult.Status.SKIPPED_NO_ANCHOR, List.of());
        }

        List<GraphNeighborhoodSnapshot> snapshots = new ArrayList<>();
        for (String anchorUid : notice.getAnchorEntityUids()) {
            QuerySpec spec = new QuerySpec(QuerySpec.Type.NEIGHBORS, anchorUid, null, 1,
                    Set.of(), Set.of(), QuerySpec.Direction.BOTH, null, Map.of());
            snapshots.add(new GraphNeighborhoodSnapshot(anchorUid, queryService.query(spec)));
        }
        return new GraphChangeNeighborhoodResult(
                notice, GraphChangeNeighborhoodResult.Status.QUERIED, snapshots);
    }
}
