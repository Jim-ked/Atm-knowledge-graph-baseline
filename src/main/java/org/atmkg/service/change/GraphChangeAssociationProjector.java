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

/** Applies configured named association queries, with at most one continuation from first-level results. */
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
