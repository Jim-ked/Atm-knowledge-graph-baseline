package org.atmkg.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class OntologySchema {
    private final Map<String, OntologyTerm> classes;
    private final Map<String, OntologyTerm> datatypeProperties;
    private final Map<String, OntologyTerm> objectProperties;

    public OntologySchema(Map<String, OntologyTerm> classes,
                          Map<String, OntologyTerm> datatypeProperties,
                          Map<String, OntologyTerm> objectProperties) {
        this.classes = immutable(classes);
        this.datatypeProperties = immutable(datatypeProperties);
        this.objectProperties = immutable(objectProperties);
    }

    private static Map<String, OntologyTerm> immutable(Map<String, OntologyTerm> values) {
        Objects.requireNonNull(values);
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public Map<String, OntologyTerm> getClasses() { return classes; }
    public Map<String, OntologyTerm> getDatatypeProperties() { return datatypeProperties; }
    public Map<String, OntologyTerm> getObjectProperties() { return objectProperties; }

    public boolean hasClass(String iri) { return classes.containsKey(iri); }
    public boolean hasDatatypeProperty(String iri) { return datatypeProperties.containsKey(iri); }
    public boolean hasObjectProperty(String iri) { return objectProperties.containsKey(iri); }

    /** True when child == parent or child transitively inherits from parent. */
    public boolean isClassCompatible(String childIri, String parentIri) {
        if (childIri == null || parentIri == null) return false;
        if (childIri.equals(parentIri)) return true;
        return isSubclassOf(childIri, parentIri, new LinkedHashSet<>());
    }

    private boolean isSubclassOf(String childIri, String parentIri, Set<String> visited) {
        if (!visited.add(childIri)) return false;
        OntologyTerm child = classes.get(childIri);
        if (child == null) return false;
        for (String superClass : child.getSuperClasses()) {
            if (parentIri.equals(superClass) || isSubclassOf(superClass, parentIri, visited)) return true;
        }
        return false;
    }
}
