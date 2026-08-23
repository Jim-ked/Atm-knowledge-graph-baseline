package org.atmkg.infra.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.atmkg.core.model.OntologySchema;
import org.atmkg.core.model.OntologyTerm;
import org.junit.jupiter.api.Test;

class Neo4jOntologyMetadataTest {
    @Test
    void derivesClassLabelsFromDirectAndTransitiveOntologyClosure() {
        OntologySchema schema = schema(
                Map.of(
                        "urn:test:Base", term("urn:test:Base", Set.of()),
                        "urn:test:Sequence", term("urn:test:Sequence", Set.of()),
                        "urn:test:Route", term("urn:test:Route", Set.of("urn:test:Base", "urn:test:Sequence"))),
                Map.of("urn:test:hasNode", objectProperty("urn:test:hasNode", Set.of("urn:test:Route"), Set.of("urn:test:Route"))));

        Neo4jOntologyMetadata metadata = Neo4jOntologyMetadata.from(schema);

        assertEquals(Set.of("Route", "Base", "Sequence"), metadata.labelsForClass("urn:test:Route"));
        assertEquals("HAS_NODE", metadata.relationshipType("urn:test:hasNode"));
    }

    @Test
    void subclassClosureTerminatesOnCycles() {
        OntologySchema schema = schema(
                Map.of(
                        "urn:test:A", term("urn:test:A", Set.of("urn:test:B")),
                        "urn:test:B", term("urn:test:B", Set.of("urn:test:A"))),
                Map.of());

        assertEquals(Set.of("A", "B"), Neo4jOntologyMetadata.from(schema).labelsForClass("urn:test:A"));
    }

    @Test
    void rejectsClassAndRelationshipTokenCollisions() {
        OntologySchema classCollision = schema(
                Map.of(
                        "urn:a:Thing", term("urn:a:Thing", Set.of()),
                        "urn:b:Thing", term("urn:b:Thing", Set.of())), Map.of());
        assertThrows(IllegalArgumentException.class, () -> Neo4jOntologyMetadata.from(classCollision));

        OntologySchema relationshipCollision = schema(
                Map.of("urn:test:Base", term("urn:test:Base", Set.of())),
                Map.of(
                        "urn:test:hasRunway", objectProperty("urn:test:hasRunway", Set.of(), Set.of()),
                        "urn:test:has-runway", objectProperty("urn:test:has-runway", Set.of(), Set.of())));
        assertThrows(IllegalArgumentException.class, () -> Neo4jOntologyMetadata.from(relationshipCollision));
    }

    @Test
    void rejectsUnknownTermsAndDomainRangeMismatch() {
        OntologySchema schema = schema(
                Map.of(
                        "urn:test:Route", term("urn:test:Route", Set.of()),
                        "urn:test:Node", term("urn:test:Node", Set.of())),
                Map.of("urn:test:hasNode", objectProperty("urn:test:hasNode",
                        Set.of("urn:test:Route"), Set.of("urn:test:Node"))));
        Neo4jOntologyMetadata metadata = Neo4jOntologyMetadata.from(schema);

        assertThrows(IllegalArgumentException.class, () -> metadata.labelsForClass("urn:test:Missing"));
        assertThrows(IllegalArgumentException.class, () -> metadata.relationshipType("urn:test:missing"));
        metadata.validateRelationship("urn:test:hasNode", "urn:test:Route", "urn:test:Node");
        assertThrows(IllegalArgumentException.class,
                () -> metadata.validateRelationship("urn:test:hasNode", "urn:test:Node", "urn:test:Route"));
    }

    private static OntologySchema schema(Map<String, OntologyTerm> classes,
                                         Map<String, OntologyTerm> objectProperties) {
        return new OntologySchema(new LinkedHashMap<>(classes), Map.of(), new LinkedHashMap<>(objectProperties));
    }

    private static OntologyTerm term(String iri, Set<String> superClasses) {
        return new OntologyTerm(iri, null, Set.of(), Set.of(), superClasses, "已确认");
    }

    private static OntologyTerm objectProperty(String iri, Set<String> domains, Set<String> ranges) {
        return new OntologyTerm(iri, null, domains, ranges, Set.of(), "已确认");
    }
}
