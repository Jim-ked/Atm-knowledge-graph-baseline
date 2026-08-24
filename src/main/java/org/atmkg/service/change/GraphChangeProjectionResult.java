package org.atmkg.service.change;

import java.util.List;
import java.util.Objects;
import org.atmkg.service.sync.GraphChangeNotice;

/** 一次 GraphChange 处理的统一结果；保留各投影原始边界，不合并 GraphDTO 或增加业务判断。 */
public final class GraphChangeProjectionResult {
    private final GraphChangeNotice notice;
    private final GraphChangeNeighborhoodResult neighborhood;
    private final List<AssociationQueryResult> associations;

    public GraphChangeProjectionResult(GraphChangeNotice notice,
                                       GraphChangeNeighborhoodResult neighborhood,
                                       List<AssociationQueryResult> associations) {
        this.notice = Objects.requireNonNull(notice, "notice");
        this.neighborhood = Objects.requireNonNull(neighborhood, "neighborhood");
        this.associations = List.copyOf(Objects.requireNonNull(associations, "associations"));
    }

    public GraphChangeNotice getNotice() { return notice; }
    public GraphChangeNeighborhoodResult getNeighborhood() { return neighborhood; }
    public List<AssociationQueryResult> getAssociations() { return associations; }
}
