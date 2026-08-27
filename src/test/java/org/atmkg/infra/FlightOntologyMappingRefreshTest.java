package org.atmkg.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.atmkg.core.model.OntologySchema;
import org.atmkg.core.model.mapping.MappingCatalog;
import org.atmkg.infra.mapping.PoiMappingRegistry;
import org.atmkg.infra.ontology.JenaOntologyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlightOntologyMappingRefreshTest {
    @TempDir Path temp;

    @Test
    void refreshListsFlightTermsInOntologyReferenceWithoutChangingExistingMappings() throws Exception {
        Path copy = temp.resolve("mapping.xlsx");
        Files.copy(Path.of("fixtures/mapping/fixture_mapping.xlsx"), copy, StandardCopyOption.REPLACE_EXISTING);

        OntologySchema schema = new JenaOntologyService().load(Path.of("ontology/atm_knowledge_graph.ttl"));
        PoiMappingRegistry registry = new PoiMappingRegistry();
        MappingCatalog before = registry.load(copy, schema);

        registry.refreshFromOntology(copy, schema);
        MappingCatalog after = registry.load(copy, schema);

        assertEquals(before.getEntities().size(), after.getEntities().size());
        assertEquals(before.getProperties().size(), after.getProperties().size());
        assertEquals(before.getRelationships().size(), after.getRelationships().size());

        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(copy))) {
            Sheet reference = workbook.getSheet("本体参考");
            assertReference(reference, "Class", "urn:atm-knowledge-graph:Flight");
            assertReference(reference, "Class", "urn:atm-knowledge-graph:PlannedFlightRoute");
            assertReference(reference, "DatatypeProperty", "urn:atm-knowledge-graph:flightId");
            assertReference(reference, "ObjectProperty", "urn:atm-knowledge-graph:departsFrom");
            assertReference(reference, "ObjectProperty", "urn:atm-knowledge-graph:arrivesAt");
            assertReference(reference, "ObjectProperty", "urn:atm-knowledge-graph:hasPlannedRoute");
        }
    }

    private void assertReference(Sheet sheet, String type, String iri) {
        DataFormatter formatter = new DataFormatter();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;
            if (type.equals(formatter.formatCellValue(row.getCell(0)).trim())
                    && iri.equals(formatter.formatCellValue(row.getCell(5)).trim())) return;
        }
        throw new AssertionError("本体参考未找到: " + type + " / " + iri);
    }
}
