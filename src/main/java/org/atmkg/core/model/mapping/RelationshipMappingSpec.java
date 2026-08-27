package org.atmkg.core.model.mapping;

public final class RelationshipMappingSpec {
    private final String predicateIri;
    private final String subjectClassIri;
    private final String objectClassIri;
    private final String sourceId;
    private final String sourceObject;
    private final String subjectLocator;
    private final String objectLocator;
    private final String note;

    public RelationshipMappingSpec(String predicateIri, String subjectClassIri, String objectClassIri,
                                   String sourceId, String sourceObject, String subjectLocator,
                                   String objectLocator, String note) {
        this.predicateIri = predicateIri;
        this.subjectClassIri = subjectClassIri;
        this.objectClassIri = objectClassIri;
        this.sourceId = sourceId;
        this.sourceObject = sourceObject;
        this.subjectLocator = subjectLocator;
        this.objectLocator = objectLocator;
        this.note = note;
    }

    public String getPredicateIri() { return predicateIri; }
    public String getSubjectClassIri() { return subjectClassIri; }
    public String getObjectClassIri() { return objectClassIri; }
    public String getSourceId() { return sourceId; }
    public String getSourceObject() { return sourceObject; }
    public String getSubjectLocator() { return subjectLocator; }
    public String getObjectLocator() { return objectLocator; }
    public String getNote() { return note; }
}
