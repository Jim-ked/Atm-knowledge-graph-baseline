package org.atmkg.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.atmkg.core.model.SourceScope;
import org.atmkg.core.model.mapping.EntityMappingSpec;
import org.atmkg.core.model.mapping.MappingCatalog;
import org.atmkg.core.model.mapping.PropertyMappingSpec;
import org.atmkg.core.model.mapping.RelationshipMappingSpec;
import org.atmkg.fixture.CsvFixtureSourceAdapter;
import org.atmkg.infra.identity.DeterministicIdentityResolver;
import org.atmkg.infra.mapping.DefaultMappingEngine;
import org.atmkg.service.sync.DefaultSyncService;
import org.atmkg.testsupport.InMemoryGraphStore;
import org.junit.jupiter.api.Test;

class FullSyncOrderingIntegrationTest {
    private static final String NS = "urn:atm-knowledge-graph:";

    @Test
    void fullSyncCreatesAllSameObjectEndpointsBeforeForwardRelationships() {
        Path root = Path.of("fixtures/generated/small");
        CsvFixtureSourceAdapter source = new CsvFixtureSourceAdapter(
                "fixture", root, Map.of("ROUTE_NODE", "nodeKey"));
        EntityMappingSpec node = new EntityMappingSpec(
                NS + "RouteNode", "fixture", "ROUTE_NODE", "nodeKey", "businessKey");
        MappingCatalog catalog = new MappingCatalog(
                List.of(node),
                List.of(new PropertyMappingSpec(NS + "RouteNode", NS + "sequenceNumber", "", "fixture",
                        "ROUTE_NODE", "sequenceNumber", "integer", true)),
                List.of(new RelationshipMappingSpec(NS + "nextNode", NS + "RouteNode", NS + "RouteNode",
                        "fixture", "nodeKey", "nextNodeKey", "explicit next-node fixture reference")));
        InMemoryGraphStore store = new InMemoryGraphStore();
        DefaultSyncService sync = new DefaultSyncService(Map.of("fixture", source),
                new DefaultMappingEngine(catalog, new DeterministicIdentityResolver(NS)), store);

        sync.fullSync("fixture", "ROUTE_NODE");
        assertEquals(30, store.stats().getEntityCount());
        assertEquals(24, store.stats().getRelationshipCount());
    }

    @Test
    void fullRebuildCoordinatesEntitiesAcrossDifferentSourceObjects() {
        Path root = Path.of("fixtures/generated/small");
        CsvFixtureSourceAdapter source = new CsvFixtureSourceAdapter(
                "fixture", root, Map.of("AIRPORT", "airportCode", "RUNWAY", "runwayCode"));
        EntityMappingSpec airport = new EntityMappingSpec(NS + "Airport", "fixture", "AIRPORT", "airportCode", "businessKey");
        EntityMappingSpec runway = new EntityMappingSpec(NS + "Runway", "fixture", "RUNWAY", "runwayCode", "businessKey");
        MappingCatalog catalog = new MappingCatalog(
                List.of(airport, runway), List.of(),
                List.of(new RelationshipMappingSpec(NS + "hasRunway", NS + "Airport", NS + "Runway",
                        "fixture", "airportCode", "runwayCode", "explicit fixture reference")));
        InMemoryGraphStore store = new InMemoryGraphStore();
        DefaultSyncService sync = new DefaultSyncService(Map.of("fixture", source),
                new DefaultMappingEngine(catalog, new DeterministicIdentityResolver(NS)), store);

        // Deliberately put RUNWAY before AIRPORT: the implementation must not depend on scope order.
        sync.fullRebuild(List.of(new SourceScope("fixture", "RUNWAY"), new SourceScope("fixture", "AIRPORT")));
        assertEquals(15, store.stats().getEntityCount());
        assertEquals(10, store.stats().getRelationshipCount());
    }
}
