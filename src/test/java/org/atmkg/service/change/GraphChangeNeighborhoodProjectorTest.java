package org.atmkg.service.change;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.atmkg.core.model.GraphDTO;
import org.atmkg.core.model.GraphEntity;
import org.atmkg.core.model.GraphRelationship;
import org.atmkg.core.model.GraphProjectionSnapshot;
import org.atmkg.core.model.MappingResult;
import org.atmkg.core.model.QuerySpec;
import org.atmkg.core.model.SourceRef;
import org.atmkg.core.spi.QueryService;
import org.atmkg.service.sync.GraphChangeNotice;
import org.junit.jupiter.api.Test;

class GraphChangeNeighborhoodProjectorTest {
    private static final SourceRef REF = new SourceRef("jdbc-main", "route-segment", "R1|1");
    private static final Instant NOW = Instant.parse("2026-08-23T01:00:00Z");

    @Test
    void anchorsComeOnlyFromMappedEntitiesAndRelationshipEndpointsInStableDistinctOrder() {
        MappingResult mapped = new MappingResult(
                List.of(new GraphEntity("U1", "urn:test:Entity", "one", Map.of(), Map.of())),
                List.of(
                        new GraphRelationship("R1", "urn:test:related", "U1", "U2", Map.of(), Map.of()),
                        new GraphRelationship("R2", "urn:test:related", "U2", "U1", Map.of(), Map.of())));

        GraphChangeNotice notice = GraphChangeNotice.forUpsert(REF, mapped, NOW);

        assertEquals(List.of("U1"), notice.getEntityUids());
        assertEquals(List.of("R1", "R2"), notice.getRelationshipUids());
        assertEquals(List.of("U1", "U2"), notice.getAnchorEntityUids());
    }

    @Test
    void queriesOneCompleteCurrentNeighborhoodPerAnchorAndKeepsSnapshotsSeparate() {
        List<QuerySpec> specs = new ArrayList<>();
        List<GraphDTO> graphs = new ArrayList<>();
        QueryService query = spec -> {
            specs.add(spec);
            GraphDTO graph = new GraphDTO("1", List.of(), List.of(), Map.of("anchor", spec.getStartUid()));
            graphs.add(graph);
            return graph;
        };
        GraphChangeNotice notice = GraphChangeNotice.forUpsert(REF, new MappingResult(
                List.of(new GraphEntity("U1", "urn:test:Entity", "one", Map.of(), Map.of())),
                List.of(new GraphRelationship("R1", "urn:test:related", "U2", "U3", Map.of(), Map.of()))), NOW);

        GraphChangeNeighborhoodResult result = new GraphChangeNeighborhoodProjector(query).project(notice);

        assertEquals(GraphChangeNeighborhoodResult.Status.QUERIED, result.getStatus());
        assertEquals(List.of("U1", "U2", "U3"),
                result.getSnapshots().stream().map(GraphNeighborhoodSnapshot::getAnchorUid).toList());
        assertEquals(3, specs.size());
        for (int index = 0; index < specs.size(); index++) {
            QuerySpec spec = specs.get(index);
            assertEquals(QuerySpec.Type.NEIGHBORS, spec.getType());
            assertEquals(result.getSnapshots().get(index).getAnchorUid(), spec.getStartUid());
            assertEquals(1, spec.getDepth());
            assertEquals(QuerySpec.Direction.BOTH, spec.getDirection());
            assertEquals(Set.of(), spec.getRelationshipTypes());
            assertEquals(Set.of(), spec.getClassFilters());
            assertSame(graphs.get(index), result.getSnapshots().get(index).getCurrentGraph());
        }
    }

    @Test
    void queryFailurePublishesNoSyntheticSuccessResult() {
        List<GraphChangeNeighborhoodResult> published = new ArrayList<>();
        GraphChangeNeighborhoodProjector projector = new GraphChangeNeighborhoodProjector(
                spec -> { throw new IllegalStateException("query failed"); }, published::add);
        GraphChangeNotice notice = GraphChangeNotice.forUpsert(REF, new MappingResult(
                List.of(new GraphEntity("U1", "urn:test:Entity", "one", Map.of(), Map.of())), List.of()), NOW);

        assertThrows(IllegalStateException.class, () -> projector.accept(notice));

        assertTrue(published.isEmpty());
    }

    @Test
    void deleteSkipsCurrentNeighborhoodWithoutGuessingHistoricalAnchors() {
        CountingQuery query = new CountingQuery();
        GraphChangeNeighborhoodResult result = new GraphChangeNeighborhoodProjector(query)
                .project(GraphChangeNotice.forDelete(REF,
                        new GraphProjectionSnapshot(List.of("U1"), List.of("R1"), List.of("U1", "U2")), NOW));

        assertEquals(GraphChangeNeighborhoodResult.Status.SKIPPED_DELETE, result.getStatus());
        assertTrue(result.getSnapshots().isEmpty());
        assertEquals(0, query.calls);
    }

    @Test
    void emptyUpsertAnchorsAreReportedAsSkippedWithoutGuessing() {
        CountingQuery query = new CountingQuery();
        GraphChangeNotice notice = GraphChangeNotice.forUpsert(
                REF, new MappingResult(List.of(), List.of()), NOW);

        GraphChangeNeighborhoodResult result = new GraphChangeNeighborhoodProjector(query).project(notice);

        assertEquals(GraphChangeNeighborhoodResult.Status.SKIPPED_NO_ANCHOR, result.getStatus());
        assertTrue(result.getSnapshots().isEmpty());
        assertEquals(0, query.calls);
    }

    private static final class CountingQuery implements QueryService {
        int calls;
        @Override public GraphDTO query(QuerySpec spec) {
            calls++;
            return new GraphDTO("1", List.of(), List.of(), Map.of());
        }
    }
}
