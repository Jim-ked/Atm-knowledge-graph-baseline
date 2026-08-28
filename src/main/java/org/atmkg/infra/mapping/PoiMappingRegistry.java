package org.atmkg.infra.mapping;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
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
import org.atmkg.core.model.mapping.MappingInspection;
import org.atmkg.core.model.mapping.MappingIssue;
import org.atmkg.core.model.mapping.MappingScope;
import org.atmkg.core.model.mapping.MappingValidationReport;
import org.atmkg.core.model.mapping.PropertyMappingSpec;
import org.atmkg.core.model.mapping.RelationshipMappingSpec;
import org.atmkg.core.spi.MappingRegistry;

/** Loads and validates the single current mapping workbook contract. */
public final class PoiMappingRegistry implements MappingRegistry {
    private final DataFormatter formatter = new DataFormatter();

    @Override
    public MappingCatalog load(Path mappingFile, OntologySchema schema) {
        requireFile(mappingFile);
        try (InputStream input = Files.newInputStream(mappingFile); Workbook workbook = new XSSFWorkbook(input)) {
            List<EntityMappingSpec> entities = loadEntities(
                    requireSheet(workbook, MappingWorkbookFormat.ENTITY_SHEET), schema);
            List<PropertyMappingSpec> properties = loadProperties(
                    requireSheet(workbook, MappingWorkbookFormat.PROPERTY_SHEET), schema);
            List<RelationshipMappingSpec> relationships = loadRelationships(
                    requireSheet(workbook, MappingWorkbookFormat.RELATIONSHIP_SHEET), schema);
            MappingCatalog catalog = new MappingCatalog(entities, properties, relationships);
            validate(catalog, schema);
            return catalog;
        } catch (IOException ex) {
            throw new MappingExecutionException("字段映射文件读取失败：" + mappingFile, ex);
        }
    }

    /** Parse the workbook while isolating semantic errors to their source scope. */
    public MappingInspection inspect(Path mappingFile, OntologySchema schema) {
        requireFile(mappingFile);
        try (InputStream input = Files.newInputStream(mappingFile); Workbook workbook = new XSSFWorkbook(input)) {
            Sheet entitiesSheet = requireSheet(workbook, MappingWorkbookFormat.ENTITY_SHEET);
            Sheet propertiesSheet = requireSheet(workbook, MappingWorkbookFormat.PROPERTY_SHEET);
            Sheet relationshipsSheet = requireSheet(workbook, MappingWorkbookFormat.RELATIONSHIP_SHEET);
            requireHeaders(entitiesSheet, MappingWorkbookFormat.ENTITY_HEADERS);
            requireHeaders(propertiesSheet, MappingWorkbookFormat.PROPERTY_HEADERS);
            requireHeaders(relationshipsSheet, MappingWorkbookFormat.RELATIONSHIP_HEADERS);
            List<MappingIssue> issues = new ArrayList<>();
            Set<MappingScope> scopes = new java.util.LinkedHashSet<>();
            List<EntityMappingSpec> entities = inspectEntities(entitiesSheet, schema, issues, scopes);
            List<PropertyMappingSpec> properties = inspectProperties(propertiesSheet, schema, issues, scopes);
            List<RelationshipMappingSpec> relationships = inspectRelationships(relationshipsSheet, schema, issues, scopes);
            MappingCatalog parsed = new MappingCatalog(entities, properties, relationships);
            addSemanticIssues(parsed, schema, issues, entitiesSheet, propertiesSheet, relationshipsSheet);
            MappingValidationReport report = new MappingValidationReport(issues, scopes);
            Set<MappingScope> invalid = scopes.stream()
                    .filter(scope -> report.status(scope) == org.atmkg.core.model.mapping.MappingScopeStatus.INVALID)
                    .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
            MappingCatalog valid = new MappingCatalog(
                    entities.stream().filter(e -> !invalid.contains(new MappingScope(e.getSourceId(), e.getSourceObject()))).toList(),
                    properties.stream().filter(p -> !invalid.contains(new MappingScope(p.getSourceId(), p.getSourceObject()))).toList(),
                    relationships.stream().filter(r -> !invalid.contains(new MappingScope(r.getSourceId(), r.getSourceObject()))).toList());
            return new MappingInspection(valid, report);
        } catch (IOException ex) {
            throw new MappingExecutionException("字段映射文件读取失败：" + mappingFile, ex);
        }
    }

