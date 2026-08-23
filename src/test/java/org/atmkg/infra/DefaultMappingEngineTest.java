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
        EntityMappingSpec airport = new EntityMappingSpec(NS + "Airport", "fixture", "AIRPORT", "airportCode", "businessKey");
        EntityMappingSpec runway = new EntityMappingSpec(NS + "Runway", "fixture", "RUNWAY", "runwayCode", "businessKey");
        PropertyMappingSpec length = new PropertyMappingSpec(NS + "Runway", NS + "length", "跑道长度", "fixture", "RUNWAY", "length", "decimal", true);
        RelationshipMappingSpec hasRunway = new RelationshipMappingSpec(NS + "hasRunway", NS + "Airport", NS + "Runway", "fixture", "airportCode", "runwayCode", "");
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
}
