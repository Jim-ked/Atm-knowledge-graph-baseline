package org.atmkg.core.model.mapping;

public final class EntityMappingSpec {
    private final String classIri;
    private final String sourceId;
    private final String sourceObject;
    private final String businessKey;

    public EntityMappingSpec(String classIri, String sourceId, String sourceObject, String businessKey) {
        this.classIri = classIri;
        this.sourceId = sourceId;
        this.sourceObject = sourceObject;
        this.businessKey = businessKey;
    }

    public String getClassIri() { return classIri; }
    public String getSourceId() { return sourceId; }
    public String getSourceObject() { return sourceObject; }
    public String getBusinessKey() { return businessKey; }
}
