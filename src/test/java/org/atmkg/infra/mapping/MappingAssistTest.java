package org.atmkg.infra.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.atmkg.core.model.OntologySchema;
import org.atmkg.core.model.OntologyTerm;
import org.atmkg.core.model.mapping.MappingCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MappingAssistTest {
    private static final String NS = "urn:test:";
    private static final String ENTITY = NS + "Airport";
    private static final String SUPER = NS + "Facility";

    @TempDir Path temp;

    @Test
    void matchesExactLocalNameLabelAndAsciiCaseOnly() {
        OntologySchema schema = schema(
                property("airportCode", "机场代码", Set.of()),
                property("displayName", "机场名称", Set.of()));

        MappingAssist.Analysis result = MappingAssist.analyze(
                List.of("airportCode", "机场代码", "AIRPORTCODE", "AIRPORT_CODE", "unknown"),
                ENTITY, schema);

        assertEquals(List.of(
                        "airportCode->airportCode",
                        "机场代码->airportCode",
                        "AIRPORTCODE->airportCode"),
                result.candidates().stream()
                        .map(item -> item.sourcePath() + "->" + localName(item.property().getIri()))
                        .toList());
        assertEquals(List.of("AIRPORT_CODE", "unknown"), result.unmatched());
        assertTrue(result.ambiguous().isEmpty());
    }

    @Test
    void filtersByCompatibleDomainIncludingSuperclass() {
        OntologySchema schema = schema(
                property("openName", "openName", Set.of()),
                property("airportName", "airportName", Set.of(ENTITY)),
                property("facilityCode", "facilityCode", Set.of(SUPER)),
                property("runwayCode", "runwayCode", Set.of(NS + "Runway")));

        MappingAssist.Analysis result = MappingAssist.analyze(
                List.of("openName", "airportName", "facilityCode", "runwayCode"), ENTITY, schema);

        assertEquals(List.of("openName", "airportName", "facilityCode"), result.candidates().stream()
                .map(MappingAssist.PropertyCandidate::sourcePath).toList());
        assertEquals(List.of("runwayCode"), result.unmatched());
    }

    @Test
    void reportsMultipleStrictMatchesAsAmbiguous() {
        Map<String, OntologyTerm> properties = new LinkedHashMap<>();
        properties.put(NS + "code", property("code", "代码", Set.of()));
        properties.put(NS + "otherCode", property("otherCode", "code", Set.of()));
        OntologySchema schema = schema(properties);

        MappingAssist.Analysis result = MappingAssist.analyze(List.of("code"), ENTITY, schema);

        assertTrue(result.candidates().isEmpty());
        assertTrue(result.unmatched().isEmpty());
        assertEquals(1, result.ambiguous().size());
        assertEquals(2, result.ambiguous().get(0).properties().size());
    }

    @Test
    void preservesAdjacentPathsWhenWritingAndWorkbookReloads() throws Exception {
        Path workbook = workbook("current.code", false);
        OntologySchema schema = schema(property("code", "代码", Set.of(ENTITY)));
        MappingAssist.Analysis analysis = MappingAssist.analyze(List.of("current.code"), ENTITY, schema);

        MappingAssist.WriteResult result = MappingAssist.write(
                workbook, "source-a", "adjacent", ENTITY, "current.code", analysis, schema);

        assertTrue(result.entityAdded());
        assertEquals(1, result.propertiesAdded());
        MappingCatalog loaded = new PoiMappingRegistry().load(workbook, schema);
        assertEquals("current.code", loaded.getEntities().get(0).getBusinessKey());
        assertEquals("current.code", loaded.getProperties().get(0).getSourcePath());
        assertEquals("", loaded.getProperties().get(0).getTransform());
        assertEquals(false, loaded.getProperties().get(0).isRequired());
    }

    @Test
    void skipsIdenticalEntityAndPreservesExistingPropertyAndRelationships() throws Exception {
        Path workbook = workbook("current.code", true);
        OntologySchema schema = schema(property("code", "代码", Set.of(ENTITY)));
        MappingAssist.Analysis analysis = MappingAssist.analyze(List.of("current.code"), ENTITY, schema);

        MappingAssist.WriteResult result = MappingAssist.write(
                workbook, "source-a", "adjacent", ENTITY, "current.code", analysis, schema);

        assertEquals(false, result.entityAdded());
        assertEquals(0, result.propertiesAdded());
        assertEquals(1, result.propertiesSkipped());
        try (XSSFWorkbook book = new XSSFWorkbook(Files.newInputStream(workbook))) {
            assertEquals(2, book.getSheet(MappingWorkbookFormat.ENTITY_SHEET).getPhysicalNumberOfRows());
            Sheet properties = book.getSheet(MappingWorkbookFormat.PROPERTY_SHEET);
            assertEquals(2, properties.getPhysicalNumberOfRows());
            assertEquals("manualProperty", text(properties.getRow(1), 4));
            assertEquals("trim", text(properties.getRow(1), 5));
            Sheet relationships = book.getSheet(MappingWorkbookFormat.RELATIONSHIP_SHEET);
            assertEquals(2, relationships.getPhysicalNumberOfRows());
            assertEquals("linkedTo", text(relationships.getRow(1), 2));
        }
        new PoiMappingRegistry().load(workbook, schema);
    }

    @Test
    void reportsDifferentBusinessKeyAsConflictWithoutChangingWorkbook() throws Exception {
        Path workbook = workbook("current.code", true);
        byte[] before = Files.readAllBytes(workbook);
        OntologySchema schema = schema(property("code", "代码", Set.of(ENTITY)));
        MappingAssist.Analysis analysis = MappingAssist.analyze(List.of("next.code"), ENTITY, schema);

        MappingAssist.WriteResult result = MappingAssist.write(
                workbook, "source-a", "adjacent", ENTITY, "next.code", analysis, schema);

        assertTrue(result.existingEntityConflict());
        assertEquals(false, result.entityAdded());
        assertEquals(0, result.propertiesAdded());
        assertArrayEquals(before, Files.readAllBytes(workbook));
    }

    @Test
    void leavesOriginalWorkbookUnchangedWhenTemporaryValidationFails() throws Exception {
        Path workbook = workbook("current.code", false);
        byte[] before = Files.readAllBytes(workbook);
        OntologySchema schema = schema(property("code", "代码", Set.of(ENTITY)));
        MappingAssist.Analysis invalid = new MappingAssist.Analysis(
                List.of(new MappingAssist.PropertyCandidate(
                        "current.code", property("notInSchema", "不存在", Set.of(ENTITY)))),
                List.of(), List.of());

        assertThrows(RuntimeException.class, () -> MappingAssist.write(
                workbook, "source-a", "adjacent", ENTITY, "current.code", invalid, schema));

        assertArrayEquals(before, Files.readAllBytes(workbook));
    }

    private Path workbook(String businessKey, boolean withRows) throws Exception {
        Path path = temp.resolve(withRows ? "existing.xlsx" : "empty.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream output = Files.newOutputStream(path)) {
            MappingWorkbookFormat.createFormalSheets(workbook);
            Sheet reference = workbook.createSheet(MappingWorkbookFormat.REFERENCE_SHEET);
            MappingWorkbookFormat.writeHeader(reference, MappingWorkbookFormat.REFERENCE_HEADERS);
            if (withRows) {
                append(workbook.getSheet(MappingWorkbookFormat.ENTITY_SHEET),
                        "source-a", "adjacent", "Airport", businessKey);
                append(workbook.getSheet(MappingWorkbookFormat.PROPERTY_SHEET),
                        "source-a", "adjacent", "Airport", "current.code", "manualProperty", "trim", "是");
                append(workbook.getSheet(MappingWorkbookFormat.RELATIONSHIP_SHEET),
                        "source-a", "adjacent", "linkedTo", "Airport", "current.code",
                        "Airport", "next.code", "人工关系");
            }
            MappingWorkbookFormat.applyEditingFeatures(workbook);
            MappingWorkbookFormat.configureReferenceSheet(reference);
            workbook.write(output);
        }
        return path;
    }

    private static void append(Sheet sheet, String... values) {
        Row row = sheet.createRow(sheet.getLastRowNum() + 1);
        for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
    }

    private static String text(Row row, int column) {
        return new DataFormatter().formatCellValue(row.getCell(column)).trim();
    }

    private static OntologySchema schema(OntologyTerm... properties) {
        Map<String, OntologyTerm> values = new LinkedHashMap<>();
        for (OntologyTerm property : properties) values.put(property.getIri(), property);
        return schema(values);
    }

    private static OntologySchema schema(Map<String, OntologyTerm> properties) {
        properties = new LinkedHashMap<>(properties);
        properties.putIfAbsent(NS + "manualProperty",
                property("manualProperty", "人工属性", Set.of(ENTITY)));
        Map<String, OntologyTerm> classes = new LinkedHashMap<>();
        classes.put(SUPER, new OntologyTerm(SUPER, "设施", Set.of(), Set.of(), Set.of()));
        classes.put(ENTITY, new OntologyTerm(ENTITY, "机场", Set.of(), Set.of(), Set.of(SUPER)));
        classes.put(NS + "Runway", new OntologyTerm(NS + "Runway", "跑道", Set.of(), Set.of(), Set.of()));
        OntologyTerm linkedTo = new OntologyTerm(
                NS + "linkedTo", "关联", Set.of(ENTITY), Set.of(ENTITY), Set.of());
        return new OntologySchema(classes, properties, Map.of(linkedTo.getIri(), linkedTo));
    }

    private static OntologyTerm property(String localName, String label, Set<String> domains) {
        return new OntologyTerm(NS + localName, label, domains, Set.of(), Set.of());
    }

    private static String localName(String iri) {
        return iri.substring(iri.lastIndexOf(':') + 1);
    }
}
