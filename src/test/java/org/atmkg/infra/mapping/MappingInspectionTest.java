package org.atmkg.infra.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.atmkg.core.model.OntologySchema;
import org.atmkg.core.model.OntologyTerm;
import org.atmkg.core.model.mapping.MappingIssue;
import org.atmkg.core.model.mapping.MappingScope;
import org.atmkg.core.model.mapping.MappingScopeStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MappingInspectionTest {
    private static final String NS = "urn:test:";
    @TempDir Path temp;

    @Test
    void invalidScopeDoesNotPreventValidScopeCatalogAndUnmappedIsNormal() throws Exception {
        Path workbook = workbook();
        OntologySchema schema = schema();

        var inspection = new PoiMappingRegistry().inspect(workbook, schema);

        MappingScope invalid = new MappingScope("source-a", "bad-object");
        MappingScope valid = new MappingScope("source-b", "good-object");
        MappingScope unmapped = new MappingScope("source-c", "no-rows");
        assertEquals(MappingScopeStatus.INVALID, inspection.report().status(invalid));
        assertEquals(MappingScopeStatus.VALID, inspection.report().status(valid));
        assertEquals(MappingScopeStatus.UNMAPPED, inspection.report().status(unmapped));
        assertEquals(1, inspection.validCatalog().entityMappingsFor("source-b", "good-object").size());
        assertEquals(1, inspection.validCatalog()
                .propertyMappingsFor("source-b", "good-object", NS + "Airport").size());
        assertTrue(inspection.validCatalog().entityMappingsFor("source-a", "bad-object").isEmpty());
    }

    @Test
    void unknownPropertyBecomesLocatedIssueAndUnmappedOntologyClassIsNotAnIssue() throws Exception {
        var inspection = new PoiMappingRegistry().inspect(workbook(), schema());

        MappingIssue issue = inspection.report().issues().stream()
                .filter(item -> item.sourceId().equals("source-a")).findFirst().orElseThrow();
        assertEquals(MappingWorkbookFormat.PROPERTY_SHEET, issue.sheetName());
        assertEquals(2, issue.rowNumber());
        assertEquals("source-a", issue.sourceId());
        assertEquals("bad-object", issue.sourceObject());
        assertEquals("属性映射", issue.mappingKind());
        assertEquals("missingProperty", issue.term());
        assertEquals("code", issue.sourcePath());
        assertTrue(issue.message().contains("未知数据属性"));
        assertTrue(inspection.report().issues().stream()
                .noneMatch(item -> item.message().contains("UnmappedClass")));
    }

    private Path workbook() throws Exception {
        Path path = temp.resolve("inspection.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream output = Files.newOutputStream(path)) {
            MappingWorkbookFormat.createFormalSheets(workbook);
            append(workbook.getSheet(MappingWorkbookFormat.ENTITY_SHEET),
                    "source-a", "bad-object", "Airport", "code");
            append(workbook.getSheet(MappingWorkbookFormat.ENTITY_SHEET),
                    "source-b", "good-object", "Airport", "code");
            append(workbook.getSheet(MappingWorkbookFormat.PROPERTY_SHEET),
                    "source-a", "bad-object", "Airport", "code", "missingProperty", "", "");
            append(workbook.getSheet(MappingWorkbookFormat.PROPERTY_SHEET),
                    "source-b", "good-object", "Airport", "code", "airportCode", "", "");
            workbook.write(output);
        }
        return path;
    }

    private static void append(Sheet sheet, String... values) {
        Row row = sheet.createRow(sheet.getLastRowNum() + 1);
        for (int index = 0; index < values.length; index++) row.createCell(index).setCellValue(values[index]);
    }

    private static OntologySchema schema() {
        Map<String, OntologyTerm> classes = new LinkedHashMap<>();
        classes.put(NS + "Airport", term("Airport", Set.of()));
        classes.put(NS + "UnmappedClass", term("UnmappedClass", Set.of()));
        OntologyTerm airportCode = new OntologyTerm(
                NS + "airportCode", "机场代码", Set.of(NS + "Airport"), Set.of(), Set.of());
        return new OntologySchema(classes, Map.of(airportCode.getIri(), airportCode), Map.of());
    }

    private static OntologyTerm term(String localName, Set<String> superClasses) {
        return new OntologyTerm(NS + localName, localName, Set.of(), Set.of(), superClasses);
    }
}