    private List<EntityMappingSpec> inspectEntities(Sheet sheet, OntologySchema schema,
                                                    List<MappingIssue> issues, Set<MappingScope> scopes) {
        List<EntityMappingSpec> out = new ArrayList<>();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i); if (row == null || rowBlank(row)) continue;
            String sourceId = cell(row, 0), object = cell(row, 1), rawClass = cell(row, 2), key = cell(row, 3);
            registerScope(scopes, sourceId, object);
            boolean rowValid = true;
            if (sourceId.isBlank() || object.isBlank() || key.isBlank()) {
                issue(issues, sheet, i, sourceId, object, "实体映射", "实体映射缺少 sourceId/sourceObject/businessKey");
                rowValid = false;
            }
            if (rawClass.isBlank()) { issue(issues, sheet, i, sourceId, object, "实体映射", "实体类不能为空"); continue; }
            String classIri = MappingIriResolver.resolve(rawClass, schema.getClasses());
            if (!schema.hasClass(classIri)) { issue(issues, sheet, i, sourceId, object, "实体映射", "未知实体类 " + rawClass, classIri, "", key); rowValid = false; }
            if (rowValid) out.add(new EntityMappingSpec(classIri, sourceId, object, key));
        }
        return out;
    }

    private List<PropertyMappingSpec> inspectProperties(Sheet sheet, OntologySchema schema,
                                                        List<MappingIssue> issues, Set<MappingScope> scopes) {
        List<PropertyMappingSpec> out = new ArrayList<>();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i); if (row == null || rowBlank(row)) continue;
            String sourceId = cell(row, 0), object = cell(row, 1), rawClass = cell(row, 2), path = cell(row, 3), rawProperty = cell(row, 4);
            registerScope(scopes, sourceId, object);
            boolean rowValid = true;
            if (sourceId.isBlank() || object.isBlank() || rawClass.isBlank() || path.isBlank() || rawProperty.isBlank()) {
                issue(issues, sheet, i, sourceId, object, "属性映射", "属性映射缺少 sourceId/sourceObject/class/sourcePath/property", rawClass, rawProperty, path);
                rowValid = false;
            }
            if (sourceId.isBlank() && object.isBlank() && rawClass.isBlank() && path.isBlank() && rawProperty.isBlank()) continue;
            String classIri = MappingIriResolver.resolve(rawClass, schema.getClasses());
            String propertyIri = MappingIriResolver.resolve(rawProperty, schema.getDatatypeProperties());
            if (!schema.hasClass(classIri)) { issue(issues, sheet, i, sourceId, object, "属性映射", "属性映射引用未知实体类 " + rawClass, classIri, propertyIri, path); rowValid = false; }
            if (!schema.hasDatatypeProperty(propertyIri)) { issue(issues, sheet, i, sourceId, object, "属性映射", "未知数据属性 " + rawProperty, classIri, rawProperty, path); rowValid = false; }
            if (rowValid) out.add(new PropertyMappingSpec(classIri, propertyIri, sourceId, object, path, cell(row, 5), parseBoolean(cell(row, 6))));
        }
        return out;
    }

    private List<RelationshipMappingSpec> inspectRelationships(Sheet sheet, OntologySchema schema,
                                                                List<MappingIssue> issues, Set<MappingScope> scopes) {
        List<RelationshipMappingSpec> out = new ArrayList<>();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i); if (row == null || rowBlank(row)) continue;
            String sourceId = cell(row, 0), object = cell(row, 1), rawPredicate = cell(row, 2), rawSubject = cell(row, 3), rawObject = cell(row, 5);
            registerScope(scopes, sourceId, object);
            boolean rowValid = true;
            if (sourceId.isBlank() || object.isBlank() || rawPredicate.isBlank() || rawSubject.isBlank()
                    || cell(row, 4).isBlank() || rawObject.isBlank() || cell(row, 6).isBlank()) {
                issue(issues, sheet, i, sourceId, object, "关系映射", "关系映射缺少 sourceId/sourceObject/predicate/subjectClass/subjectLocator/objectClass/objectLocator", rawSubject, rawPredicate, cell(row, 4));
                rowValid = false;
            }
            if (sourceId.isBlank() && object.isBlank() && rawPredicate.isBlank() && rawSubject.isBlank()
                    && cell(row, 4).isBlank() && rawObject.isBlank() && cell(row, 6).isBlank()) continue;
            String predicate = MappingIriResolver.resolve(rawPredicate, schema.getObjectProperties());
            String subject = MappingIriResolver.resolve(rawSubject, schema.getClasses());
            String objectClass = MappingIriResolver.resolve(rawObject, schema.getClasses());
            if (!schema.hasObjectProperty(predicate)) { issue(issues, sheet, i, sourceId, object, "关系映射", "未知对象属性 " + rawPredicate, subject, rawPredicate, cell(row, 4)); rowValid = false; }
            if (!schema.hasClass(subject)) { issue(issues, sheet, i, sourceId, object, "关系映射", "关系起点类不存在 " + rawSubject, subject, predicate, cell(row, 4)); rowValid = false; }
            if (!schema.hasClass(objectClass)) { issue(issues, sheet, i, sourceId, object, "关系映射", "关系终点类不存在 " + rawObject, objectClass, predicate, cell(row, 6)); rowValid = false; }
            if (rowValid) out.add(new RelationshipMappingSpec(predicate, subject, objectClass, sourceId, object, cell(row, 4), cell(row, 6), cell(row, 7)));
        }
        return out;
    }

    private void addSemanticIssues(MappingCatalog catalog, OntologySchema schema, List<MappingIssue> issues,
                                   Sheet entities, Sheet properties, Sheet relationships) {
        // Semantic checks are retained by validate(); inspection adds located issues for domain/range and duplicates.
        for (int i = 1; i <= properties.getLastRowNum(); i++) {
            Row row = properties.getRow(i); if (row == null || rowBlank(row)) continue;
            String sourceId = cell(row, 0), object = cell(row, 1), rawClass = cell(row, 2), rawProperty = cell(row, 4);
            String cls = MappingIriResolver.resolve(rawClass, schema.getClasses());
            String prop = MappingIriResolver.resolve(rawProperty, schema.getDatatypeProperties());
            OntologyTerm term = schema.getDatatypeProperties().get(prop);
            if (term == null || !schema.hasClass(cls)) continue;
            boolean hasEntityMapping = catalog.getEntities().stream().anyMatch(entity ->
                    sourceId.equals(entity.getSourceId()) && object.equals(entity.getSourceObject())
                            && cls.equals(entity.getClassIri()));
            if (!hasEntityMapping) {
                issue(issues, properties, i, sourceId, object, "属性映射",
                        "同一 scope/class 缺少实体映射，属性不会被投影", cls, prop, cell(row, 3));
            }
            for (String domain : term.getDomains()) if (schema.hasClass(domain) && !schema.isClassCompatible(cls, domain))
                issue(issues, properties, i, sourceId, object, "属性映射", "属性 domain 不兼容", cls, prop, cell(row, 3), MappingIriResolver.compact(domain), MappingIriResolver.compact(cls));
        }
        for (int i = 1; i <= relationships.getLastRowNum(); i++) {
            Row row = relationships.getRow(i); if (row == null || rowBlank(row)) continue;
            String sourceId = cell(row, 0), object = cell(row, 1), pred = MappingIriResolver.resolve(cell(row, 2), schema.getObjectProperties());
            String subject = MappingIriResolver.resolve(cell(row, 3), schema.getClasses());
            String objectClass = MappingIriResolver.resolve(cell(row, 5), schema.getClasses());
            OntologyTerm term = schema.getObjectProperties().get(pred); if (term == null) continue;
            for (String domain : term.getDomains()) if (schema.hasClass(domain) && !schema.isClassCompatible(subject, domain))
                issue(issues, relationships, i, sourceId, object, "关系映射", "关系 domain 不兼容", subject, pred, cell(row, 4), MappingIriResolver.compact(domain), MappingIriResolver.compact(subject));
            for (String range : term.getRanges()) if (schema.hasClass(range) && !schema.isClassCompatible(objectClass, range))
                issue(issues, relationships, i, sourceId, object, "关系映射", "关系 range 不兼容", objectClass, pred, cell(row, 6), MappingIriResolver.compact(range), MappingIriResolver.compact(objectClass));
        }
        Set<String> seen = new HashSet<>();
        for (int i = 1; i <= entities.getLastRowNum(); i++) { Row row = entities.getRow(i); if (row == null || rowBlank(row)) continue;
            String key = cell(row,0)+"|"+cell(row,1)+"|"+MappingIriResolver.resolve(cell(row,2), schema.getClasses());
            if (!seen.add(key)) issue(issues, entities, i, cell(row,0), cell(row,1), "实体映射", "实体映射重复："+key);
        }
    }

    private void registerScope(Set<MappingScope> scopes, String sourceId, String object) {
        if (!sourceId.isBlank() && !object.isBlank()) scopes.add(new MappingScope(sourceId, object));
    }

    private void issue(List<MappingIssue> issues, Sheet sheet, int rowIndex, String sourceId, String object,
                       String kind, String message) { issue(issues, sheet, rowIndex, sourceId, object, kind, message, "", "", "", "", ""); }
    private void issue(List<MappingIssue> issues, Sheet sheet, int rowIndex, String sourceId, String object,
                       String kind, String message, String cls, String term, String path) { issue(issues, sheet, rowIndex, sourceId, object, kind, message, cls, term, path, "", ""); }
    private void issue(List<MappingIssue> issues, Sheet sheet, int rowIndex, String sourceId, String object,
                       String kind, String message, String cls, String term, String path, String expected, String actual) {
        issues.add(new MappingIssue(sheet.getSheetName(), rowIndex + 1, sourceId, object, kind, message, cls, term, path, expected, actual));
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
            if (blank(spec.getSourceId()) || blank(spec.getSourceObject())
                    || blank(spec.getSubjectLocator()) || blank(spec.getObjectLocator())) {
                issues.add("关系映射缺少 sourceId/sourceObject/引用字段: " + spec.getPredicateIri());
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
        }
        if (!issues.isEmpty()) throw new MappingValidationException(issues);
    }

    @Override
    public void refreshFromOntology(Path mappingFile, OntologySchema schema) {
        requireFile(mappingFile);
        try (InputStream input = Files.newInputStream(mappingFile); Workbook workbook = new XSSFWorkbook(input)) {
            requireHeaders(requireSheet(workbook, MappingWorkbookFormat.ENTITY_SHEET), MappingWorkbookFormat.ENTITY_HEADERS);
            requireHeaders(requireSheet(workbook, MappingWorkbookFormat.PROPERTY_SHEET), MappingWorkbookFormat.PROPERTY_HEADERS);
            requireHeaders(requireSheet(workbook, MappingWorkbookFormat.RELATIONSHIP_SHEET),
                    MappingWorkbookFormat.RELATIONSHIP_HEADERS);
            rebuildOntologyReference(workbook, schema);
            MappingWorkbookFormat.applyEditingFeatures(workbook);
            try (OutputStream output = Files.newOutputStream(mappingFile)) {
                workbook.write(output);
            }
        } catch (IOException ex) {
            throw new MappingExecutionException("字段映射刷新失败：" + mappingFile, ex);
        }
    }

    private List<EntityMappingSpec> loadEntities(Sheet sheet, OntologySchema schema) {
        requireHeaders(sheet, MappingWorkbookFormat.ENTITY_HEADERS);
        List<EntityMappingSpec> out = new ArrayList<>();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null || rowBlank(row)) continue;
            String rawClass = cell(row, 2);
            if (rawClass.isBlank()) continue;
            out.add(new EntityMappingSpec(
                    MappingIriResolver.resolve(rawClass, schema.getClasses()),
                    cell(row, 0), cell(row, 1), cell(row, 3)));
        }
        return out;
    }

    private List<PropertyMappingSpec> loadProperties(Sheet sheet, OntologySchema schema) {
        requireHeaders(sheet, MappingWorkbookFormat.PROPERTY_HEADERS);
        List<PropertyMappingSpec> out = new ArrayList<>();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null || rowBlank(row)) continue;
            String rawClass = cell(row, 2), rawProperty = cell(row, 4);
            if (rawProperty.isBlank()) continue;
            out.add(new PropertyMappingSpec(
                    MappingIriResolver.resolve(rawClass, schema.getClasses()),
                    MappingIriResolver.resolve(rawProperty, schema.getDatatypeProperties()),
                    cell(row, 0), cell(row, 1), cell(row, 3), cell(row, 5), parseBoolean(cell(row, 6))));
        }
        return out;
    }

    private List<RelationshipMappingSpec> loadRelationships(Sheet sheet, OntologySchema schema) {
        requireHeaders(sheet, MappingWorkbookFormat.RELATIONSHIP_HEADERS);
        List<RelationshipMappingSpec> out = new ArrayList<>();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null || rowBlank(row)) continue;
            String rawPredicate = cell(row, 2);
            if (rawPredicate.isBlank()) continue;
            out.add(new RelationshipMappingSpec(
                    MappingIriResolver.resolve(rawPredicate, schema.getObjectProperties()),
                    MappingIriResolver.resolve(cell(row, 3), schema.getClasses()),
                    MappingIriResolver.resolve(cell(row, 5), schema.getClasses()),
                    cell(row, 0), cell(row, 1), cell(row, 4), cell(row, 6), cell(row, 7)));
        }
        return out;
    }

    private void rebuildOntologyReference(Workbook workbook, OntologySchema schema) {
        int previousIndex = workbook.getSheetIndex(MappingWorkbookFormat.REFERENCE_SHEET);
        if (previousIndex >= 0) workbook.removeSheetAt(previousIndex);
        Sheet sheet = workbook.createSheet(MappingWorkbookFormat.REFERENCE_SHEET);
        if (previousIndex >= 0) workbook.setSheetOrder(MappingWorkbookFormat.REFERENCE_SHEET, previousIndex);
        MappingWorkbookFormat.writeHeader(sheet, MappingWorkbookFormat.REFERENCE_HEADERS);
        appendTerms(sheet, "Class", schema.getClasses().values());
        appendTerms(sheet, "DatatypeProperty", schema.getDatatypeProperties().values());
        appendTerms(sheet, "ObjectProperty", schema.getObjectProperties().values());
        MappingWorkbookFormat.configureReferenceSheet(sheet);
    }

    private void appendTerms(Sheet sheet, String type, Iterable<OntologyTerm> terms) {
        List<OntologyTerm> sorted = new ArrayList<>();
        terms.forEach(sorted::add);
        sorted.sort(Comparator.comparing(OntologyTerm::getIri));
        for (OntologyTerm term : sorted) {
            Row row = sheet.createRow(sheet.getLastRowNum() + 1);
            row.createCell(0).setCellValue(type);
            row.createCell(1).setCellValue(MappingIriResolver.compact(term.getIri()));
            row.createCell(2).setCellValue(term.getLabel() == null ? "" : term.getLabel());
            row.createCell(3).setCellValue(compactValues(term.getDomains()));
            row.createCell(4).setCellValue(compactValues(term.getRanges()));
            row.createCell(5).setCellValue(term.getIri());
        }
    }

    private String compactValues(Set<String> values) {
        return values.stream().map(MappingIriResolver::compact).sorted().collect(Collectors.joining(", "));
    }

    private void requireHeaders(Sheet sheet, List<String> expected) {
        Row row = sheet.getRow(0);
        if (row == null) throw new MappingExecutionException("映射表缺少表头：" + sheet.getSheetName());
        for (int i = 0; i < expected.size(); i++) {
            if (!expected.get(i).equals(cell(row, i))) {
                throw new MappingExecutionException(sheet.getSheetName() + " 表头不符合约定，第 "
                        + (i + 1) + " 列应为：" + expected.get(i));
            }
        }
        for (int i = expected.size(); i < row.getLastCellNum(); i++) {
            if (!cell(row, i).isBlank()) {
                throw new MappingExecutionException(sheet.getSheetName() + " 表头包含未约定列：" + cell(row, i));
            }
        }
    }

    private Sheet requireSheet(Workbook workbook, String name) {
        Sheet sheet = workbook.getSheet(name);
        if (sheet == null) throw new MappingExecutionException("字段映射文件缺少工作表：" + name);
        return sheet;
    }

    private void requireFile(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            throw new MappingExecutionException("字段映射文件不存在：" + path);
        }
    }

    private String cell(Row row, int column) {
        Cell value = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        return value == null ? "" : formatter.formatCellValue(value).trim();
    }

    private boolean rowBlank(Row row) {
        for (int i = 0; i < row.getLastCellNum(); i++) if (!cell(row, i).isBlank()) return false;
        return true;
    }

    private boolean parseBoolean(String value) {
        String normalized = value == null ? "" : value.trim();
        return "true".equalsIgnoreCase(normalized) || "是".equals(normalized) || "1".equals(normalized)
                || "yes".equalsIgnoreCase(normalized);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
