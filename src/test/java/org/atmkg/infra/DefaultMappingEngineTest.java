package org.atmkg.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.atmkg.core.model.MappingResult;
import org.atmkg.core.model.SourceRecord;
import org.atmkg.core.model.mapping.EntityMappingSpec;
import org.atmkg.core.model.mapping.MappingCatalog;
import org.atmkg.core.model.mapping.PropertyMappingSpec;
import org.atmkg.core.model.mapping.RelationshipMappingSpec;
import org.atmkg.infra.identity.DeterministicIdentityResolver;
import org.atmkg.infra.mapping.DefaultMappingEngine;
import org.junit.jupiter.api.Test;

class DefaultMappingEngineTest {
    private static final String NS = "urn:atm-knowledge-graph:";

    @Test
    void mapsEntitiesPropertiesAndExplicitRelationshipLocatorsWithoutDomainBranches() {
        EntityMappingSpec airport = new EntityMappingSpec(NS + "Airport", "fixture", "AIRPORT", "airportCode");
        EntityMappingSpec runway = new EntityMappingSpec(NS + "Runway", "fixture", "RUNWAY", "runwayCode");
        PropertyMappingSpec length = new PropertyMappingSpec(NS + "Runway", NS + "length", "fixture", "RUNWAY", "length", "decimal", true);
        RelationshipMappingSpec hasRunway = new RelationshipMappingSpec(NS + "hasRunway", NS + "Airport", NS + "Runway", "fixture", "RUNWAY", "airportCode", "runwayCode", "");
        MappingCatalog catalog = new MappingCatalog(List.of(airport, runway), List.of(length), List.of(hasRunway));
        DefaultMappingEngine engine = new DefaultMappingEngine(catalog, new DeterministicIdentityResolver("urn:test:kg:"));

        SourceRecord record = new SourceRecord("fixture", "RUNWAY", "Z001-01/19",
                Map.of("runwayCode", "Z001-01/19", "airportCode", "Z001", "length", "3200.5"), null);
        MappingResult result = engine.map(record);

        assertEquals(1, result.getEntities().size());
        assertEquals(1, result.getRelationships().size());
        assertEquals(NS + "Runway", result.getEntities().get(0).getClassIri());
        assertTrue(result.getEntities().get(0).getProperties().containsKey(NS + "length"));
    }

    @Test
    void resolvesCanonicalIdentityAcrossDifferentSources() {
        EntityMappingSpec airportBase = new EntityMappingSpec(
                NS + "Airport", "source-A", "airport-base", "airport_code");
        EntityMappingSpec airportPosition = new EntityMappingSpec(
                NS + "Airport", "source-B", "airport-position", "position_airport_code");
        EntityMappingSpec runway = new EntityMappingSpec(
                NS + "Runway", "source-B", "runway", "runway_code");
        RelationshipMappingSpec hasRunway = new RelationshipMappingSpec(
                NS + "hasRunway", NS + "Airport", NS + "Runway", "source-C", "AIRPORT_RUNWAY_REL",
                "airport_code", "runway_code", "");
        MappingCatalog catalog = new MappingCatalog(
                List.of(airportBase, airportPosition, runway), List.of(), List.of(hasRunway));
        DefaultMappingEngine engine = new DefaultMappingEngine(
                catalog, new DeterministicIdentityResolver("urn:test:kg:"));

        MappingResult base = engine.map(new SourceRecord(
                "source-A", "airport-base", "ZBAA", Map.of("airport_code", "ZBAA"), null));
        MappingResult position = engine.map(new SourceRecord(
                "source-B", "airport-position", "ZBAA", Map.of("position_airport_code", "ZBAA"), null));
        MappingResult relationResult = engine.map(new SourceRecord(
                "source-C", "AIRPORT_RUNWAY_REL", "ZBAA|RWY01",
                Map.of("runway_code", "RWY01", "airport_code", "ZBAA"), null));

        assertEquals(base.getEntities().get(0).getUid(), position.getEntities().get(0).getUid());
        assertEquals(base.getEntities().get(0).getUid(),
                relationResult.getRelationships().get(0).getSourceUid());
    }

    @Test
    void relationshipMappingOnlyRunsForItsSourceObject() {
        RelationshipMappingSpec hasRunway = new RelationshipMappingSpec(
                NS + "hasRunway", NS + "Airport", NS + "Runway", "fixture", "runway-rel",
                "airport_code", "runway_code", "");
        MappingCatalog catalog = new MappingCatalog(List.of(), List.of(), List.of(hasRunway));
        DefaultMappingEngine engine = new DefaultMappingEngine(
                catalog, new DeterministicIdentityResolver("urn:test:kg:"));

        MappingResult unrelated = engine.map(new SourceRecord(
                "fixture", "another-object", "1",
                Map.of("airport_code", "ZBAA", "runway_code", "RWY01"), null));

        assertTrue(unrelated.getRelationships().isEmpty());
    }

    @Test
    void entityAndRelationshipLocatorsUseTheSameCompositeKeyResolver() {
        EntityMappingSpec node = new EntityMappingSpec(
                NS + "RouteNode", "source-A", "nodes", "nodeType;nodeCode");
        RelationshipMappingSpec link = new RelationshipMappingSpec(
                NS + "fromNode", NS + "RouteNode", NS + "RouteNode", "source-A", "links",
                "from.type;from.code", "to.type;to.code", "");
        MappingCatalog catalog = new MappingCatalog(List.of(node), List.of(), List.of(link));
        DefaultMappingEngine engine = new DefaultMappingEngine(
                catalog, new DeterministicIdentityResolver("urn:test:kg:"));

        MappingResult entity = engine.map(new SourceRecord("source-A", "nodes", "1",
                Map.of("nodeType", "FIX", "nodeCode", "DOGAR"), null));
        MappingResult relation = engine.map(new SourceRecord("source-A", "links", "1",
                Map.of("from", Map.of("type", "FIX", "code", "DOGAR"),
                        "to", Map.of("type", "FIX", "code", "OTHER")), null));

        assertEquals(entity.getEntities().get(0).getUid(), relation.getRelationships().get(0).getSourceUid());
    }

    @Test
    void multiSourceRouteNodesWithDifferentBusinessFieldNamesShareUid() {
        EntityMappingSpec sourceA = new EntityMappingSpec(
                NS + "RouteNode", "source-A", "nodes", "NODE_CODE");
        EntityMappingSpec sourceB = new EntityMappingSpec(
                NS + "RouteNode", "source-B", "nodes", "POINT_CODE");
        DefaultMappingEngine engine = new DefaultMappingEngine(
                new MappingCatalog(List.of(sourceA, sourceB), List.of(), List.of()),
                new DeterministicIdentityResolver("urn:test:kg:"));

        MappingResult left = engine.map(new SourceRecord("source-A", "nodes", "1",
                Map.of("NODE_CODE", " DOGAR "), null));
        MappingResult right = engine.map(new SourceRecord("source-B", "nodes", "1",
                Map.of("POINT_CODE", "DOGAR"), null));

        assertEquals(left.getEntities().get(0).getUid(), right.getEntities().get(0).getUid());
    }
}
