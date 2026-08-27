package org.atmkg.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.atmkg.core.model.ChangeEvent;
import org.atmkg.core.model.GraphEntity;
import org.atmkg.core.model.GraphRelationship;
import org.atmkg.core.model.SourceScope;
import org.atmkg.core.model.mapping.EntityMappingSpec;
import org.atmkg.fixture.CsvFixtureSourceAdapter;
import org.atmkg.fixture.FixtureChangeEventReader;
import org.atmkg.infra.identity.DeterministicIdentityResolver;
import org.atmkg.infra.mapping.DefaultMappingEngine;
import org.atmkg.infra.mapping.PoiMappingRegistry;
import org.atmkg.infra.ontology.JenaOntologyService;
import org.atmkg.service.sync.DefaultSyncService;
import org.atmkg.testsupport.InMemoryGraphStore;
import org.junit.jupiter.api.Test;

class Phase3FixtureSyncIntegrationTest {
    private static final String NS = "urn:atm-knowledge-graph:";
    private static final List<String> OBJECTS = List.of(
            "AIRPORT", "RUNWAY", "RUNWAY_DIRECTION", "NAVIGATION_AID", "REPORTING_POINT",
            "ROUTE", "SCHEDULED_FLIGHT_ROUTE", "ROUTE_NODE", "ROUTE_SEGMENT", "AIRSPACE",
            "AIRSPACE_GEOMETRY", "BOUNDARY_POINT", "CONTROL_AREA", "FLIGHT_INFORMATION_REGION");
    private static final Map<String, String> KEYS = Map.ofEntries(
            Map.entry("AIRPORT", "airportCode"), Map.entry("RUNWAY", "runwayCode"),
            Map.entry("RUNWAY_DIRECTION", "directionKey"), Map.entry("NAVIGATION_AID", "navigationAidCode"),
            Map.entry("REPORTING_POINT", "reportingPointCode"), Map.entry("ROUTE", "routeCode"),
            Map.entry("SCHEDULED_FLIGHT_ROUTE", "scheduledRouteCode"), Map.entry("ROUTE_NODE", "nodeKey"),
            Map.entry("ROUTE_SEGMENT", "segmentKey"), Map.entry("AIRSPACE", "airspaceCode"),
            Map.entry("AIRSPACE_GEOMETRY", "geometryKey"), Map.entry("BOUNDARY_POINT", "boundaryPointKey"),
            Map.entry("CONTROL_AREA", "controlAreaCode"),
            Map.entry("FLIGHT_INFORMATION_REGION", "flightInformationRegionCode"));

