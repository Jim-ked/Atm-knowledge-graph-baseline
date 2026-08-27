package org.atmkg.core.model.mapping;

public final class PropertyMappingSpec {
    private final String classIri;
    private final String propertyIri;
    private final String sourceId;
    private final String sourceObject;
    private final String sourcePath;
    private final String transform;
    private final boolean required;

    public PropertyMappingSpec(String classIri, String propertyIri, String sourceId,
                               String sourceObject, String sourcePath, String transform, boolean required) {
        this.classIri = classIri;
        this.propertyIri = propertyIri;
        this.sourceId = sourceId;
        this.sourceObject = sourceObject;
        this.sourcePath = sourcePath;
        this.transform = transform;
        this.required = required;
    }

    public String getClassIri() { return classIri; }
    public String getPropertyIri() { return propertyIri; }
    public String getSourceId() { return sourceId; }
    public String getSourceObject() { return sourceObject; }
    public String getSourcePath() { return sourcePath; }
    public String getTransform() { return transform; }
    public boolean isRequired() { return required; }
}
