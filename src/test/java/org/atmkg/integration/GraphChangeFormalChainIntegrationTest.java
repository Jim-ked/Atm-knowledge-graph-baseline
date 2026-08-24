package org.atmkg.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.atmkg.core.model.ChangeEvent;
import org.atmkg.core.model.GraphDTO;
import org.atmkg.core.model.GraphEntity;
import org.atmkg.core.model.GraphNodeDTO;
import org.atmkg.core.model.MappingResult;
import org.atmkg.core.model.QuerySpec;
import org.atmkg.core.model.SourceRecord;
import org.atmkg.core.spi.QueryService;
import org.atmkg.core.spi.SourceAdapter;
import org.atmkg.service.change.ChangeQueryRuleRegistry;
import org.atmkg.service.change.GraphChangeAssociationProjector;
import org.atmkg.service.change.GraphChangeNeighborhoodProjector;
import org.atmkg.service.change.GraphChangeProcessor;
import org.atmkg.service.change.GraphChangeProjectionResult;
import org.atmkg.service.sync.DefaultSyncService;
import org.atmkg.testsupport.InMemoryGraphStore;
import org.junit.jupiter.api.Test;

class GraphChangeFormalChainIntegrationTest {
    @Test
    void successfulUpsertFlowsFromSyncServiceThroughBothProjectorsToResultSink() {
        SourceRecord record = new SourceRecord("fixture", "airport-base", "ZBAA",
                Map.of("name", "北京首都"), Instant.parse("2026-08-24T12:00:00Z"));
        SourceAdapter source = new SourceAdapter() {
            @Override public Iterable<SourceRecord> readAll(String objectName) { return List.of(record); }
            @Override public Optional<SourceRecord> readByKey(String objectName, String sourceKey) {
                return Optional.of(record);
            }
            @Override public Iterable<SourceRecord> scanChangedSince(String objectName, Instant since) {
                return List.of(record);
            }
        };
        QueryService query = spec -> {
            if (spec.getType() == QuerySpec.Type.ENTITY) {
                return graph(new GraphNodeDTO(spec.getStartUid(), List.of("Airport"),
                        "Airport", "北京首都", Map.of()));
            }
            return graph(new GraphNodeDTO(spec.getStartUid(), List.of("Airport"),
                    "Airport", "北京首都", Map.of()));
        };
        List<GraphChangeProjectionResult> results = new ArrayList<>();
        GraphChangeProcessor processor = new GraphChangeProcessor(
                new GraphChangeNeighborhoodProjector(query),
                new GraphChangeAssociationProjector(query,
                        ChangeQueryRuleRegistry.load(Path.of("queries/change-query-rules.yaml"))),
                results::add);
        DefaultSyncService sync = new DefaultSyncService(Map.of("fixture", source), value ->
                new MappingResult(List.of(new GraphEntity("U1", "urn:test:Airport", "北京首都",
                        Map.of(), Map.of("sourceId", "fixture", "sourceObject", "airport-base",
                                "sourceKey", "ZBAA"))), List.of()),
                new InMemoryGraphStore(), processor);

        sync.handle(new ChangeEvent("E1", "fixture", "airport-base", "ZBAA",
                ChangeEvent.Operation.UPSERT, Instant.parse("2026-08-24T12:00:00Z")));

        assertEquals(1, results.size());
        assertEquals(List.of("U1"), results.get(0).getNotice().getEntityUids());
        assertSame(results.get(0).getNotice(), results.get(0).getNeighborhood().getNotice());
        assertEquals(1, results.get(0).getNeighborhood().getSnapshots().size());
        assertEquals(1, results.get(0).getAssociations().size());
    }

    private static GraphDTO graph(GraphNodeDTO... nodes) {
        return new GraphDTO("1", List.of(nodes), List.of(), Map.of());
    }
}
