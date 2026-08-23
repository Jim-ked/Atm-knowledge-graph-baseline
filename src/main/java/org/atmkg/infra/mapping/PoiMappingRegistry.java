package org.atmkg.infra.mapping;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.atmkg.core.error.MappingExecutionException;
import org.atmkg.core.error.MappingValidationException;
import org.atmkg.core.model.OntologySchema;
import org.atmkg.core.model.OntologyTerm;
import org.atmkg.core.model.mapping.EntityMappingSpec;
import org.atmkg.core.model.mapping.MappingCatalog;
import org.atmkg.core.model.mapping.PropertyMappingSpec;
import org.atmkg.core.model.mapping.RelationshipMappingSpec;
import org.atmkg.core.spi.MappingRegistry;

public final class PoiMappingRegistry implements MappingRegistry {
    public static final String PENDING = "[待映射]";
    private final DataFormatter formatter = new DataFormatter();

    @Override
    public MappingCatalog load(Path mappingFile, OntologySchema schema) {
        requireFile(mappingFile);
        try (InputStream input = Files.newInputStream(mappingFile); Workbook workbook = new XSSFWorkbook(input)) {
            List<EntityMappingSpec> entities = loadEntities(requireSheet(workbook, "实体映射"), schema);
            List<PropertyMappingSpec> properties = loadProperties(requireSheet(workbook, "属性映射"), schema);
            List<RelationshipMappingSpec> relationships = loadRelationships(requireSheet(workbook, "关系映射"), schema);
            MappingCatalog catalog = new MappingCatalog(entities, properties, relationships);
            validate(catalog, schema);
            return catalog;
        } catch (IOException ex) {
            throw new MappingExecutionException("字段映射文件读取失败：" + mappingFile, ex);
        }
    }

    @Override
    public void validate(MappingCatalog catalog, OntologySchema schema) {
        List<String> issues = new ArrayList<>();
        Set<String> entityKeys = new HashSet<>();
        for (EntityMappingSpec spec : catalog.getEntities()) {
            if (!schema.hasClass(spec.getClassIri())) issues.add("未知实体类 " + spec.getClassIri());
            if (blank(spec.getSourceId()) || blank(spec.getSourceObject()) || blank(spec.getBusinessKey())) {
                issues.add("实体映射缺少 sourceId/sourceObject/businessKey: " + spec.getClassIri());
            }
            String key = spec.getSourceId() + "|" + spec.getSourceObject() + "|" + spec.getClassIri();
            if (!entityKeys.add(key)) issues.add("实体映射重复：" + key);
        }
        Set<String> identityGroups = new HashSet<>();
        for (EntityMappingSpec spec : catalog.getEntities()) {
            String group = spec.getSourceId() + "\u0000" + spec.getClassIri();
            if (identityGroups.add(group)
                    && catalog.compatibleEntityMapping(spec.getSourceId(), spec.getClassIri()).isEmpty()) {
                issues.add("实体身份映射 UID规则不兼容：" + spec.getSourceId() + " / " + spec.getClassIri());
            }
        }

        for (PropertyMappingSpec spec : catalog.getProperties()) {
            if (!schema.hasClass(spec.getClassIri())) issues.add("属性映射引用未知实体类 " + spec.getClassIri());
            OntologyTerm term = schema.getDatatypeProperties().get(spec.getPropertyIri());
            if (term == null) {
                issues.add("未知数据属性 " + spec.getPropertyIri());
                continue;
            }
            if (blank(spec.getSourceId()) || blank(spec.getSourceObject()) || blank(spec.getSourcePath())) {
                issues.add("属性映射缺少 sourceId/sourceObject/sourcePath: " + spec.getPropertyIri());
            }
            for (String domain : term.getDomains()) {
                if (schema.hasClass(domain) && !schema.isClassCompatible(spec.getClassIri(), domain)) {
                    issues.add("属性 domain 不兼容：" + spec.getPropertyIri() + " <- " + spec.getClassIri());
                }
            }
        }

        for (RelationshipMappingSpec spec : catalog.getRelationships()) {
            OntologyTerm term = schema.getObjectProperties().get(spec.getPredicateIri());
            if (term == null) {
                issues.add("未知对象属性 " + spec.getPredicateIri());
                continue;
            }
            if (!schema.hasClass(spec.getSubjectClassIri())) issues.add("关系起点类不存在 " + spec.getSubjectClassIri());
            if (!schema.hasClass(spec.getObjectClassIri())) issues.add("关系终点类不存在 " + spec.getObjectClassIri());
            if (blank(spec.getSourceId()) || blank(spec.getSubjectLocator()) || blank(spec.getObjectLocator())) {
                issues.add("关系映射缺少 sourceId/定位字段: " + spec.getPredicateIri());
            }
            for (String domain : term.getDomains()) {
                if (schema.hasClass(domain) && !schema.isClassCompatible(spec.getSubjectClassIri(), domain)) {
                    issues.add("关系 domain 不兼容：" + spec.getPredicateIri() + " <- " + spec.getSubjectClassIri());
                }
            }
            for (String range : term.getRanges()) {
                if (schema.hasClass(range) && !schema.isClassCompatible(spec.getObjectClassIri(), range)) {
                    issues.add("关系 range 不兼容：" + spec.getPredicateIri() + " -> " + spec.getObjectClassIri());
                }
            }
            if (catalog.compatibleEntityMapping(spec.getSourceId(), spec.getSubjectClassIri()).isEmpty()) {
                issues.add("关系起点缺少兼容实体身份映射：" + spec.getPredicateIri());
            }
            if (catalog.compatibleEntityMapping(spec.getSourceId(), spec.getObjectClassIri()).isEmpty()) {
                issues.add("关系终点缺少兼容实体身份映射：" + spec.getPredicateIri());
            }
        }
        if (!issues.isEmpty()) throw new MappingValidationException(issues);
    }

