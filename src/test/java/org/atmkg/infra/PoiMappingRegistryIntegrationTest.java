package org.atmkg.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.atmkg.core.error.MappingValidationException;
import org.atmkg.core.model.MappingResult;
import org.atmkg.core.model.OntologySchema;
import org.atmkg.core.model.SourceRecord;
import org.atmkg.core.model.mapping.EntityMappingSpec;
import org.atmkg.core.model.mapping.MappingCatalog;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
        Map<String, List<List<String>>> originalRows = workbookRows(copy);
        registry.refreshFromOntology(copy, schema);
        MappingCatalog after = registry.load(copy, schema);
        assertEquals(before.getEntities().size(), after.getEntities().size());
        assertFalse(after.getEntities().isEmpty());

        Map<String, List<List<String>>> refreshedRows = workbookRows(copy);
        for (Map.Entry<String, List<List<String>>> entry : originalRows.entrySet()) {
            List<List<String>> rows = refreshedRows.get(entry.getKey());
            assertTrue(rows.size() >= entry.getValue().size());
            assertEquals(entry.getValue(), rows.subList(0, entry.getValue().size()),
                    () -> entry.getKey() + " 的人工行被删除、改名或覆盖");
            rows.subList(entry.getValue().size(), rows.size()).forEach(row ->
                    assertTrue(row.contains(PoiMappingRegistry.PENDING),
                            () -> entry.getKey() + " 的刷新新增行不是待映射行：" + row));
        }
    }

    @Test
    void acceptsMultipleSourceObjectsWithCompatibleEntityIdentityRule() throws Exception {
        Path copy = temp.resolve("multi-source-mapping.xlsx");
        Files.copy(Path.of("fixtures/mapping/fixture_mapping.xlsx"), copy, StandardCopyOption.REPLACE_EXISTING);
        OntologySchema schema = new JenaOntologyService().load(Path.of("ontology/atm_knowledge_graph.ttl"));
        PoiMappingRegistry registry = new PoiMappingRegistry();
        MappingCatalog original = registry.load(copy, schema);
        EntityMappingSpec airport = original.getEntities().stream()
                .filter(spec -> spec.getSourceId().equals("fixture") && spec.getClassIri().equals(NS + "Airport"))
                .findFirst().orElseThrow();

        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(copy));
             OutputStream output = Files.newOutputStream(copy)) {
            Sheet entities = workbook.getSheet("实体映射");
            Row row = entities.createRow(entities.getLastRowNum() + 1);
            row.createCell(0).setCellValue("Airport");
            row.createCell(1).setCellValue("fixture");
            row.createCell(2).setCellValue("AIRPORT_POSITION");
            row.createCell(3).setCellValue("positionAirportCode");
            row.createCell(4).setCellValue(airport.getUidRule());
            workbook.write(output);
        }

        MappingCatalog loaded = registry.load(copy, schema);
        assertTrue(loaded.uniqueEntityMapping("fixture", NS + "Airport").isEmpty());
        assertTrue(loaded.compatibleEntityMapping("fixture", NS + "Airport").isPresent());
    }

    @Test
    void rejectsIncompatibleUidRulesEvenWithoutRelationshipMapping() {
        OntologySchema schema = new JenaOntologyService().load(Path.of("ontology/atm_knowledge_graph.ttl"));
        MappingCatalog incompatible = new MappingCatalog(List.of(
                new EntityMappingSpec(NS + "Airport", "fixture", "AIRPORT", "airportCode", "rule-a"),
                new EntityMappingSpec(NS + "Airport", "fixture", "AIRPORT_POSITION", "airportCode", "rule-b")),
                List.of(), List.of());

        assertThrows(MappingValidationException.class,
                () -> new PoiMappingRegistry().validate(incompatible, schema));
    }

    private Map<String, List<List<String>>> workbookRows(Path path) throws Exception {
        Map<String, List<List<String>>> result = new LinkedHashMap<>();
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(path))) {
            for (String sheetName : List.of("实体映射", "属性映射", "关系映射")) {
                Sheet sheet = workbook.getSheet(sheetName);
                List<List<String>> rows = new ArrayList<>();
                for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    List<String> cells = new ArrayList<>();
                    if (row != null) {
                        for (int column = 0; column < row.getLastCellNum(); column++) {
                            cells.add(formatter.formatCellValue(row.getCell(column)).trim());
                        }
                    }
                    rows.add(cells);
                }
                result.put(sheetName, rows);
            }
        }
        return result;
    }

    private static final String NS = "urn:atm-knowledge-graph:";
}
