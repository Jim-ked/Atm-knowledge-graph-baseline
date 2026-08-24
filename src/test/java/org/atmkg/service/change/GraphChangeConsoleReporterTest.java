package org.atmkg.service.change;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.atmkg.core.model.GraphDTO;
import org.atmkg.core.model.GraphEntity;
import org.atmkg.core.model.GraphNodeDTO;
import org.atmkg.core.model.GraphRelationshipDTO;
import org.atmkg.core.model.MappingResult;
import org.atmkg.core.model.SourceRef;
import org.atmkg.service.sync.GraphChangeNotice;
import org.junit.jupiter.api.Test;

class GraphChangeConsoleReporterTest {
    @Test
    void printsOnlyACompactSummaryWithoutGraphContentOrProperties() {
        GraphChangeNotice notice = GraphChangeNotice.forUpsert(
                new SourceRef("jdbc-main", "airport-base", "ZBAA"),
                new MappingResult(List.of(new GraphEntity(
                        "U1", "urn:test:Airport", "SECRET_CAPTION", Map.of("secret", "SECRET_VALUE"), Map.of())),
                        List.of()), Instant.parse("2026-08-24T12:00:00Z"));
        GraphDTO graph = new GraphDTO("1",
                List.of(new GraphNodeDTO("U1", List.of("Airport"), "Airport",
                        "SECRET_CAPTION", Map.of("secret", "SECRET_VALUE"))),
                List.of(new GraphRelationshipDTO("R1", "U1", "U2", "related",
                        Map.of("secret", "SECRET_VALUE"))), Map.of("secret", "SECRET_VALUE"));
        GraphChangeProjectionResult result = new GraphChangeProjectionResult(notice,
                new GraphChangeNeighborhoodResult(notice, GraphChangeNeighborhoodResult.Status.QUERIED,
                        List.of(new GraphNeighborhoodSnapshot("U1", graph))),
                List.of(new AssociationQueryResult("U1", "Airport", "airport-direct-flights", graph)));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        new GraphChangeConsoleReporter(new PrintStream(bytes, true, StandardCharsets.UTF_8)).accept(result);

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.startsWith("[CHANGE] UPSERT source=jdbc-main/airport-base/ZBAA"));
        assertTrue(output.contains("entities=1 relationships=0 anchors=1"));
        assertTrue(output.contains("neighborhood=QUERIED neighborhoods=1 associations=1"));
        assertFalse(output.contains("SECRET_CAPTION"));
        assertFalse(output.contains("SECRET_VALUE"));
        assertFalse(output.contains("GraphDTO"));
    }
}
