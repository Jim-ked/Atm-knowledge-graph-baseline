package org.atmkg.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.atmkg.core.model.GraphRelationship;
import org.atmkg.core.model.mapping.EntityMappingSpec;
import org.atmkg.core.model.mapping.MappingCatalog;
import org.atmkg.core.model.mapping.PropertyMappingSpec;
import org.atmkg.core.model.mapping.RelationshipMappingSpec;
import org.atmkg.fixture.CsvFixtureSourceAdapter;
import org.atmkg.fixture.FixtureChangeEventReader;
import org.atmkg.infra.identity.DeterministicIdentityResolver;
import org.atmkg.infra.mapping.DefaultMappingEngine;
import org.atmkg.service.sync.DefaultSyncService;
import org.atmkg.testsupport.InMemoryGraphStore;
import org.junit.jupiter.api.Test;

class SyncFixtureIntegrationTest {
    private static final String NS = "urn:atm-knowledge-graph:";

    @Test
    void changedSnapshotExercisesUpdateReferenceChangeAndInsert() {
        Path root = Path.of("fixtures/generated/small");
        Map<String, String> keys = Map.of(
                "AIRPORT", "airportCode", "RUNWAY", "runwayCode", "AIRSPACE", "airspaceCode",
                "ROUTE_NODE", "nodeKey", "CONTROL_AREA", "controlAreaCode");
        CsvFixtureSourceAdapter base = new CsvFixtureSourceAdapter("fixture", root, keys);
        CsvFixtureSourceAdapter changed = new CsvFixtureSourceAdapter("fixture", root.resolve("changed"), keys);

        EntityMappingSpec airport = new EntityMappingSpec(NS + "Airport", "fixture", "AIRPORT", "airportCode", "businessKey");
        EntityMappingSpec runway = new EntityMappingSpec(NS + "Runway", "fixture", "RUNWAY", "runwayCode", "businessKey");
        EntityMappingSpec airspace = new EntityMappingSpec(NS + "Airspace", "fixture", "AIRSPACE", "airspaceCode", "businessKey");
        MappingCatalog catalog = new MappingCatalog(
                List.of(airport, runway, airspace),
                List.of(
                        new PropertyMappingSpec(NS + "Airport", NS + "nameZh", "", "fixture", "AIRPORT", "nameZh", "trim", true),
                        new PropertyMappingSpec(NS + "Airport", NS + "elevation", "", "fixture", "AIRPORT", "elevation", "decimal", false),
                        new PropertyMappingSpec(NS + "Runway", NS + "length", "", "fixture", "RUNWAY", "length", "decimal", true),
                        new PropertyMappingSpec(NS + "Airspace", NS + "airspaceName", "", "fixture", "AIRSPACE", "airspaceName", "trim", true)),
                List.of(new RelationshipMappingSpec(NS + "hasRunway", NS + "Airport", NS + "Runway", "fixture",
                        "airportCode", "runwayCode", "fixture explicit reference")));

        DeterministicIdentityResolver ids = new DeterministicIdentityResolver(NS);
        DefaultMappingEngine mapping = new DefaultMappingEngine(catalog, ids);
        InMemoryGraphStore store = new InMemoryGraphStore();
        DefaultSyncService baseSync = new DefaultSyncService(Map.of("fixture", base), mapping, store);
        baseSync.fullSync("fixture", "AIRPORT");
        baseSync.fullSync("fixture", "RUNWAY");
        baseSync.fullSync("fixture", "AIRSPACE");
        assertEquals(18, store.stats().getEntityCount());
        assertEquals(10, store.stats().getRelationshipCount());

        DefaultSyncService changedSync = new DefaultSyncService(Map.of("fixture", changed), mapping, store);
        for (var event : new FixtureChangeEventReader().read(root.resolve("changes.csv"))) changedSync.handle(event);
        assertEquals(20, store.stats().getEntityCount());
        assertEquals(11, store.stats().getRelationshipCount());

        String z001 = ids.entityUid(airport, "Z001");
        String z002 = ids.entityUid(airport, "Z002");
        String runwayUid = ids.entityUid(runway, "Z001-01/19");
        assertTrue(String.valueOf(store.findEntity(z001).orElseThrow().getProperties().get(NS + "nameZh")).endsWith("-已更新"));
        assertTrue(store.relationships().stream().anyMatch(r -> r.getSourceUid().equals(z002) && r.getTargetUid().equals(runwayUid)));
        assertFalse(store.relationships().stream().anyMatch(r -> r.getSourceUid().equals(z001) && r.getTargetUid().equals(runwayUid)));

        String insertedAirport = ids.entityUid(airport, "Z999");
        assertTrue(store.findEntity(insertedAirport).isPresent());
        String insertedRunway = ids.entityUid(runway, "Z999-01/19");
        assertTrue(store.findEntity(insertedRunway).isPresent());
        assertTrue(store.relationships().stream().anyMatch(r ->
                r.getSourceUid().equals(insertedAirport) && r.getTargetUid().equals(insertedRunway)));
    }
}
