package org.atmkg.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.atmkg.infra.mapping.MappingWorkbookFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MappingCheckMainTest {
    @TempDir Path temp;

    @Test
    void reportsLocatedIssueAndReturnsOneWithoutConnectingNeo4j() throws Exception {
        Path root = temp;
        Files.createDirectories(root.resolve("ontology"));
        Files.copy(Path.of("ontology/atm_knowledge_graph.ttl"), root.resolve("ontology/atm_knowledge_graph.ttl"));
        Files.createDirectories(root.resolve("mapping"));
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             OutputStream output = Files.newOutputStream(root.resolve("mapping/字段映射.xlsx"))) {
            MappingWorkbookFormat.createFormalSheets(workbook);
            append(workbook.getSheet(MappingWorkbookFormat.ENTITY_SHEET), "good", "airports", "Airport", "airportCode");
            append(workbook.getSheet(MappingWorkbookFormat.PROPERTY_SHEET), "bad", "runways", "Airport", "code", "missingProperty", "", "");
            workbook.write(output);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int code = MappingCheckMain.run(root, new PrintStream(bytes), new PrintStream(bytes));
        String output = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(1, code);
        assertTrue(output.contains("属性映射"));
        assertTrue(output.contains("第 2 行"));
        assertTrue(output.contains("sourceId=bad"));
        assertTrue(output.contains("missingProperty"));
        assertTrue(output.contains("影响 scope=bad/runways"));
    }

    @Test
    void unreadableWorkbookReturnsTwo() throws Exception {
        Path root = temp;
        Files.createDirectories(root.resolve("ontology"));
        Files.copy(Path.of("ontology/atm_knowledge_graph.ttl"), root.resolve("ontology/atm_knowledge_graph.ttl"));
        Files.createDirectories(root.resolve("mapping"));
        Files.writeString(root.resolve("mapping/字段映射.xlsx"), "not xlsx");
        assertEquals(2, MappingCheckMain.run(root, System.out, System.err));
    }

    private static void append(Sheet sheet, String... values) {
        Row row = sheet.createRow(sheet.getLastRowNum() + 1);
        for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
    }
}
