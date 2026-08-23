package org.atmkg.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.neo4j.driver.Values.parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.atmkg.core.error.GraphStoreException;
import org.atmkg.core.model.GraphEntity;
import org.atmkg.core.model.MappingResult;
import org.atmkg.core.model.SourceRecord;
import org.atmkg.core.model.SourceRef;
import org.atmkg.core.model.mapping.EntityMappingSpec;
import org.atmkg.core.model.mapping.MappingCatalog;
import org.atmkg.core.model.mapping.RelationshipMappingSpec;
import org.atmkg.infra.identity.DeterministicIdentityResolver;
import org.atmkg.infra.mapping.DefaultMappingEngine;
import org.atmkg.infra.neo4j.Neo4jConnectionSettings;
import org.atmkg.infra.neo4j.Neo4jDriverFactory;
import org.atmkg.infra.neo4j.Neo4jGraphStore;
import org.atmkg.infra.ontology.JenaOntologyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.neo4j.driver.Driver;
import org.neo4j.driver.SessionConfig;

class Neo4jMultiSourceEntityContributionTest {
    private static final String NS = "urn:atm-knowledge-graph:";
    private static final String PROJECT = "atmkg-multisource-contribution-it";
    private static final String UID = "urn:test:kg:entity:Airport:ZBAA";
    private static final SourceRef BASE = new SourceRef("fixture", "airport-base", "ZBAA");
    private static final SourceRef POSITION = new SourceRef("fixture", "airport-position", "ZBAA");
    private static final SourceRef SECONDARY = new SourceRef("fixture", "airport-secondary", "ZBAA");

    @Test
    @EnabledIfSystemProperty(named = "atmkg.neo4j.it", matches = "true")
    void realNeo4jPreservesCanonicalEntityAcrossSourceContributions() {
        Neo4jConnectionSettings settings = Neo4jConnectionSettings.fromEnvironment(PROJECT, 100);
        var schema = new JenaOntologyService().load(Path.of("ontology/atm_knowledge_graph.ttl"));
        try (Driver driver = Neo4jDriverFactory.create(settings)) {
            driver.verifyConnectivity();
            Neo4jGraphStore store = new Neo4jGraphStore(driver, settings, schema);
            store.initializeSchema();
            store.clearProject();
            try {
                assertRelationshipEndpointResolution();

                store.replaceProjection(BASE, projection(entity(BASE, Map.of(
                        NS + "airportName", "北京首都"))));
                store.replaceProjection(POSITION, projection(entity(POSITION, Map.of(
                        NS + "longitude", 116.58,
                        NS + "latitude", 40.08))));
                GraphEntity merged = store.findEntity(UID).orElseThrow();
                assertEquals("北京首都", merged.getProperties().get(NS + "airportName"));
                assertEquals(116.58, number(merged, "longitude"));
                assertEquals(40.08, number(merged, "latitude"));
                assertFalse(merged.getProvenance().containsKey("sourceId"));
                assertEquals(2, ((List<?>) merged.getProvenance().get("sourceRefs")).size());
                assertEquals(1, store.stats().getEntityCount());

                store.replaceProjection(POSITION, projection(entity(POSITION, Map.of(
                        NS + "longitude", 116.60,
                        NS + "latitude", 40.10))));
                GraphEntity updated = store.findEntity(UID).orElseThrow();
                assertEquals("北京首都", updated.getProperties().get(NS + "airportName"));
                assertEquals(116.60, number(updated, "longitude"));
                assertEquals(40.10, number(updated, "latitude"));

                store.deleteProjection(POSITION);
                GraphEntity afterPositionDelete = store.findEntity(UID).orElseThrow();
                assertEquals("北京首都", afterPositionDelete.getProperties().get(NS + "airportName"));
                assertFalse(afterPositionDelete.getProperties().containsKey(NS + "longitude"));
                assertFalse(afterPositionDelete.getProperties().containsKey(NS + "latitude"));

                store.deleteProjection(BASE);
                assertTrue(store.findEntity(UID).isEmpty());

                store.replaceProjection(BASE, projection(entity(BASE, Map.of(
                        NS + "airportName", "北京首都"))));
                store.replaceProjection(SECONDARY, projection(entity(SECONDARY, Map.of(
                        NS + "airportName", "北京首都"))));
                assertEquals("北京首都", store.findEntity(UID).orElseThrow()
                        .getProperties().get(NS + "airportName"));

                store.deleteEntity(UID);
                assertTrue(store.findEntity(UID).isEmpty());
                assertEquals(0, contributionCount(driver, settings));

                store.replaceProjection(BASE, projection(entity(BASE, Map.of(
                        NS + "airportName", "北京首都"))));
                assertThrows(GraphStoreException.class, () -> store.replaceProjection(
                        SECONDARY, projection(entity(SECONDARY, Map.of(
                                NS + "airportName", "冲突名称")))));
                GraphEntity afterConflict = store.findEntity(UID).orElseThrow();
                assertEquals("北京首都", afterConflict.getProperties().get(NS + "airportName"));
                assertEquals(1, contributionCount(driver, settings));
            } finally {
                store.clearProject();
            }
            assertEquals(0, contributionCount(driver, settings));
        }
    }

    private void assertRelationshipEndpointResolution() {
        EntityMappingSpec airportBase = new EntityMappingSpec(
                NS + "Airport", "fixture", "airport-base", "airport_code", "businessKey");
        EntityMappingSpec airportPosition = new EntityMappingSpec(
                NS + "Airport", "fixture", "airport-position", "position_airport_code", "businessKey");
        EntityMappingSpec runway = new EntityMappingSpec(
                NS + "Runway", "fixture", "runway", "runway_code", "businessKey");
        RelationshipMappingSpec hasRunway = new RelationshipMappingSpec(
                NS + "hasRunway", NS + "Airport", NS + "Runway", "fixture",
                "airport_code", "runway_code", "");
        DefaultMappingEngine engine = new DefaultMappingEngine(
                new MappingCatalog(List.of(airportBase, airportPosition, runway), List.of(), List.of(hasRunway)),
                new DeterministicIdentityResolver("urn:test:kg:"));
        String airportUid = engine.map(new SourceRecord(
                "fixture", "airport-base", "ZBAA", Map.of("airport_code", "ZBAA"), null))
                .getEntities().get(0).getUid();
        MappingResult runwayResult = engine.map(new SourceRecord(
                "fixture", "runway", "RWY01",
                Map.of("airport_code", "ZBAA", "runway_code", "RWY01"), null));
        assertEquals(airportUid, runwayResult.getRelationships().get(0).getSourceUid());
    }

    private GraphEntity entity(SourceRef ref, Map<String, Object> properties) {
        return new GraphEntity(UID, NS + "Airport", "ZBAA", properties, Map.of(
                "sourceId", ref.getSourceId(),
                "sourceObject", ref.getObjectName(),
                "sourceKey", ref.getSourceKey()));
    }

    private MappingResult projection(GraphEntity entity) {
        return new MappingResult(List.of(entity), List.of());
    }

    private double number(GraphEntity entity, String localName) {
        return ((Number) entity.getProperties().get(NS + localName)).doubleValue();
    }

    private long contributionCount(Driver driver, Neo4jConnectionSettings settings) {
        try (var session = driver.session(SessionConfig.forDatabase(settings.getDatabase()))) {
            return session.executeRead(tx -> tx.run(
                    "MATCH (c:KGEntityContribution {kg_project: $projectId}) RETURN count(c) AS count",
                    parameters("projectId", settings.getProjectId())).single().get("count").asLong());
        }
    }
}