    @Override
    public void refreshFromOntology(Path mappingFile, OntologySchema schema) {
        requireFile(mappingFile);
        try (InputStream input = Files.newInputStream(mappingFile); Workbook workbook = new XSSFWorkbook(input)) {
            refreshEntities(requireSheet(workbook, "实体映射"), schema);
            refreshProperties(requireSheet(workbook, "属性映射"), schema);
            refreshRelationships(requireSheet(workbook, "关系映射"), schema);
            try (OutputStream output = Files.newOutputStream(mappingFile)) {
                workbook.write(output);
            }
        } catch (IOException ex) {
            throw new MappingExecutionException("字段映射刷新失败：" + mappingFile, ex);
        }
    }

    private List<EntityMappingSpec> loadEntities(Sheet sheet, OntologySchema schema) {
        requireHeaders(sheet, List.of("实体类", "权威数据源 sourceId", "源记录类型/表/Sheet", "业务主键", "UID规则"));
        List<EntityMappingSpec> out = new ArrayList<>();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i); if (row == null) continue;
            String rawClass = cell(row, 0);
            if (rawClass.isBlank() || MappingIriResolver.isPending(rawClass)) continue;
            String sourceId = cell(row, 1), sourceObject = cell(row, 2), businessKey = cell(row, 3), uidRule = cell(row, 4);
            if (containsPending(sourceId, sourceObject, businessKey, uidRule)) continue;
            String classIri = MappingIriResolver.resolve(rawClass, schema.getClasses());
            out.add(new EntityMappingSpec(classIri, sourceId, sourceObject, businessKey, uidRule));
        }
        return out;
    }

    private List<PropertyMappingSpec> loadProperties(Sheet sheet, OntologySchema schema) {
        requireHeaders(sheet, List.of("实体类", "本体属性", "中文含义", "sourceId", "源对象", "源字段/路径", "必要转换", "是否必填"));
        List<PropertyMappingSpec> out = new ArrayList<>();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i); if (row == null) continue;
            String rawClass = cell(row, 0), rawProperty = cell(row, 1);
            if (rawProperty.isBlank() || MappingIriResolver.isPending(rawProperty)) continue;
            String label = cell(row, 2), sourceId = cell(row, 3), sourceObject = cell(row, 4), sourcePath = cell(row, 5), transform = cell(row, 6), required = cell(row, 7);
            if (containsPending(sourceId, sourceObject, sourcePath)) continue;
            String classIri = MappingIriResolver.resolve(rawClass, schema.getClasses());
            String propertyIri = MappingIriResolver.resolve(rawProperty, schema.getDatatypeProperties());
            out.add(new PropertyMappingSpec(classIri, propertyIri, label, sourceId, sourceObject, sourcePath, transform, parseBoolean(required)));
        }
        return out;
    }

    private List<RelationshipMappingSpec> loadRelationships(Sheet sheet, OntologySchema schema) {
        requireHeaders(sheet, List.of("关系类型", "起点类", "终点类", "sourceId", "起点定位字段", "终点定位字段", "说明"));
        List<RelationshipMappingSpec> out = new ArrayList<>();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i); if (row == null) continue;
            String rawPredicate = cell(row, 0), rawSubject = cell(row, 1), rawObject = cell(row, 2);
            if (rawPredicate.isBlank() || MappingIriResolver.isPending(rawPredicate)) continue;
            String sourceId = cell(row, 3), subjectLocator = cell(row, 4), objectLocator = cell(row, 5), note = cell(row, 6);
            if (containsPending(sourceId, subjectLocator, objectLocator)) continue;
            out.add(new RelationshipMappingSpec(
                    MappingIriResolver.resolve(rawPredicate, schema.getObjectProperties()),
                    MappingIriResolver.resolve(rawSubject, schema.getClasses()),
                    MappingIriResolver.resolve(rawObject, schema.getClasses()),
                    sourceId, subjectLocator, objectLocator, note));
        }
        return out;
    }

    private void refreshEntities(Sheet sheet, OntologySchema schema) {
        Set<String> existing = new LinkedHashSet<>();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i); if (row != null) existing.add(MappingIriResolver.resolve(cell(row, 0), schema.getClasses()));
        }
        for (OntologyTerm term : schema.getClasses().values()) {
            if (existing.contains(term.getIri())) continue;
            Row row = sheet.createRow(sheet.getLastRowNum() + 1);
            row.createCell(0).setCellValue(term.getIri());
            for (int c = 1; c <= 4; c++) row.createCell(c).setCellValue(PENDING);
        }
    }

    private void refreshProperties(Sheet sheet, OntologySchema schema) {
        Set<String> existing = new LinkedHashSet<>();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i); if (row != null) existing.add(MappingIriResolver.resolve(cell(row, 1), schema.getDatatypeProperties()));
        }
        for (OntologyTerm term : schema.getDatatypeProperties().values()) {
            if (existing.contains(term.getIri())) continue;
            Row row = sheet.createRow(sheet.getLastRowNum() + 1);
            row.createCell(0).setCellValue(term.getDomains().size() == 1 ? term.getDomains().iterator().next() : "");
            row.createCell(1).setCellValue(term.getIri());
            row.createCell(2).setCellValue(term.getLabel() == null ? "" : term.getLabel());
            row.createCell(3).setCellValue(PENDING);
            row.createCell(4).setCellValue(PENDING);
            row.createCell(5).setCellValue(PENDING);
            row.createCell(6).setCellValue("");
            row.createCell(7).setCellValue("");
        }
    }

    private void refreshRelationships(Sheet sheet, OntologySchema schema) {
        Set<String> existing = new LinkedHashSet<>();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i); if (row != null) existing.add(MappingIriResolver.resolve(cell(row, 0), schema.getObjectProperties()));
        }
        for (OntologyTerm term : schema.getObjectProperties().values()) {
            if (existing.contains(term.getIri())) continue;
            Row row = sheet.createRow(sheet.getLastRowNum() + 1);
            row.createCell(0).setCellValue(term.getIri());
            row.createCell(1).setCellValue(term.getDomains().size() == 1 ? term.getDomains().iterator().next() : "");
            row.createCell(2).setCellValue(term.getRanges().size() == 1 ? term.getRanges().iterator().next() : "");
            row.createCell(3).setCellValue(PENDING);
            row.createCell(4).setCellValue(PENDING);
            row.createCell(5).setCellValue(PENDING);
            row.createCell(6).setCellValue(term.getLabel() == null ? "" : term.getLabel());
        }
    }

    private void requireHeaders(Sheet sheet, List<String> expected) {
        Row row = sheet.getRow(0);
        if (row == null) throw new MappingExecutionException("映射表缺少表头：" + sheet.getSheetName());
        for (int i = 0; i < expected.size(); i++) {
            if (!expected.get(i).equals(cell(row, i))) {
                throw new MappingExecutionException(sheet.getSheetName() + " 表头不符合约定，第 " + (i + 1) + " 列应为：" + expected.get(i));
            }
        }
    }

    private Sheet requireSheet(Workbook workbook, String name) {
        Sheet sheet = workbook.getSheet(name);
        if (sheet == null) throw new MappingExecutionException("字段映射文件缺少工作表：" + name);
        return sheet;
    }

    private void requireFile(Path path) {
        if (path == null || !Files.isRegularFile(path)) throw new MappingExecutionException("字段映射文件不存在：" + path);
    }

    private String cell(Row row, int column) {
        Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }

    private boolean containsPending(String... values) {
        for (String value : values) if (MappingIriResolver.isPending(value)) return true;
        return false;
    }

    private boolean parseBoolean(String value) {
        String v = value == null ? "" : value.trim();
        return "true".equalsIgnoreCase(v) || "是".equals(v) || "1".equals(v) || "yes".equalsIgnoreCase(v);
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
}
