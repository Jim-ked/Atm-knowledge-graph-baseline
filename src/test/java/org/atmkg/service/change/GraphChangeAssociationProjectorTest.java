package org.atmkg.service.change;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.atmkg.core.model.GraphDTO;
import org.atmkg.core.model.GraphNodeDTO;
import org.atmkg.core.model.QuerySpec;
import org.atmkg.core.model.SourceRef;
import org.atmkg.core.spi.QueryService;
import org.atmkg.service.query.QueryTemplateRegistry;
import org.atmkg.service.query.TemplateAwareQueryService;
import org.atmkg.service.sync.GraphChangeNotice;
import org.junit.jupiter.api.Test;

class GraphChangeAssociationProjectorTest {
    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");

    @Test
    void routeAndScheduledRouteStopNaturallyBecauseTheirKindsHaveNoRule() {
        for (String routeKind : List.of("Route", "ScheduledFlightRoute")) {
            ScenarioQuery delegate = new ScenarioQuery();
            delegate.entity(node("segment", "RouteSegment"));
            GraphDTO association = graph(node("segment", "RouteSegment"), node("route", routeKind));
            delegate.association("segment", association);

            List<AssociationQueryResult> results = projector(delegate).project(upsert("segment", "any-source"));

            assertEquals(1, results.size());
            assertResult(results.get(0), "segment", "RouteSegment", "segment-route-structures", association);
            assertEquals(2, delegate.calls.size());
        }
    }

    @Test
    void plannedRouteContinuesOnceFromSegmentToFlight() {
        ScenarioQuery delegate = new ScenarioQuery();
        delegate.entity(node("segment", "RouteSegment"));
        GraphDTO routeStructures = graph(
                node("segment", "RouteSegment"), node("planned", "PlannedFlightRoute"));
        GraphDTO flights = graph(node("planned", "PlannedFlightRoute"), node("flight", "Flight"));
        delegate.association("segment", routeStructures);
        delegate.association("planned", flights);

        List<AssociationQueryResult> results = projector(delegate).project(upsert("segment", "segment-source"));

        assertEquals(2, results.size());
        assertResult(results.get(0), "segment", "RouteSegment", "segment-route-structures", routeStructures);
        assertResult(results.get(1), "planned", "PlannedFlightRoute", "planned-route-flights", flights);
        assertEquals(3, delegate.calls.size());
        assertEquals(List.of(QuerySpec.Type.ENTITY, QuerySpec.Type.NEIGHBORS, QuerySpec.Type.NEIGHBORS),
                delegate.calls.stream().map(QuerySpec::getType).toList());
    }

    @Test
    void airportSelectionUsesGraphNodeKindAndIgnoresSourceIdentity() {
        ScenarioQuery delegate = new ScenarioQuery();
        delegate.entity(node("airport", "Airport"));
        GraphDTO flights = graph(node("airport", "Airport"), node("flight", "Flight"));
        delegate.association("airport", flights);

        List<AssociationQueryResult> results = projector(delegate)
                .project(upsert("airport", "source-that-does-not-name-airports"));

        assertEquals(1, results.size());
        assertResult(results.get(0), "airport", "Airport", "airport-direct-flights", flights);
        assertEquals(Set.of(
                "urn:atm-knowledge-graph:departsFrom",
                "urn:atm-knowledge-graph:arrivesAt"), delegate.calls.get(1).getRelationshipTypes());
    }

    @Test
    void deleteAndMissingAnchorAreSkippedWithoutAssociationQueries() {
        ScenarioQuery deleteDelegate = new ScenarioQuery();
        List<AssociationQueryResult> deleted = projector(deleteDelegate).project(
                GraphChangeNotice.forDelete(new SourceRef("source", "object", "key"), NOW));
        assertTrue(deleted.isEmpty());
        assertTrue(deleteDelegate.calls.isEmpty());

        ScenarioQuery missingDelegate = new ScenarioQuery();
        List<AssociationQueryResult> missing = projector(missingDelegate).project(upsert("missing", "source"));
        assertTrue(missing.isEmpty());
        assertEquals(1, missingDelegate.calls.size());
        assertEquals(QuerySpec.Type.ENTITY, missingDelegate.calls.get(0).getType());
    }

    @Test
    void queryFailureDoesNotProduceAResult() {
        ScenarioQuery delegate = new ScenarioQuery();
        delegate.entity(node("segment", "RouteSegment"));
        delegate.failAssociation = true;

        assertThrows(IllegalStateException.class,
                () -> projector(delegate).project(upsert("segment", "source")));
        assertEquals(2, delegate.calls.size());
    }

    private GraphChangeAssociationProjector projector(ScenarioQuery delegate) {
        QueryService queryService = new TemplateAwareQueryService(delegate,
                QueryTemplateRegistry.load(Path.of("queries/query-templates.yaml")));
        return new GraphChangeAssociationProjector(queryService,
                ChangeQueryRuleRegistry.load(Path.of("queries/change-query-rules.yaml")));
    }

    private GraphChangeNotice upsert(String uid, String sourceId) {
        return new GraphChangeNotice(new SourceRef(sourceId, "unrelated-object", "unrelated-key"),
                GraphChangeNotice.Operation.UPSERT, List.of(uid), List.of(), NOW);
    }

    private void assertResult(AssociationQueryResult actual, String anchorUid, String anchorKind,
                              String queryId, GraphDTO expectedGraph) {
        assertEquals(anchorUid, actual.getAnchorUid());
        assertEquals(anchorKind, actual.getAnchorKind());
        assertEquals(queryId, actual.getQueryId());
        assertSame(expectedGraph, actual.getGraph());
    }

    private static GraphNodeDTO node(String uid, String kind) {
        return new GraphNodeDTO(uid, List.of(kind), kind, uid, Map.of());
    }

    private static GraphDTO graph(GraphNodeDTO... nodes) {
        return new GraphDTO("1", List.of(nodes), List.of(), Map.of());
    }

    private static final class ScenarioQuery implements QueryService {
        private final Map<String, GraphNodeDTO> entities = new LinkedHashMap<>();
        private final Map<String, GraphDTO> associations = new LinkedHashMap<>();
        private final List<QuerySpec> calls = new ArrayList<>();
        private boolean failAssociation;

        private void entity(GraphNodeDTO node) {
            entities.put(node.getId(), node);
        }

        private void association(String uid, GraphDTO result) {
            associations.put(uid, result);
        }

        @Override
        public GraphDTO query(QuerySpec spec) {
            calls.add(spec);
            if (spec.getType() == QuerySpec.Type.ENTITY) {
                GraphNodeDTO node = entities.get(spec.getStartUid());
                return node == null ? graph() : graph(node);
            }
            if (spec.getType() == QuerySpec.Type.NEIGHBORS) {
                if (failAssociation) throw new IllegalStateException("association query failed");
                return associations.getOrDefault(spec.getStartUid(), graph());
            }
            throw new AssertionError("unexpected query type: " + spec.getType());
        }
    }
}
