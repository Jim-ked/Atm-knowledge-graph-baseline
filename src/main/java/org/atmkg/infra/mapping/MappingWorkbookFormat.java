package org.atmkg.infra.mapping;

import java.util.List;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFSheet;

/** Shared fixed layout for the single supported mapping workbook contract. */
public final class MappingWorkbookFormat {
    public static final String ENTITY_SHEET = "实体映射";
    public static final String PROPERTY_SHEET = "属性映射";
    public static final String RELATIONSHIP_SHEET = "关系映射";
    public static final String REFERENCE_SHEET = "本体参考";

    public static final List<String> ENTITY_HEADERS =
            List.of("sourceId", "sourceObject", "实体类", "业务主键");
    public static final List<String> PROPERTY_HEADERS =
            List.of("sourceId", "sourceObject", "实体类", "源字段/路径", "本体属性", "转换", "必填");
    public static final List<String> RELATIONSHIP_HEADERS = List.of(
            "sourceId", "sourceObject", "关系类型", "起点类", "起点引用字段", "终点类", "终点引用字段", "说明");
    public static final List<String> REFERENCE_HEADERS =
            List.of("类型", "名称", "中文名称", "Domain", "Range", "完整IRI");

    private static final String[] TRANSFORMS =
            {"", "trim", "upper", "lower", "integer", "long", "decimal", "boolean"};
    private static final String[] REQUIRED = {"是", "否"};

    private MappingWorkbookFormat() {}

    public static void createFormalSheets(Workbook workbook) {
        writeHeader(workbook.createSheet(ENTITY_SHEET), ENTITY_HEADERS);
        writeHeader(workbook.createSheet(PROPERTY_SHEET), PROPERTY_HEADERS);
        writeHeader(workbook.createSheet(RELATIONSHIP_SHEET), RELATIONSHIP_HEADERS);
    }

    public static void applyEditingFeatures(Workbook workbook) {
        CellStyle headerStyle = headerStyle(workbook);
        configure(requireSheet(workbook, ENTITY_SHEET), ENTITY_HEADERS.size(),
                new int[]{18, 24, 24, 28}, headerStyle);
        Sheet properties = requireSheet(workbook, PROPERTY_SHEET);
        configure(properties, PROPERTY_HEADERS.size(),
                new int[]{18, 24, 24, 30, 28, 14, 10}, headerStyle);
        configure(requireSheet(workbook, RELATIONSHIP_SHEET), RELATIONSHIP_HEADERS.size(),
                new int[]{18, 24, 28, 24, 30, 24, 30, 36}, headerStyle);
        clearValidations(properties);
        addListValidation(properties, 5, TRANSFORMS);
        addListValidation(properties, 6, REQUIRED);
    }

    public static void configureReferenceSheet(Sheet sheet) {
        CellStyle headerStyle = headerStyle(sheet.getWorkbook());
        configure(sheet, REFERENCE_HEADERS.size(), new int[]{20, 28, 28, 36, 36, 54}, headerStyle);
    }

    public static void writeHeader(Sheet sheet, List<String> headers) {
        Row row = sheet.createRow(0);
        for (int i = 0; i < headers.size(); i++) row.createCell(i).setCellValue(headers.get(i));
    }

    private static void configure(Sheet sheet, int columns, int[] widths, CellStyle headerStyle) {
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new CellRangeAddress(0, Math.max(0, sheet.getLastRowNum()), 0, columns - 1));
        Row header = sheet.getRow(0);
        if (header != null) {
            for (int i = 0; i < columns; i++) header.getCell(i).setCellStyle(headerStyle);
        }
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
    }

    private static void addListValidation(Sheet sheet, int column, String[] values) {
        DataValidationHelper helper = sheet.getDataValidationHelper();
        DataValidationConstraint constraint = helper.createExplicitListConstraint(values);
        CellRangeAddressList range = new CellRangeAddressList(1, 1_048_575, column, column);
        DataValidation validation = helper.createValidation(constraint, range);
        validation.setShowErrorBox(true);
        validation.createErrorBox("无效值", "请从下拉列表中选择允许的值");
        sheet.addValidationData(validation);
    }

    private static void clearValidations(Sheet sheet) {
        if (sheet instanceof XSSFSheet xssf && xssf.getCTWorksheet().isSetDataValidations()) {
            xssf.getCTWorksheet().unsetDataValidations();
        }
    }

    private static CellStyle headerStyle(Workbook workbook) {
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private static Sheet requireSheet(Workbook workbook, String name) {
        Sheet sheet = workbook.getSheet(name);
        if (sheet == null) throw new IllegalArgumentException("工作簿缺少工作表：" + name);
        return sheet;
    }
}
