package org.atmkg.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import org.atmkg.core.model.SourceScope;
import org.atmkg.core.model.SourceRecord;
import org.atmkg.core.model.mapping.EntityMappingSpec;
import org.atmkg.core.model.mapping.MappingCatalog;
import org.atmkg.core.model.mapping.RelationshipMappingSpec;
import org.atmkg.fixture.CsvFixtureSourceAdapter;
import org.atmkg.infra.identity.DeterministicIdentityResolver;
import org.atmkg.infra.mapping.DefaultMappingEngine;
import org.atmkg.service.sync.DefaultSyncService;
import org.atmkg.core.spi.SourceAdapter;
import org.atmkg.testsupport.InMemoryGraphStore;
import org.junit.jupiter.api.Test;

class FullSyncOrderingIntegrationTest {
    private static final String NS = "urn:atm-knowledge-graph:";

    @Test
    void fullSyncCreatesRouteNodesWithoutImplicitSequenceOrNextNodeSemantics() {
        Path root = Path.of("fixtures/generated/small");
        CsvFixtureSourceAdapter source = new CsvFixtureSourceAdapter(
                "fixture", root, Map.of("ROUTE_NODE", "nodeKey"));
        EntityMappingSpec node = new EntityMappingSpec(
                NS + "RouteNode", "fixture", "ROUTE_NODE", "nodeKey");
        MappingCatalog catalog = new MappingCatalog(
                List.of(node),
                List.of(), List.of());
        InMemoryGraphStore store = new InMemoryGraphStore();
        DefaultSyncService sync = new DefaultSyncService(Map.of("fixture", source),
                new DefaultMappingEngine(catalog, new DeterministicIdentityResolver(NS)), store);

        sync.fullSync("fixture", "ROUTE_NODE");
        assertEquals(30, store.stats().getEntityCount());
        assertEquals(0, store.stats().getRelationshipCount());
    }

    @Test
    void sameRouteNodeBusinessKeyWithDifferentSequenceValuesHasNoPropertyConflict() {
        EntityMappingSpec node = new EntityMappingSpec(NS + "RouteNode", "fixture", "ROUTE_NODE", "nodeKey");
        MappingCatalog catalog = new MappingCatalog(List.of(node), List.of(), List.of());
        SourceRecord first = new SourceRecord("fixture", "ROUTE_NODE", "r1",
                Map.of("nodeKey", "DOGAR", "sequenceNumber", 1), Instant.parse("2026-08-21T00:00:00Z"));
        SourceRecord second = new SourceRecord("fixture", "ROUTE_NODE", "r2",
                Map.of("nodeKey", "DOGAR", "sequenceNumber", 9), Instant.parse("2026-08-21T00:00:01Z"));
        SourceAdapter source = new SourceAdapter() {
            public Iterable<SourceRecord> readAll(String objectName) { return List.of(first, second); }
            public java.util.Optional<SourceRecord> readByKey(String objectName, String sourceKey) { return java.util.Optional.empty(); }
            public Iterable<SourceRecord> scanChangedSince(String objectName, Instant since) { return List.of(); }
        };
        InMemoryGraphStore store = new InMemoryGraphStore();
        DefaultSyncService sync = new DefaultSyncService(Map.of("fixture", source),
                new DefaultMappingEngine(catalog, new DeterministicIdentityResolver(NS)), store);
        sync.fullSync("fixture", "ROUTE_NODE");
        assertEquals(1, store.stats().getEntityCount());
    }

    @Test
    void fullRebuildCoordinatesEntitiesAcrossDifferentSourceObjects() {
        Path root = Path.of("fixtures/generated/small");
        CsvFixtureSourceAdapter source = new CsvFixtureSourceAdapter(
                "fixture", root, Map.of("AIRPORT", "airportCode", "RUNWAY", "runwayCode"));
        EntityMappingSpec airport = new EntityMappingSpec(NS + "Airport", "fixture", "AIRPORT", "airportCode");
        EntityMappingSpec runway = new EntityMappingSpec(NS + "Runway", "fixture", "RUNWAY", "runwayCode");
        MappingCatalog catalog = new MappingCatalog(
                List.of(airport, runway), List.of(),
                List.of(new RelationshipMappingSpec(NS + "hasRunway", NS + "Airport", NS + "Runway",
                        "fixture", "RUNWAY", "airportCode", "runwayCode", "explicit fixture reference")));
        InMemoryGraphStore store = new InMemoryGraphStore();
        DefaultSyncService sync = new DefaultSyncService(Map.of("fixture", source),
                new DefaultMappingEngine(catalog, new DeterministicIdentityResolver(NS)), store);

        // Deliberately put RUNWAY before AIRPORT: the implementation must not depend on scope order.
        sync.fullRebuild(List.of(new SourceScope("fixture", "RUNWAY"), new SourceScope("fixture", "AIRPORT")));
        assertEquals(15, store.stats().getEntityCount());
        assertEquals(10, store.stats().getRelationshipCount());
    }
}
