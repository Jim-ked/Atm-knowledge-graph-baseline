package org.atmkg.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import org.atmkg.core.model.MappingResult;
import org.atmkg.core.model.OntologySchema;
import org.atmkg.core.model.SourceRecord;
import org.atmkg.core.model.mapping.MappingCatalog;
import org.atmkg.fixture.CsvFixtureSourceAdapter;
import org.atmkg.fixture.FixtureDataGenerator;
import org.atmkg.fixture.FixtureScale;
import org.atmkg.infra.identity.DeterministicIdentityResolver;
import org.atmkg.infra.mapping.DefaultMappingEngine;
import org.atmkg.infra.mapping.PoiMappingRegistry;
import org.atmkg.infra.ontology.JenaOntologyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PoiMappingRegistryIntegrationTest {
    @TempDir Path temp;

    @Test
    void fixtureWorkbookDrivesCsvToGraphObjects() {
        OntologySchema schema = new JenaOntologyService().load(Path.of("ontology/atm_knowledge_graph.ttl"));
        MappingCatalog catalog = new PoiMappingRegistry().load(Path.of("fixtures/mapping/fixture_mapping.xlsx"), schema);
        assertEquals(14, catalog.getEntities().size());
        assertEquals(13, catalog.getRelationships().size());

        Path data = temp.resolve("data");
        new FixtureDataGenerator().generate(data, FixtureScale.SMALL, 20260821L);
        CsvFixtureSourceAdapter source = new CsvFixtureSourceAdapter("fixture", data, Map.of(
                "AIRPORT", "airportCode", "RUNWAY", "runwayCode", "ROUTE", "routeCode",
                "ROUTE_NODE", "nodeKey", "ROUTE_SEGMENT", "segmentKey", "AIRSPACE", "airspaceCode"));
        DefaultMappingEngine engine = new DefaultMappingEngine(catalog, new DeterministicIdentityResolver("urn:test:atmkg:"));

        int entities = 0;
        int relationships = 0;
        for (String objectName : new String[]{"AIRPORT","RUNWAY","ROUTE","ROUTE_NODE","ROUTE_SEGMENT","AIRSPACE"}) {
            for (SourceRecord record : source.readAll(objectName)) {
                MappingResult result = engine.map(record);
                entities += result.getEntities().size();
                relationships += result.getRelationships().size();
            }
        }
        assertTrue(entities > 0);
        assertTrue(relationships > 0);
    }

    @Test
    void ontologyRefreshPreservesExistingHumanMappings() throws Exception {
        Path copy = temp.resolve("mapping.xlsx");
        Files.copy(Path.of("fixtures/mapping/fixture_mapping.xlsx"), copy, StandardCopyOption.REPLACE_EXISTING);
        OntologySchema schema = new JenaOntologyService().load(Path.of("ontology/atm_knowledge_graph.ttl"));
        PoiMappingRegistry registry = new PoiMappingRegistry();
        MappingCatalog before = registry.load(copy, schema);
        registry.refreshFromOntology(copy, schema);
        MappingCatalog after = registry.load(copy, schema);
        assertEquals(before.getEntities().size(), after.getEntities().size());
        assertFalse(after.getEntities().isEmpty());
    }
}
