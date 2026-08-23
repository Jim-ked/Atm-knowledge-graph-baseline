package org.atmkg.fixture;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Generates the small, human-readable XLSX shape used by the local SourceRecord preview.
 * This is fixture preparation only; it does not define mapping or graph semantics.
 */
public final class SourcePreviewExcelFixtureGenerator {
    private static final List<String> ROUTE_HEADERS = List.of(
            "航路代码", "序号", "节点代码", "节点名称", "节点类型", "经度", "纬度",
            "磁航向", "反向磁航向", "上限", "下限", "航段距离", "RNP", "下一节点代码", "下一段距离");

    private static final List<String> AIRSPACE_HEADERS = List.of("空域代码", "空域名称", "类型码", "上限", "下限", "呼号");
    private static final List<String> BOUNDARY_HEADERS = List.of("空域代码", "序号", "点代码", "经度", "纬度", "是否闭合");
    private static final List<String> SCHEDULED_HEADERS = List.of("航线代码", "序号", "点代码", "点名称", "点类型", "经度", "纬度", "航向", "下一点代码");

    private SourcePreviewExcelFixtureGenerator() {}

    public static void main(String[] args) throws IOException {
        Path output = args.length == 0 ? Path.of("fixtures/source-preview") : Path.of(args[0]);
        generate(output);
        System.out.println("source-preview Excel fixtures generated: " + output.toAbsolutePath().normalize());
    }

    public static void generate(Path output) throws IOException {
        Files.createDirectories(output);
        writeRoute(output.resolve("航路1.xlsx"), List.of(
                row("R101", "1", "N101", "东河", "报告点", "121.100000", "31.100000", "090", "270", "FL240", "FL180", "42", "5", "N102", "40"),
                row("R101", "2", "N102", "南湖", "导航点", "121.650000", "31.100000", "110", "290", "FL240", "FL180", "38", "5", "N103", "37"),
                row("R101", "3", "N103", "西岭", "报告点", "122.100000", "31.350000", "135", "315", "FL240", "FL180", "", "", "", ""),
                row("R102", "1", "N201", "北港", "报告点", "118.100000", "30.900000", "045", "225", "FL280", "FL200", "55", "10", "N202", "52"),
                row("R102", "2", "N202", "云台", "导航点", "118.650000", "31.250000", "065", "245", "FL280", "FL200", "51", "10", "N203", "49"),
                row("R102", "3", "N203", "东岬", "报告点", "119.050000", "31.700000", "085", "265", "FL280", "FL200", "", "", "", "")));
        writeRoute(output.resolve("航路2.xlsx"), List.of(
                row("R201", "1", "N301", "海门", "报告点", "123.100000", "32.100000", "180", "000", "FL260", "FL190", "62", "5", "N302", "60"),
                row("R201", "2", "N302", "青浦", "导航点", "123.300000", "31.550000", "200", "020", "FL260", "FL190", "58", "5", "N303", "57"),
                row("R201", "3", "N303", "南沙", "报告点", "123.500000", "31.000000", "220", "040", "FL260", "FL190", "", "", "", ""),
                row("R202", "1", "N401", "西关", "报告点", "116.100000", "29.900000", "300", "120", "FL220", "FL160", "47", "10", "N402", "45"),
                row("R202", "2", "N402", "石门", "导航点", "116.750000", "30.100000", "320", "140", "FL220", "FL160", "44", "10", "N403", "43"),
                row("R202", "3", "N403", "北岭", "报告点", "117.300000", "30.550000", "340", "160", "FL220", "FL160", "42", "10", "N404", "41"),
                row("R202", "4", "N404", "东山", "报告点", "117.800000", "31.100000", "010", "190", "FL220", "FL160", "", "", "", "")));

        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream outputStream = Files.newOutputStream(output.resolve("空域.xlsx"))) {
            writeSheet(workbook.createSheet("空域主表"), AIRSPACE_HEADERS, List.of(
                    row("AS1001", "东部训练空域", "101", "FL195", "GND", "EAST-AREA"),
                    row("AS1002", "西部临时空域", "203", "FL245", "FL095", "WEST-AREA")));
            writeSheet(workbook.createSheet("边界点"), BOUNDARY_HEADERS, List.of(
                    row("AS1001", "1", "AS1001-P1", "121.00", "31.00", "否"),
                    row("AS1001", "2", "AS1001-P2", "121.80", "31.00", "否"),
                    row("AS1001", "3", "AS1001-P3", "121.90", "31.70", "否"),
                    row("AS1001", "4", "AS1001-P4", "121.10", "31.80", "是"),
                    row("AS1002", "1", "AS1002-P1", "116.00", "30.00", "否"),
                    row("AS1002", "2", "AS1002-P2", "116.70", "30.10", "否"),
                    row("AS1002", "3", "AS1002-P3", "116.80", "30.80", "否"),
                    row("AS1002", "4", "AS1002-P4", "116.10", "30.90", "是")));
            workbook.write(outputStream);
        }

        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream outputStream = Files.newOutputStream(output.resolve("班机航线.xlsx"))) {
            writeSheet(workbook.createSheet("航线"), SCHEDULED_HEADERS, List.of(
                    row("SFR101", "1", "S101", "起始点", "报告点", "120.10", "30.10", "080", "S102"),
                    row("SFR101", "2", "S102", "中继点", "导航点", "120.70", "30.30", "100", "S103"),
                    row("SFR101", "3", "S103", "终止点", "报告点", "121.20", "30.70", "", ""),
                    row("SFR202", "1", "S201", "西起点", "报告点", "117.10", "29.80", "260", "S202"),
                    row("SFR202", "2", "S202", "中间点", "导航点", "116.50", "30.10", "280", "S203"),
                    row("SFR202", "3", "S203", "北转点", "报告点", "116.00", "30.60", "300", "S204"),
                    row("SFR202", "4", "S204", "终止点", "报告点", "115.70", "31.10", "", "")));
            workbook.write(outputStream);
        }
    }

    private static void writeRoute(Path file, List<List<String>> rows) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream outputStream = Files.newOutputStream(file)) {
            writeSheet(workbook.createSheet("航路"), ROUTE_HEADERS, rows);
            workbook.write(outputStream);
        }
    }

    private static void writeSheet(Sheet sheet, List<String> headers, List<List<String>> rows) {
        Row header = sheet.createRow(0);
        for (int column = 0; column < headers.size(); column++) header.createCell(column).setCellValue(headers.get(column));
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            Row row = sheet.createRow(rowIndex + 1);
            List<String> values = rows.get(rowIndex);
            for (int column = 0; column < values.size(); column++) row.createCell(column).setCellValue(values.get(column));
        }
    }

    private static List<String> row(String... values) {
        return List.of(values);
    }
}
