package org.atmkg.service.change;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.atmkg.core.model.GraphDTO;
import org.atmkg.core.model.GraphNodeDTO;
import org.atmkg.core.model.QuerySpec;
import org.atmkg.core.spi.QueryService;
import org.atmkg.service.sync.GraphChangeNotice;

/**
 * 新增 kind→query 选择只改 {@code queries/change-query-rules.yaml}，新增 query 去
 * {@code queries/query-templates.yaml}；不要在本类加入 RouteSegment/PlannedFlightRoute if。
 *
 * <p>只有“anchor ENTITY 查询→规则查询→最多一次 continuation”的通用流程有缺陷才写 Java。去掉一次上限
 * 会把它变成未设计的递归推理器；为 DELETE 猜历史会伪造影响对象。独立测试成功但 service 无输出是因为
 * 本类尚未装配进 KgServiceMain/SyncRuntime，也没有 outward sink。
 */
public final class GraphChangeAssociationProjector {
    private final QueryService queryService;
    private final ChangeQueryRuleRegistry rules;

    public GraphChangeAssociationProjector(QueryService queryService, ChangeQueryRuleRegistry rules) {
        this.queryService = Objects.requireNonNull(queryService, "queryService");
        this.rules = Objects.requireNonNull(rules, "rules");
    }

    public List<AssociationQueryResult> project(GraphChangeNotice notice) {
        Objects.requireNonNull(notice, "notice");
        if (notice.getOperation() == GraphChangeNotice.Operation.DELETE) return List.of();

        List<AssociationQueryResult> projected = new ArrayList<>();
        for (String anchorUid : notice.getAnchorEntityUids()) {
            GraphNodeDTO anchor = findCurrentAnchor(anchorUid);
            if (anchor == null) continue;

            List<AssociationQueryResult> firstLevel = queryAssociations(anchor);
            projected.addAll(firstLevel);
            for (AssociationQueryResult first : firstLevel) {
                for (GraphNodeDTO node : first.getGraph().getNodes()) {
                    if (!anchorUid.equals(node.getId())) projected.addAll(queryAssociations(node));
                }
            }
        }
        return List.copyOf(projected);
    }

    private GraphNodeDTO findCurrentAnchor(String anchorUid) {
        QuerySpec entity = new QuerySpec(QuerySpec.Type.ENTITY, anchorUid, null, null,
                Set.of(), Set.of(), QuerySpec.Direction.BOTH, null, Map.of());
        GraphDTO current = queryService.query(entity);
        return current.getNodes().stream()
                .filter(node -> anchorUid.equals(node.getId()))
                .findFirst()
                .orElse(null);
    }

    private List<AssociationQueryResult> queryAssociations(GraphNodeDTO anchor) {
        List<AssociationQueryResult> results = new ArrayList<>();
        for (String queryId : rules.queryIdsFor(anchor.getKind())) {
            QuerySpec named = new QuerySpec(QuerySpec.Type.NAMED, anchor.getId(), null, null,
                    Set.of(), Set.of(), QuerySpec.Direction.BOTH, queryId, Map.of());
            GraphDTO graph = queryService.query(named);
            results.add(new AssociationQueryResult(
                    anchor.getId(), anchor.getKind(), queryId, graph));
        }
        return results;
    }
}
