package org.atmkg.infra.neo4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.atmkg.core.model.OntologySchema;
import org.atmkg.core.model.OntologyTerm;

/**
 * 把当前本体词汇转换为安全、确定的 Neo4j label/type token。
 * 这里只是图存储元数据，不是第二套本体或业务 mapping；普通术语变化仍修改正式 TTL。
 */
public final class Neo4jOntologyMetadata {
    private static final String TECHNICAL_ENTITY_LABEL = "KGEntity";

    private final OntologySchema schema;
    private final Map<String, Set<String>> labelsByClass;
    private final Map<String, String> relationshipTypes;
    private final Set<String> allClassLabels;
    private final Set<String> allRelationshipTypes;

    private Neo4jOntologyMetadata(OntologySchema schema) {
        this.schema = Objects.requireNonNull(schema, "schema");
        this.labelsByClass = new LinkedHashMap<>();
        this.relationshipTypes = new LinkedHashMap<>();
        this.allClassLabels = new LinkedHashSet<>();
        this.allRelationshipTypes = new LinkedHashSet<>();
        buildClassTokens();
        buildRelationshipTokens();
    }

    public static Neo4jOntologyMetadata from(OntologySchema schema) {
        return new Neo4jOntologyMetadata(schema);
    }

    public Set<String> labelsForClass(String classIri) {
        Set<String> labels = labelsByClass.get(classIri);
        if (labels == null) throw new IllegalArgumentException("ontology 中不存在 class：" + classIri);
        return labels;
    }

    public String relationshipType(String predicateIri) {
        String type = relationshipTypes.get(predicateIri);
        if (type == null) throw new IllegalArgumentException("ontology 中不存在 objectProperty：" + predicateIri);
        return type;
    }

    public Map<String, String> relationshipTypes() {
        return Collections.unmodifiableMap(relationshipTypes);
    }

    public Set<String> allClassLabels() {
        return Collections.unmodifiableSet(allClassLabels);
    }

    public Set<String> allRelationshipTypes() {
        return Collections.unmodifiableSet(allRelationshipTypes);
    }

    public void validateRelationship(String predicateIri, String sourceClassIri, String targetClassIri) {
        OntologyTerm property = schema.getObjectProperties().get(predicateIri);
        if (property == null) throw new IllegalArgumentException("未知 objectProperty：" + predicateIri);
        requireClass(sourceClassIri, "关系起点");
        requireClass(targetClassIri, "关系终点");
        if (!compatibleWithAny(sourceClassIri, property.getDomains())) {
            throw new IllegalArgumentException("关系 domain 冲突：" + predicateIri + " <- " + sourceClassIri);
        }
        if (!compatibleWithAny(targetClassIri, property.getRanges())) {
            throw new IllegalArgumentException("关系 range 冲突：" + predicateIri + " -> " + targetClassIri);
        }
    }

    public boolean isClassCompatible(String childIri, String parentIri) {
        return schema.isClassCompatible(childIri, parentIri);
    }

    private void buildClassTokens() {
        Map<String, String> owners = new LinkedHashMap<>();
        for (String classIri : schema.getClasses().keySet()) {
            LinkedHashSet<String> labels = new LinkedHashSet<>();
            for (String closureIri : classClosure(classIri)) {
                String label = classLabel(closureIri);
                String previous = owners.putIfAbsent(label, closureIri);
                if (previous != null && !previous.equals(closureIri)) {
                    throw new IllegalArgumentException("ontology class Label 冲突：" + label + " <- " + previous + ", " + closureIri);
                }
                if (TECHNICAL_ENTITY_LABEL.equals(label)) {
                    throw new IllegalArgumentException("ontology class Label 与技术 Label 冲突：" + label);
                }
                labels.add(label);
                allClassLabels.add(label);
            }
            labelsByClass.put(classIri, Collections.unmodifiableSet(labels));
        }
    }

    private void buildRelationshipTokens() {
        Map<String, String> owners = new LinkedHashMap<>();
        for (String predicateIri : schema.getObjectProperties().keySet()) {
            String type = relationshipToken(predicateIri);
            String previous = owners.putIfAbsent(type, predicateIri);
            if (previous != null && !previous.equals(predicateIri)) {
                throw new IllegalArgumentException("ontology Relationship Type 冲突：" + type + " <- " + previous + ", " + predicateIri);
            }
            relationshipTypes.put(predicateIri, type);
            allRelationshipTypes.add(type);
        }
    }

    private List<String> classClosure(String classIri) {
        requireClass(classIri, "class");
        LinkedHashSet<String> result = new LinkedHashSet<>();
        collectClosure(classIri, result);
        return new ArrayList<>(result);
    }

    private void collectClosure(String classIri, Set<String> result) {
        if (!result.add(classIri)) return;
        OntologyTerm term = schema.getClasses().get(classIri);
        if (term == null) return;
        for (String superClass : term.getSuperClasses()) {
            if (schema.hasClass(superClass)) collectClosure(superClass, result);
        }
    }

    private boolean compatibleWithAny(String childIri, Set<String> parents) {
        if (parents.isEmpty()) return true;
        for (String parent : parents) if (schema.isClassCompatible(childIri, parent)) return true;
        return false;
    }

    private void requireClass(String classIri, String role) {
        if (!schema.hasClass(classIri)) throw new IllegalArgumentException(role + " class 不存在：" + classIri);
    }

    private String classLabel(String iri) {
        String local = localName(iri);
        if (local.isBlank()) throw new IllegalArgumentException("无法从 class IRI 生成 Label：" + iri);
        return local;
    }

    private String relationshipToken(String iri) {
        String local = localName(iri);
        String token = local
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("^_+|_+$", "")
                .toUpperCase(Locale.ROOT);
        if (token.isBlank()) throw new IllegalArgumentException("无法从 objectProperty IRI 生成 Relationship Type：" + iri);
        if (Character.isDigit(token.charAt(0))) token = "R_" + token;
        return token;
    }

    private String localName(String iri) {
        int hash = iri.lastIndexOf('#');
        int slash = iri.lastIndexOf('/');
        int colon = iri.lastIndexOf(':');
        int index = Math.max(hash, Math.max(slash, colon));
        return index >= 0 && index + 1 < iri.length() ? iri.substring(index + 1) : iri;
    }
}