    @Test
    void changedFixtureReconcilesCurrentSourceTruthAndCompensatesMissedEvent() {
        Path root = Path.of(".").toAbsolutePath().normalize();
        var schema = new JenaOntologyService().load(root.resolve("ontology/atm_knowledge_graph.ttl"));
        var catalog = new PoiMappingRegistry().load(root.resolve("fixtures/mapping/fixture_mapping.xlsx"), schema);
        DeterministicIdentityResolver ids = new DeterministicIdentityResolver(NS);
        DefaultMappingEngine mapping = new DefaultMappingEngine(catalog, ids);
        CsvFixtureSourceAdapter base = new CsvFixtureSourceAdapter(
                "fixture", root.resolve("fixtures/generated/small"), KEYS);
        CsvFixtureSourceAdapter changed = new CsvFixtureSourceAdapter(
                "fixture", root.resolve("fixtures/generated/small/changed"), KEYS);
        InMemoryGraphStore store = new InMemoryGraphStore();
        DefaultSyncService baseSync = new DefaultSyncService(Map.of("fixture", base), mapping, store);
        baseSync.fullRebuild(OBJECTS.stream().map(name -> new SourceScope("fixture", name)).toList());
        assertEquals(122, store.stats().getEntityCount());
        assertEquals(186, store.stats().getRelationshipCount());

        EntityMappingSpec airport = entityMapping(catalog, "Airport");
        EntityMappingSpec runway = entityMapping(catalog, "Runway");
        EntityMappingSpec routeNode = entityMapping(catalog, "RouteNode");
        EntityMappingSpec reportingPoint = entityMapping(catalog, "ReportingPoint");
        EntityMappingSpec controlArea = entityMapping(catalog, "ControlArea");
        String z001 = ids.entityUid(airport.getClassIri(), "Z001");
        String z002 = ids.entityUid(airport.getClassIri(), "Z002");
        String z999 = ids.entityUid(airport.getClassIri(), "Z999");
        String changedRunway = ids.entityUid(runway.getClassIri(), "Z001-01/19");
        String insertedRunway = ids.entityUid(runway.getClassIri(), "Z999-01/19");
        String changedNode = ids.entityUid(routeNode.getClassIri(), "R003:N005");
        String oldNextNode = ids.entityUid(routeNode.getClassIri(), "R003:N006");
        String missedReportingPoint = ids.entityUid(reportingPoint.getClassIri(), "RPT002");
        String deletedControlArea = ids.entityUid(controlArea.getClassIri(), "CTA003");
        assertRelationship(store, NS + "hasRunway", z001, changedRunway, true);
        assertRelationship(store, NS + "hasRunway", z002, changedRunway, false);

        DefaultSyncService sync = new DefaultSyncService(Map.of("fixture", changed), mapping, store);
        List<ChangeEvent> events = new FixtureChangeEventReader().read(root.resolve("fixtures/generated/small/changes.csv"));
        for (ChangeEvent event : events) sync.handle(event);
        sync.handle(events.get(0));

        GraphEntity updatedAirport = store.findEntity(z001).orElseThrow();
        assertEquals("模拟机场1-已更新", updatedAirport.getProperties().get(NS + "nameZh"));
        assertTrue(store.findEntity(z999).isPresent());
        assertTrue(store.findEntity(insertedRunway).isPresent());
        assertRelationship(store, NS + "hasRunway", z999, insertedRunway, true);
        assertRelationship(store, NS + "hasRunway", z002, changedRunway, true);
        assertRelationship(store, NS + "hasRunway", z001, changedRunway, false);
        GraphEntity node = store.findEntity(changedNode).orElseThrow();
        assertFalse(node.getProperties().containsKey(NS + "nodeName"));
        assertRelationship(store, NS + "nextNode", changedNode, oldNextNode, false);
        assertTrue(store.findEntity(deletedControlArea).isEmpty());
        assertEquals("模拟报告点2", store.findEntity(missedReportingPoint).orElseThrow()
                .getProperties().get(NS + "reportingPointName"));
        assertEquals(123, store.stats().getEntityCount());
        assertEquals(186, store.stats().getRelationshipCount());

        sync.resync("fixture", "RUNWAY", "Z001-01/19");
        assertEquals(123, store.stats().getEntityCount());
        assertEquals(186, store.stats().getRelationshipCount());

        sync.compensateSince("fixture", "REPORTING_POINT", Instant.EPOCH);
        assertEquals("模拟报告点2-补偿更新", store.findEntity(missedReportingPoint).orElseThrow()
                .getProperties().get(NS + "reportingPointName"));
        assertEquals(123, store.stats().getEntityCount());
        assertEquals(186, store.stats().getRelationshipCount());
    }

    private EntityMappingSpec entityMapping(org.atmkg.core.model.mapping.MappingCatalog catalog, String localName) {
        return catalog.uniqueEntityMapping("fixture", NS + localName).orElseThrow();
    }

    private void assertRelationship(InMemoryGraphStore store, String predicate, String sourceUid,
                                    String targetUid, boolean expected) {
        boolean actual = store.relationships().stream().map(GraphRelationship.class::cast).anyMatch(value ->
                value.getPredicateIri().equals(predicate)
                        && value.getSourceUid().equals(sourceUid)
                        && value.getTargetUid().equals(targetUid));
        assertEquals(expected, actual);
    }
}
