package org.atmkg.infra.mapping;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.atmkg.core.model.OntologySchema;
import org.atmkg.core.model.OntologyTerm;
import org.atmkg.core.model.mapping.EntityMappingSpec;
import org.atmkg.core.model.mapping.MappingCatalog;
import org.atmkg.core.model.mapping.PropertyMappingSpec;

/** Strict, human-confirmed mapping suggestions for the existing workbook contract. */
public final class MappingAssist {
    private MappingAssist() {}

    public static Analysis analyze(List<String> sourcePaths, String selectedClass, OntologySchema schema) {
        Objects.requireNonNull(sourcePaths, "sourcePaths");
        Objects.requireNonNull(schema, "schema");
        String classIri = MappingIriResolver.resolve(selectedClass, schema.getClasses());
        if (!schema.hasClass(classIri)) throw new IllegalArgumentException("本体中不存在实体 Class：" + selectedClass);

        List<OntologyTerm> eligible = schema.getDatatypeProperties().values().stream()
                .filter(property -> domainCompatible(classIri, property, schema))
                .sorted(Comparator.comparing(OntologyTerm::getIri))
                .toList();
        List<PropertyCandidate> candidates = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        List<AmbiguousProperty> ambiguous = new ArrayList<>();
        Set<String> uniquePaths = new LinkedHashSet<>();
        for (String rawPath : sourcePaths) {
            String sourcePath = MappingIriResolver.trim(rawPath);
            if (sourcePath.isEmpty() || "__sourceKey".equals(sourcePath) || !uniquePaths.add(sourcePath)) continue;
            String leafName = leafName(sourcePath);
            List<OntologyTerm> matches = eligible.stream()
                    .filter(property -> strictNameEquals(leafName, MappingIriResolver.compact(property.getIri()))
                            || strictNameEquals(leafName, property.getLabel()))
                    .toList();
            if (matches.isEmpty()) unmatched.add(sourcePath);
            else if (matches.size() == 1) candidates.add(new PropertyCandidate(sourcePath, matches.get(0)));
            else ambiguous.add(new AmbiguousProperty(sourcePath, matches));
        }
        return new Analysis(candidates, unmatched, ambiguous);
    }

