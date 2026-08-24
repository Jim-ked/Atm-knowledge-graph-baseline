package org.atmkg.service.change;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.atmkg.core.model.GraphDTO;
import org.atmkg.core.model.GraphEntity;
import org.atmkg.core.model.GraphNodeDTO;
import org.atmkg.core.model.GraphProjectionSnapshot;
import org.atmkg.core.model.MappingResult;
import org.atmkg.core.model.QuerySpec;
import org.atmkg.core.model.SourceRef;
import org.atmkg.core.spi.QueryService;
import org.atmkg.service.sync.GraphChangeNotice;
import org.junit.jupiter.api.Test;

class GraphChangeProcessorTest {
    private static final SourceRef REF = new SourceRef("jdbc-main", "airport-base", "ZBAA");
    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");

    @Test
    void upsertRunsBothProjectorsAndPublishesOneUnifiedResultWithTheOriginalNotice() {
        RecordingQuery query = new RecordingQuery();
        List<GraphChangeProjectionResult> published = new ArrayList<>();
        GraphChangeProcessor processor = processor(query, published::add);
        GraphChangeNotice notice = GraphChangeNotice.forUpsert(REF, new MappingResult(
                List.of(new GraphEntity("U1", "urn:test:Airport", "ZBAA", Map.of(), Map.of())),
                List.of()), NOW);

        processor.accept(notice);

        assertEquals(List.of(QuerySpec.Type.NEIGHBORS, QuerySpec.Type.ENTITY, QuerySpec.Type.NAMED), query.calls);
        assertEquals(1, published.size());
        GraphChangeProjectionResult result = published.get(0);
        assertSame(notice, result.getNotice());
        assertEquals(GraphChangeNeighborhoodResult.Status.QUERIED, result.getNeighborhood().getStatus());
        assertEquals(1, result.getNeighborhood().getSnapshots().size());
        assertEquals(1, result.getAssociations().size());
        assertEquals("airport-direct-flights", result.getAssociations().get(0).getQueryId());
    }

    @Test
    void deleteKeepsBeforeStateWhileExistingProjectorsRemainSkipped() {
        QueryService noQueries = spec -> { throw new AssertionError("DELETE 不应查询当前图"); };
        List<GraphChangeProjectionResult> published = new ArrayList<>();
        GraphChangeProcessor processor = processor(noQueries, published::add);
        GraphChangeNotice notice = GraphChangeNotice.forDelete(REF,
                new GraphProjectionSnapshot(List.of("U1"), List.of("R1"), List.of("U1", "U2")), NOW);

        processor.accept(notice);

        GraphChangeProjectionResult result = published.get(0);
        assertSame(notice, result.getNotice());
        assertEquals(List.of("U1"), result.getNotice().getEntityUids());
        assertEquals(List.of("R1"), result.getNotice().getRelationshipUids());
        assertEquals(List.of("U1", "U2"), result.getNotice().getAnchorEntityUids());
        assertEquals(GraphChangeNeighborhoodResult.Status.SKIPPED_DELETE,
                result.getNeighborhood().getStatus());
        assertTrue(result.getAssociations().isEmpty());
    }

    @Test
    void projectorFailurePropagatesAndDoesNotPublishPartialResult() {
        QueryService associationFailure = spec -> {
            if (spec.getType() == QuerySpec.Type.ENTITY) throw new IllegalStateException("association failed");
            return graph();
        };
        List<GraphChangeProjectionResult> published = new ArrayList<>();
        GraphChangeProcessor processor = processor(associationFailure, published::add);
        GraphChangeNotice notice = GraphChangeNotice.forUpsert(REF, new MappingResult(
                List.of(new GraphEntity("U1", "urn:test:Airport", "ZBAA", Map.of(), Map.of())),
                List.of()), NOW);

        assertThrows(IllegalStateException.class, () -> processor.accept(notice));

        assertTrue(published.isEmpty());
    }

    @Test
    void resultConsumerFailurePropagates() {
        GraphChangeProcessor processor = processor(new RecordingQuery(), result -> {
            throw new IllegalStateException("sink failed");
        });
        GraphChangeNotice notice = GraphChangeNotice.forUpsert(REF, new MappingResult(
                List.of(new GraphEntity("U1", "urn:test:Airport", "ZBAA", Map.of(), Map.of())),
                List.of()), NOW);

        assertThrows(IllegalStateException.class, () -> processor.accept(notice));
    }

    private GraphChangeProcessor processor(QueryService query,
                                           java.util.function.Consumer<GraphChangeProjectionResult> sink) {
        return new GraphChangeProcessor(
                new GraphChangeNeighborhoodProjector(query),
                new GraphChangeAssociationProjector(query,
                        ChangeQueryRuleRegistry.load(Path.of("queries/change-query-rules.yaml"))),
                sink);
    }

    private static GraphDTO graph(GraphNodeDTO... nodes) {
        return new GraphDTO("1", List.of(nodes), List.of(), Map.of());
    }

    private static final class RecordingQuery implements QueryService {
        private final List<QuerySpec.Type> calls = new ArrayList<>();

        @Override
        public GraphDTO query(QuerySpec spec) {
            calls.add(spec.getType());
            if (spec.getType() == QuerySpec.Type.ENTITY) {
                return graph(new GraphNodeDTO(spec.getStartUid(), List.of("Airport"),
                        "Airport", "ZBAA", Map.of()));
            }
            return graph(new GraphNodeDTO(spec.getStartUid(), List.of("Airport"),
                    "Airport", "ZBAA", Map.of()));
        }
    }
}
