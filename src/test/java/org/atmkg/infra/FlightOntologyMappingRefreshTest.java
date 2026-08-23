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
    void refreshAddsFlightTermsAsPendingWithoutChangingExistingMappings() throws Exception {
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
            assertPending(workbook.getSheet("实体映射"), 0, "urn:atm-knowledge-graph:Flight");
            assertPending(workbook.getSheet("实体映射"), 0, "urn:atm-knowledge-graph:PlannedFlightRoute");
            assertPending(workbook.getSheet("属性映射"), 1, "urn:atm-knowledge-graph:flightId");
            assertPending(workbook.getSheet("关系映射"), 0, "urn:atm-knowledge-graph:departsFrom");
            assertPending(workbook.getSheet("关系映射"), 0, "urn:atm-knowledge-graph:arrivesAt");
            assertPending(workbook.getSheet("关系映射"), 0, "urn:atm-knowledge-graph:hasPlannedRoute");
        }
    }

    private void assertPending(Sheet sheet, int iriColumn, String iri) {
        DataFormatter formatter = new DataFormatter();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;
            if (!iri.equals(formatter.formatCellValue(row.getCell(iriColumn)).trim())) continue;
            for (int c = 0; c < row.getLastCellNum(); c++) {
                if (PoiMappingRegistry.PENDING.equals(formatter.formatCellValue(row.getCell(c)).trim())) return;
            }
        }
        throw new AssertionError("未找到待映射项: " + iri);
    }
}
