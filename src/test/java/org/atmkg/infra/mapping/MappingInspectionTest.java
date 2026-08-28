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

    @Test
    void incompleteEntityRowsNeverEnterValidCatalog() throws Exception {
        Path path = emptyWorkbook("incomplete-entities.xlsx");
        try (XSSFWorkbook book = new XSSFWorkbook(Files.newInputStream(path));
             OutputStream output = Files.newOutputStream(path)) {
            Sheet entities = book.getSheet(MappingWorkbookFormat.ENTITY_SHEET);
            append(entities, "", "missing-source", "Airport", "code");
            append(entities, "bad", "missing-key", "Airport", "");
            append(entities, "good", "airports", "Airport", "code");
            book.write(output);
        }
        var inspection = new PoiMappingRegistry().inspect(path, schema());
        assertEquals(1, inspection.validCatalog().getEntities().size());
        assertEquals("good", inspection.validCatalog().getEntities().get(0).getSourceId());
        assertTrue(inspection.report().issues().stream().anyMatch(issue -> issue.sourceId().isBlank()));
        assertTrue(inspection.report().issues().stream().anyMatch(issue -> issue.message().contains("businessKey")));
    }

    @Test
    void inactiveRowsAreIgnored() throws Exception {
        Path path = emptyWorkbook("inactive-rows.xlsx");
        try (XSSFWorkbook book = new XSSFWorkbook(Files.newInputStream(path));
             OutputStream output = Files.newOutputStream(path)) {
            append(book.getSheet(MappingWorkbookFormat.ENTITY_SHEET),
                    "inactive", "rows", "", "code-a");
            append(book.getSheet(MappingWorkbookFormat.ENTITY_SHEET),
                    "inactive", "rows", "", "code-b");
            append(book.getSheet(MappingWorkbookFormat.PROPERTY_SHEET),
                    "inactive", "rows", "Airport", "code", "", "", "");
            append(book.getSheet(MappingWorkbookFormat.RELATIONSHIP_SHEET),
                    "inactive", "rows", "", "Airport", "code", "Airport", "target", "");
            book.write(output);
        }
        var inspection = new PoiMappingRegistry().inspect(path, schema());
        assertTrue(inspection.validCatalog().getEntities().isEmpty());
        assertTrue(inspection.validCatalog().getProperties().isEmpty());
        assertTrue(inspection.validCatalog().getRelationships().isEmpty());
        assertTrue(inspection.report().issues().isEmpty());
        assertEquals(MappingScopeStatus.UNMAPPED,
                inspection.report().status(new MappingScope("inactive", "rows")));
    }

    @Test
    void activeIncompleteRowsProduceIssues() throws Exception {
        Path path = emptyWorkbook("active-incomplete-rows.xlsx");
        try (XSSFWorkbook book = new XSSFWorkbook(Files.newInputStream(path));
             OutputStream output = Files.newOutputStream(path)) {
            append(book.getSheet(MappingWorkbookFormat.ENTITY_SHEET),
                    "bad-entity", "airports", "Airport", "");
            append(book.getSheet(MappingWorkbookFormat.PROPERTY_SHEET),
                    "bad-property", "airports", "Airport", "", "airportCode", "", "");
            append(book.getSheet(MappingWorkbookFormat.RELATIONSHIP_SHEET),
                    "bad-relationship", "links", "linkedTo", "Airport", "code", "Airport", "", "");
            book.write(output);
        }
        var inspection = new PoiMappingRegistry().inspect(path, schema());
        assertTrue(inspection.validCatalog().getEntities().isEmpty());
        assertTrue(inspection.validCatalog().getProperties().isEmpty());
        assertTrue(inspection.validCatalog().getRelationships().isEmpty());
        assertEquals(MappingScopeStatus.INVALID,
                inspection.report().status(new MappingScope("bad-entity", "airports")));
        assertEquals(MappingScopeStatus.INVALID,
                inspection.report().status(new MappingScope("bad-property", "airports")));
        assertEquals(MappingScopeStatus.INVALID,
                inspection.report().status(new MappingScope("bad-relationship", "links")));
        assertTrue(inspection.report().issues().stream()
                .anyMatch(issue -> issue.mappingKind().equals("实体映射")
                        && issue.message().contains("businessKey")));
        assertTrue(inspection.report().issues().stream()
                .anyMatch(issue -> issue.mappingKind().equals("属性映射")
                        && issue.message().contains("sourcePath")));
        assertTrue(inspection.report().issues().stream()
                .anyMatch(issue -> issue.mappingKind().equals("关系映射")
                        && issue.message().contains("objectLocator")));
    }

    @Test
    void propertyWithoutEntityMappingInvalidatesOnlyItsScope() throws Exception {
        Path path = emptyWorkbook("property-without-entity.xlsx");
        try (XSSFWorkbook book = new XSSFWorkbook(Files.newInputStream(path));
             OutputStream output = Files.newOutputStream(path)) {
            append(book.getSheet(MappingWorkbookFormat.PROPERTY_SHEET),
                    "property-only", "airports", "Airport", "code", "airportCode", "", "");
            append(book.getSheet(MappingWorkbookFormat.ENTITY_SHEET),
                    "good", "airports", "Airport", "code");
            book.write(output);
        }
        var inspection = new PoiMappingRegistry().inspect(path, schema());
        assertEquals(MappingScopeStatus.INVALID,
                inspection.report().status(new MappingScope("property-only", "airports")));
        assertEquals(MappingScopeStatus.VALID,
                inspection.report().status(new MappingScope("good", "airports")));
        assertTrue(inspection.report().issues().stream()
                .anyMatch(issue -> issue.message().contains("缺少实体映射")));
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

    private Path emptyWorkbook(String name) throws Exception {
        Path path = temp.resolve(name);
        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream output = Files.newOutputStream(path)) {
            MappingWorkbookFormat.createFormalSheets(workbook);
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
        OntologyTerm linkedTo = new OntologyTerm(
                NS + "linkedTo", "关联", Set.of(NS + "Airport"), Set.of(NS + "Airport"), Set.of());
        return new OntologySchema(classes, Map.of(airportCode.getIri(), airportCode),
                Map.of(linkedTo.getIri(), linkedTo));
    }

    private static OntologyTerm term(String localName, Set<String> superClasses) {
        return new OntologyTerm(NS + localName, localName, Set.of(), Set.of(), superClasses);
    }
}
