package org.atmkg.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.atmkg.core.model.mapping.MappingCatalog;
import org.atmkg.core.model.mapping.RelationshipMappingSpec;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFSheet;
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
            if ("本体参考".equals(entry.getKey())) continue;
            List<List<String>> rows = refreshedRows.get(entry.getKey());
            assertEquals(entry.getValue(), rows, () -> entry.getKey() + " 的人工行被删除、改名、覆盖或追加");
        }
        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(copy))) {
            Sheet reference = workbook.getSheet("本体参考");
            assertNotNull(reference);
            assertEquals(List.of("类型", "名称", "中文名称", "Domain", "Range", "完整IRI"), rowValues(reference, 0));
            assertTrue(workbookRows(copy).get("本体参考").stream()
                    .anyMatch(row -> row.contains("Class") && row.contains(NS + "Airport")));
            for (String name : List.of("实体映射", "属性映射", "关系映射")) {
                XSSFSheet sheet = workbook.getSheet(name);
                assertTrue(sheet.getPaneInformation().isFreezePane());
                assertTrue(sheet.getCTWorksheet().isSetAutoFilter());
            }
            assertEquals(2, workbook.getSheet("属性映射").getDataValidations().size());
        }
    }

    @Test
    void acceptsMultipleSourceObjectsWithCompatibleEntityIdentityRule() throws Exception {
        Path copy = temp.resolve("multi-source-mapping.xlsx");
        Files.copy(Path.of("fixtures/mapping/fixture_mapping.xlsx"), copy, StandardCopyOption.REPLACE_EXISTING);
        OntologySchema schema = new JenaOntologyService().load(Path.of("ontology/atm_knowledge_graph.ttl"));
        PoiMappingRegistry registry = new PoiMappingRegistry();
        registry.load(copy, schema);

        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(copy));
             OutputStream output = Files.newOutputStream(copy)) {
            Sheet entities = workbook.getSheet("实体映射");
            Row row = entities.createRow(entities.getLastRowNum() + 1);
            row.createCell(0).setCellValue("fixture");
            row.createCell(1).setCellValue("AIRPORT_POSITION");
            row.createCell(2).setCellValue("Airport");
            row.createCell(3).setCellValue("positionAirportCode");
            workbook.write(output);
        }

        MappingCatalog loaded = registry.load(copy, schema);
        assertTrue(loaded.uniqueEntityMapping("fixture", NS + "Airport").isEmpty());
        assertEquals(1, loaded.entityMappingsFor("fixture", "AIRPORT_POSITION").size());
    }

    @Test
    void acceptsCrossSourceRelationshipWithoutEndpointEntityMappings() {
        OntologySchema schema = new JenaOntologyService().load(Path.of("ontology/atm_knowledge_graph.ttl"));
        MappingCatalog crossSource = new MappingCatalog(List.of(), List.of(), List.of(
                new RelationshipMappingSpec(NS + "hasRunway", NS + "Airport", NS + "Runway",
                        "source-C", "AIRPORT_RUNWAY_REL", "airport_ref", "runway_ref", "")));

        assertDoesNotThrow(() -> new PoiMappingRegistry().validate(crossSource, schema));
    }

    @Test
    void rejectsRelationshipDomainAndRangeMismatch() {
        OntologySchema schema = new JenaOntologyService().load(Path.of("ontology/atm_knowledge_graph.ttl"));
        MappingCatalog invalid = new MappingCatalog(List.of(), List.of(), List.of(
                new RelationshipMappingSpec(NS + "hasRunway", NS + "Runway", NS + "Airport",
                        "source-C", "AIRPORT_RUNWAY_REL", "runway_ref", "airport_ref", "")));

        MappingValidationException error = assertThrows(
                MappingValidationException.class, () -> new PoiMappingRegistry().validate(invalid, schema));
        assertTrue(error.getMessage().contains("domain 不兼容"));
        assertTrue(error.getMessage().contains("range 不兼容"));
    }

    private Map<String, List<List<String>>> workbookRows(Path path) throws Exception {
        Map<String, List<List<String>>> result = new LinkedHashMap<>();
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(path))) {
            for (String sheetName : List.of("实体映射", "属性映射", "关系映射", "本体参考")) {
                Sheet sheet = workbook.getSheet(sheetName);
                if (sheet == null) continue;
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

    private List<String> rowValues(Sheet sheet, int rowIndex) {
        DataFormatter formatter = new DataFormatter();
        Row row = sheet.getRow(rowIndex);
        List<String> values = new ArrayList<>();
        for (int i = 0; i < row.getLastCellNum(); i++) {
            values.add(formatter.formatCellValue(row.getCell(i)).trim());
        }
        return values;
    }

    private static final String NS = "urn:atm-knowledge-graph:";
}
