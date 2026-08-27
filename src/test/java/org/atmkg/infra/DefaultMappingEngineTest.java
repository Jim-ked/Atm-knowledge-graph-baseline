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
}
