package org.atmkg.service.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.atmkg.core.model.QuerySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QueryTemplateRegistryTest {
    @TempDir
    Path tempDir;

    @Test
    void resolvesNeighborsWithInvocationStartUidAndConfiguredFilters() throws IOException {
        QueryTemplateRegistry registry = load("""
                templates:
                  direct:
                    type: NEIGHBORS
                    direction: INCOMING
                    relationshipTypes: [urn:test:departs, urn:test:arrives]
                    classFilters: [urn:test:Flight]
                """);

        QuerySpec resolved = registry.resolve("direct", "airport-uid");

        assertEquals(QuerySpec.Type.NEIGHBORS, resolved.getType());
        assertEquals("airport-uid", resolved.getStartUid());
        assertEquals(QuerySpec.Direction.INCOMING, resolved.getDirection());
        assertEquals(Set.of("urn:test:departs", "urn:test:arrives"), resolved.getRelationshipTypes());
        assertEquals(Set.of("urn:test:Flight"), resolved.getClassFilters());
        assertEquals(null, resolved.getDepth());
        assertEquals(null, resolved.getQueryId());
        assertTrue(resolved.getParameters().isEmpty());
    }

    @Test
    void resolvesKHopWithConfiguredDepth() throws IOException {
        QueryTemplateRegistry registry = load("""
                templates:
                  nearby:
                    type: K_HOP
                    depth: 2
                    direction: BOTH
                    relationshipTypes: []
                    classFilters: []
                """);

        QuerySpec resolved = registry.resolve("nearby", "route-uid");

        assertEquals(QuerySpec.Type.K_HOP, resolved.getType());
        assertEquals(2, resolved.getDepth());
        assertEquals("route-uid", resolved.getStartUid());
    }

    @Test
    void rejectsDuplicateQueryIds() {
        assertInvalid("""
                templates:
                  duplicate:
                    type: NEIGHBORS
                  duplicate:
                    type: K_HOP
                    depth: 2
                """, "重复");
        assertInvalid("""
                templates:
                  "duplicate":
                    type: NEIGHBORS
                  " duplicate ":
                    type: NEIGHBORS
                """, "重复");
    }

    @Test
    void rejectsUnknownAndUnsupportedTypes() {
        assertInvalid("""
                templates:
                  unknown:
                    type: SOMETHING_ELSE
                """, "type");
        assertInvalid("""
                templates:
                  path-query:
                    type: PATH
                """, "NEIGHBORS");
    }

    @Test
    void rejectsInvalidKHopDepth() {
        assertInvalid("""
                templates:
                  missing-depth:
                    type: K_HOP
                """, "depth");
        assertInvalid("""
                templates:
                  zero-depth:
                    type: K_HOP
                    depth: 0
                """, "depth");
    }

    @Test
    void rejectsInvalidDirectionAndBlankFilterItems() {
        assertInvalid("""
                templates:
                  invalid-direction:
                    type: NEIGHBORS
                    direction: SIDEWAYS
                """, "direction");
        assertInvalid("""
                templates:
                  blank-filter:
                    type: NEIGHBORS
                    relationshipTypes: [""]
                """, "relationshipTypes");
        assertInvalid("""
                templates:
                  null-filter:
                    type: NEIGHBORS
                    classFilters: [null]
                """, "classFilters");
    }

    @Test
    void rejectsUnknownFieldsIncludingRawCypher() {
        assertInvalid("""
                templates:
                  arbitrary:
                    type: NEIGHBORS
                    cypher: MATCH (n) RETURN n
                """, "cypher");
        assertInvalid("""
                templates:
                  arbitrary:
                    type: NEIGHBORS
                    rawCypher: MATCH (n) RETURN n
                """, "rawCypher");
        assertInvalid("""
                templates:
                  fixed-start:
                    type: NEIGHBORS
                    startUid: forbidden
                """, "startUid");
    }

    @Test
    void rejectsUnknownRootFields() {
        assertInvalid("""
                templates: {}
                version: 1
                """, "version");
    }

    @Test
    void officialAirportTemplateContainsOnlyEstablishedDirectFacts() {
        QueryTemplateRegistry registry = QueryTemplateRegistry.load(
                Path.of("queries/query-templates.yaml"));

        QuerySpec resolved = registry.resolve("airport-direct-flights", "airport-uid");

        assertEquals(QuerySpec.Type.NEIGHBORS, resolved.getType());
        assertEquals(QuerySpec.Direction.INCOMING, resolved.getDirection());
        assertEquals(Set.of(
                "urn:atm-knowledge-graph:departsFrom",
                "urn:atm-knowledge-graph:arrivesAt"), resolved.getRelationshipTypes());
        assertEquals(Set.of("urn:atm-knowledge-graph:Flight"), resolved.getClassFilters());
    }

    @Test
    void officialRouteAssociationTemplatesContainOnlyEstablishedDirectFacts() {
        QueryTemplateRegistry registry = QueryTemplateRegistry.load(
                Path.of("queries/query-templates.yaml"));

        QuerySpec routeStructures = registry.resolve("segment-route-structures", "segment-uid");
        assertEquals(QuerySpec.Type.NEIGHBORS, routeStructures.getType());
        assertEquals(QuerySpec.Direction.INCOMING, routeStructures.getDirection());
        assertEquals(Set.of("urn:atm-knowledge-graph:hasSegment"),
                routeStructures.getRelationshipTypes());
        assertEquals(Set.of(
                "urn:atm-knowledge-graph:Route",
                "urn:atm-knowledge-graph:ScheduledFlightRoute",
                "urn:atm-knowledge-graph:PlannedFlightRoute"), routeStructures.getClassFilters());

        QuerySpec flights = registry.resolve("planned-route-flights", "planned-route-uid");
        assertEquals(QuerySpec.Type.NEIGHBORS, flights.getType());
        assertEquals(QuerySpec.Direction.INCOMING, flights.getDirection());
        assertEquals(Set.of("urn:atm-knowledge-graph:hasPlannedRoute"), flights.getRelationshipTypes());
        assertEquals(Set.of("urn:atm-knowledge-graph:Flight"), flights.getClassFilters());
    }

    private QueryTemplateRegistry load(String yaml) throws IOException {
        Path file = tempDir.resolve("query-templates.yaml");
        Files.writeString(file, yaml);
        return QueryTemplateRegistry.load(file);
    }

    private void assertInvalid(String yaml, String messageFragment) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> load(yaml));
        assertTrue(failure.getMessage().contains(messageFragment), failure.getMessage());
    }
}
