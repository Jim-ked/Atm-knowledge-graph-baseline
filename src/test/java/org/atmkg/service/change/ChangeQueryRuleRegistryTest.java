package org.atmkg.service.change;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChangeQueryRuleRegistryTest {
    @TempDir
    Path tempDir;

    @Test
    void officialRulesMapOnlyGraphKindsToNamedQueries() {
        ChangeQueryRuleRegistry rules = ChangeQueryRuleRegistry.load(
                Path.of("queries/change-query-rules.yaml"));

        assertEquals(List.of("airport-direct-flights"), rules.queryIdsFor("Airport"));
        assertEquals(List.of("segment-route-structures"), rules.queryIdsFor("RouteSegment"));
        assertEquals(List.of("planned-route-flights"), rules.queryIdsFor("PlannedFlightRoute"));
        assertTrue(rules.queryIdsFor("Route").isEmpty());
        assertTrue(rules.queryIdsFor("ScheduledFlightRoute").isEmpty());
    }

    @Test
    void invalidRuleShapesFailAtLoadBoundary() throws IOException {
        assertInvalid("""
                associations:
                  RouteSegment: [segment-route-structures]
                condition: arbitrary
                """, "condition");
        assertInvalid("""
                associations:
                  RouteSegment: [""]
                """, "RouteSegment");
        assertInvalid("""
                associations:
                  "RouteSegment": [first]
                  " RouteSegment ": [second]
                """, "重复");
    }

    private void assertInvalid(String yaml, String messageFragment) throws IOException {
        Path file = tempDir.resolve("change-query-rules.yaml");
        Files.writeString(file, yaml);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> ChangeQueryRuleRegistry.load(file));
        assertTrue(failure.getMessage().contains(messageFragment), failure.getMessage());
    }
}
