package org.atmkg.infra.source.excel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.atmkg.core.model.SourceRecord;
import org.atmkg.infra.source.config.SourceConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExcelSourceAdapterTest {
    @TempDir Path temp;

    @Test
    void readsMultipleFilesWithOneMapping() throws Exception {
        Path data = Files.createDirectories(temp.resolve("data"));
        writeWorkbook(data.resolve("航路1.xlsx"), "数据", List.of(
                List.of("routeCode", "CODE"), List.of("R001", "A")));
        writeWorkbook(data.resolve("航路2.xlsx"), "数据", List.of(
                List.of("routeCode", "CODE"), List.of("R002", "B")));

        ExcelSourceAdapter adapter = adapter("""
                sources:
                  - sourceId: excel-main
                    adapter: excel
                    root: data
                    objects:
                      route-base:
                        files: "航路*.xlsx"
                        sheet: 数据
                        keyFields: [routeCode]
                """);

        List<SourceRecord> records = list(adapter.readAll("route-base"));
        assertEquals(2, records.size());
        assertEquals(List.of("R001", "R002"), records.stream().map(SourceRecord::getSourceKey).toList());
        assertEquals("A", records.get(0).getFields().get("CODE"));
        assertEquals("B", records.get(1).getFields().get("CODE"));
    }

    @Test
    void sameColumnNameInDifferentObjectsDoesNotCollide() throws Exception {
        Path data = Files.createDirectories(temp.resolve("data"));
        Path file = data.resolve("混合.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream output = Files.newOutputStream(file)) {
            Sheet route = workbook.createSheet("航路");
            addRows(route, List.of(List.of("ID", "CODE"), List.of("R001", "ROUTE-CODE")));
            Sheet airspace = workbook.createSheet("空域");
            addRows(airspace, List.of(List.of("ID", "CODE"), List.of("A001", "AIRSPACE-CODE")));
            workbook.write(output);
        }

        ExcelSourceAdapter adapter = adapter("""
                sources:
                  - sourceId: excel-main
                    adapter: excel
                    root: data
                    objects:
                      route-base:
                        files: "混合.xlsx"
                        sheet: 航路
                        keyFields: [ID]
                      airspace-base:
                        files: "混合.xlsx"
                        sheet: 空域
                        keyFields: [ID]
                """);

        assertEquals("ROUTE-CODE", list(adapter.readAll("route-base")).get(0).getFields().get("CODE"));
        assertEquals("AIRSPACE-CODE", list(adapter.readAll("airspace-base")).get(0).getFields().get("CODE"));
    }

    @Test
    void adjacentNextPairsRowsAndSkipsTerminalRow() throws Exception {
        Path data = Files.createDirectories(temp.resolve("data"));
        writeWorkbook(data.resolve("班机航线.xlsx"), "Sheet1", List.of(
                List.of("航线ID", "序号", "点", "航向"),
                List.of("A01", "1", "P1", "090"),
                List.of("A01", "2", "P2", "120"),
                List.of("A01", "3", "P3", "")));

        ExcelSourceAdapter adapter = adapter("""
                sources:
                  - sourceId: excel-main
                    adapter: excel
                    root: data
                    objects:
                      scheduled-route-segment:
                        files: "班机航线*.xlsx"
                        sheet: 1
                        keyFields: [航线ID, 序号]
                        recordMode: adjacent_next
                        groupBy: [航线ID]
                        orderBy: 序号
                """);

        List<SourceRecord> records = list(adapter.readAll("scheduled-route-segment"));
        assertEquals(2, records.size());
        assertEquals("A01|1", records.get(0).getSourceKey());
        assertEquals("P1", records.get(0).getFields().get("点"));
        @SuppressWarnings("unchecked")
        Map<String, Object> current = (Map<String, Object>) records.get(0).getFields().get("current");
        @SuppressWarnings("unchecked")
        Map<String, Object> next = (Map<String, Object>) records.get(0).getFields().get("next");
        assertEquals("A01|1", current.get("__sourceKey"));
        assertEquals("P2", next.get("点"));
        assertEquals("A01|2", next.get("__sourceKey"));
        assertEquals("A01|2", records.get(1).getSourceKey());
    }

    @Test
    void groupFirstSelectsTheLowestOrderedRowInEachGroup() throws Exception {
        Path data = Files.createDirectories(temp.resolve("data"));
        writeWorkbook(data.resolve("航路.xlsx"), "数据", List.of(
                List.of("routeCode", "rowKey", "sequence", "point"),
                List.of("R001", "R001:2", "2", "P2"),
                List.of("R001", "R001:1", "1", "P1"),
                List.of("R002", "R002:1", "1", "Q1")));

        ExcelSourceAdapter adapter = adapter("""
                sources:
                  - sourceId: excel-main
                    adapter: excel
                    root: data
                    objects:
                      route-first:
                        files: "航路.xlsx"
                        sheet: 数据
                        keyFields: [rowKey]
                        recordMode: group_first
                        groupBy: [routeCode]
                        orderBy: sequence
                """);

        List<SourceRecord> records = list(adapter.readAll("route-first"));
        assertEquals(List.of("R001:1", "R002:1"),
                records.stream().map(SourceRecord::getSourceKey).toList());
        assertEquals("P1", records.get(0).getFields().get("point"));
    }

    @Test
    void adjacentNextNeverPairsRowsFromDifferentPhysicalFiles() throws Exception {
        Path data = Files.createDirectories(temp.resolve("data"));
        writeWorkbook(data.resolve("航路1.xlsx"), "数据", List.of(
                List.of("routeCode", "rowKey", "sequence"),
                List.of("R001", "R001:1", "1")));
        writeWorkbook(data.resolve("航路2.xlsx"), "数据", List.of(
                List.of("routeCode", "rowKey", "sequence"),
                List.of("R001", "R001:2", "2")));

        ExcelSourceAdapter adapter = adapter("""
                sources:
                  - sourceId: excel-main
                    adapter: excel
                    root: data
                    objects:
                      route-adjacent:
                        files: "航路*.xlsx"
                        sheet: 数据
                        keyFields: [rowKey]
                        recordMode: adjacent_next
                        groupBy: [routeCode]
                        orderBy: sequence
                """);

        assertEquals(0, list(adapter.readAll("route-adjacent")).size());
    }

    @Test
    void duplicateSourceKeyAcrossFilesFailsExplicitly() throws Exception {
        Path data = Files.createDirectories(temp.resolve("data"));
        writeWorkbook(data.resolve("航路1.xlsx"), "数据", List.of(
                List.of("routeCode"), List.of("R001")));
        writeWorkbook(data.resolve("航路2.xlsx"), "数据", List.of(
                List.of("routeCode"), List.of("R001")));

        ExcelSourceAdapter adapter = adapter("""
                sources:
                  - sourceId: excel-main
                    adapter: excel
                    root: data
                    objects:
                      route-base:
                        files: "航路*.xlsx"
                        sheet: 数据
                        keyFields: [routeCode]
                """);

        assertThrows(IllegalStateException.class, () -> list(adapter.readAll("route-base")));
    }

    private ExcelSourceAdapter adapter(String yaml) throws Exception {
        Path config = temp.resolve("sources.yaml");
        Files.writeString(config, yaml);
        SourceConfig sourceConfig = SourceConfig.load(config);
        return new ExcelSourceAdapter(sourceConfig.requireSource("excel-main"), temp);
    }

    private static List<SourceRecord> list(Iterable<SourceRecord> iterable) {
        List<SourceRecord> out = new ArrayList<>();
        iterable.forEach(out::add);
        return out;
    }

    private static void writeWorkbook(Path file, String sheetName, List<List<String>> rows) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream output = Files.newOutputStream(file)) {
            Sheet sheet = workbook.createSheet(sheetName);
            addRows(sheet, rows);
            workbook.write(output);
        }
    }

    private static void addRows(Sheet sheet, List<List<String>> rows) {
        for (int r = 0; r < rows.size(); r++) {
            Row row = sheet.createRow(r);
            for (int c = 0; c < rows.get(r).size(); c++) row.createCell(c).setCellValue(rows.get(r).get(c));
        }
    }
}
