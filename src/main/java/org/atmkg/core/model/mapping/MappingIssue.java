package org.atmkg.core.model.mapping;

import java.util.Objects;

/** One located semantic mapping problem. */
public record MappingIssue(String sheetName, int rowNumber, String sourceId, String sourceObject,
                           String mappingKind, String message, String classIri, String term,
                           String sourcePath, String expected, String actual) {
    public MappingIssue {
        sheetName = Objects.requireNonNullElse(sheetName, "");
        sourceId = Objects.requireNonNullElse(sourceId, "");
        sourceObject = Objects.requireNonNullElse(sourceObject, "");
        mappingKind = Objects.requireNonNullElse(mappingKind, "");
        message = Objects.requireNonNullElse(message, "");
        classIri = Objects.requireNonNullElse(classIri, "");
        term = Objects.requireNonNullElse(term, "");
        sourcePath = Objects.requireNonNullElse(sourcePath, "");
        expected = Objects.requireNonNullElse(expected, "");
        actual = Objects.requireNonNullElse(actual, "");
    }

    public MappingIssue(String sheetName, int rowNumber, String sourceId, String sourceObject,
                        String mappingKind, String message) {
        this(sheetName, rowNumber, sourceId, sourceObject, mappingKind, message,
                "", "", "", "", "");
    }

    public MappingScope scope() {
        return sourceId.isBlank() || sourceObject.isBlank() ? null : new MappingScope(sourceId, sourceObject);
    }

    public String getSheetName() { return sheetName; }
    public int getRowNumber() { return rowNumber; }
    public String getSourceId() { return sourceId; }
    public String getSourceObject() { return sourceObject; }
    public String getMappingKind() { return mappingKind; }
    public String getMessage() { return message; }
    public String getClassIri() { return classIri; }
    public String getTerm() { return term; }
    public String getSourcePath() { return sourcePath; }
    public String getExpected() { return expected; }
    public String getActual() { return actual; }
}