    public static WriteResult write(Path mappingFile, String sourceId, String sourceObject,
                                    String selectedClass, String businessKey, Analysis analysis,
                                    OntologySchema schema) {
        Objects.requireNonNull(mappingFile, "mappingFile");
        Objects.requireNonNull(analysis, "analysis");
        Objects.requireNonNull(schema, "schema");
        String normalizedSourceId = required(sourceId, "sourceId");
        String normalizedObject = required(sourceObject, "sourceObject");
        String normalizedBusinessKey = required(businessKey, "业务主键字段");
        String classIri = MappingIriResolver.resolve(required(selectedClass, "实体 Class"), schema.getClasses());
        if (!schema.hasClass(classIri)) throw new IllegalArgumentException("本体中不存在实体 Class：" + selectedClass);

        Path target = mappingFile.toAbsolutePath().normalize();
        PoiMappingRegistry registry = new PoiMappingRegistry();
        MappingCatalog existing = registry.inspect(target, schema).validCatalog();
        boolean identicalEntity = existing.getEntities().stream().anyMatch(entity ->
                sameEntityScope(entity, normalizedSourceId, normalizedObject, classIri)
                        && normalizedBusinessKey.equals(entity.getBusinessKey()));
        boolean entityScopeExists = existing.getEntities().stream().anyMatch(entity ->
                sameEntityScope(entity, normalizedSourceId, normalizedObject, classIri));
        boolean entityAdded = !identicalEntity && !entityScopeExists;
        if (entityScopeExists && !identicalEntity) {
            return new WriteResult(false, 0, 0, true);
        }

        Set<String> existingPropertyPaths = new LinkedHashSet<>();
        for (PropertyMappingSpec property : existing.getProperties()) {
            if (normalizedSourceId.equals(property.getSourceId())
                    && normalizedObject.equals(property.getSourceObject())
                    && classIri.equals(property.getClassIri())) {
                existingPropertyPaths.add(property.getSourcePath());
            }
        }
        List<PropertyCandidate> additions = analysis.candidates().stream()
                .filter(candidate -> !existingPropertyPaths.contains(candidate.sourcePath()))
                .toList();
        int skipped = analysis.candidates().size() - additions.size();
        if (!entityAdded && additions.isEmpty()) {
            return new WriteResult(false, 0, skipped, entityScopeExists && !identicalEntity);
        }

        Path temporary = null;
        try {
            Path parent = target.getParent();
            if (parent == null) throw new IllegalArgumentException("无法确定 Mapping 文件目录：" + target);
            temporary = Files.createTempFile(parent, "mapping-assist-", ".xlsx");
            try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(target));
                 OutputStream output = Files.newOutputStream(temporary)) {
                if (entityAdded) appendEntity(workbook, normalizedSourceId, normalizedObject,
                        classIri, normalizedBusinessKey);
                for (PropertyCandidate candidate : additions) {
                    appendProperty(workbook, normalizedSourceId, normalizedObject, classIri, candidate);
                }
                MappingWorkbookFormat.applyEditingFeatures(workbook);
                workbook.write(output);
            }
            var inspection = registry.inspect(temporary, schema);
            if (!inspection.report().issuesFor(new org.atmkg.core.model.mapping.MappingScope(
                    normalizedSourceId, normalizedObject)).isEmpty()) {
                throw new IllegalStateException("生成的 Mapping scope 校验失败");
            }
            replace(temporary, target);
            temporary = null;
            registry.inspect(target, schema);
            return new WriteResult(entityAdded, additions.size(), skipped,
                    entityScopeExists && !identicalEntity);
        } catch (IOException ex) {
            throw new IllegalStateException("写入 Mapping 工作簿失败：" + target, ex);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Preserve the original failure; the target workbook was not replaced.
                }
            }
        }
    }

    public static String localName(String iri) {
        return MappingIriResolver.compact(iri);
    }

    private static boolean domainCompatible(String selectedClass, OntologyTerm property,
                                            OntologySchema schema) {
        return property.getDomains().isEmpty() || property.getDomains().stream()
                .anyMatch(domain -> schema.isClassCompatible(selectedClass, domain));
    }

    private static boolean strictNameEquals(String left, String right) {
        String first = MappingIriResolver.trim(left);
        String second = MappingIriResolver.trim(right);
        if (first.length() != second.length()) return false;
        for (int i = 0; i < first.length(); i++) {
            char a = first.charAt(i);
            char b = second.charAt(i);
            if (a == b) continue;
            if (asciiLetter(a) && asciiLetter(b)
                    && Character.toLowerCase(a) == Character.toLowerCase(b)) continue;
            return false;
        }
        return true;
    }

    private static boolean asciiLetter(char value) {
        return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z';
    }

    private static String leafName(String sourcePath) {
        int separator = sourcePath.lastIndexOf('.');
        return separator >= 0 ? sourcePath.substring(separator + 1) : sourcePath;
    }

    private static boolean sameEntityScope(EntityMappingSpec entity, String sourceId,
                                           String sourceObject, String classIri) {
        return sourceId.equals(entity.getSourceId())
                && sourceObject.equals(entity.getSourceObject())
                && classIri.equals(entity.getClassIri());
    }

    private static void appendEntity(XSSFWorkbook workbook, String sourceId, String sourceObject,
                                     String classIri, String businessKey) {
        Sheet sheet = workbook.getSheet(MappingWorkbookFormat.ENTITY_SHEET);
        Row row = sheet.createRow(sheet.getLastRowNum() + 1);
        write(row, sourceId, sourceObject, MappingIriResolver.compact(classIri), businessKey);
    }

    private static void appendProperty(XSSFWorkbook workbook, String sourceId, String sourceObject,
                                       String classIri, PropertyCandidate candidate) {
        Sheet sheet = workbook.getSheet(MappingWorkbookFormat.PROPERTY_SHEET);
        Row row = sheet.createRow(sheet.getLastRowNum() + 1);
        write(row, sourceId, sourceObject, MappingIriResolver.compact(classIri), candidate.sourcePath(),
                MappingIriResolver.compact(candidate.property().getIri()), "", "");
    }

    private static void write(Row row, String... values) {
        for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
    }

    private static void replace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String required(String value, String name) {
        String normalized = MappingIriResolver.trim(value);
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " 不能为空");
        return normalized;
    }

    public record PropertyCandidate(String sourcePath, OntologyTerm property) {
        public PropertyCandidate {
            sourcePath = required(sourcePath, "源字段路径");
            Objects.requireNonNull(property, "property");
        }
    }

    public record AmbiguousProperty(String sourcePath, List<OntologyTerm> properties) {
        public AmbiguousProperty {
            sourcePath = required(sourcePath, "源字段路径");
            properties = List.copyOf(properties);
        }
    }

    public record Analysis(List<PropertyCandidate> candidates, List<String> unmatched,
                           List<AmbiguousProperty> ambiguous) {
        public Analysis {
            candidates = List.copyOf(candidates);
            unmatched = List.copyOf(unmatched);
            ambiguous = List.copyOf(ambiguous);
        }
    }

    public record WriteResult(boolean entityAdded, int propertiesAdded, int propertiesSkipped,
                              boolean existingEntityConflict) {}
}
