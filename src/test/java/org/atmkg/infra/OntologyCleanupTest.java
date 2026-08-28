package org.atmkg.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.atmkg.core.model.OntologySchema;
import org.atmkg.infra.ontology.JenaOntologyService;
import org.junit.jupiter.api.Test;

class OntologyCleanupTest {
    private static final String NS = "urn:atm-knowledge-graph:";

    @Test
    void ttlKeepsStableTermsAndRemovesRetiredDesignTerms() throws Exception {
        OntologySchema schema = new JenaOntologyService().load(Path.of("ontology/atm_knowledge_graph.ttl"));
        for (String property : List.of("entryLatitude", "entryLongitude", "entryElevation",
                "exitLatitude", "exitLongitude", "exitElevation")) {
            assertEquals(Set.of(NS + "RunwayDirection"),
                    schema.getDatatypeProperties().get(NS + property).getDomains(), property);
        }
        assertEquals(Set.of(NS + "RouteSegment"), schema.getObjectProperties().get(NS + "crosses").getDomains());
        assertEquals(Set.of(NS + "Airspace"), schema.getObjectProperties().get(NS + "crosses").getRanges());
        assertTrue(schema.getClasses().containsKey(NS + "Airport"));
        assertTrue(schema.getClasses().containsKey(NS + "RouteNode"));
        assertTrue(schema.getClasses().containsKey(NS + "RouteSegment"));
        assertTrue(schema.getClasses().containsKey(NS + "BoundaryPoint"));
        assertFalse(schema.getClasses().containsKey(NS + "RouteStructureObject"));
        assertFalse(schema.getClasses().containsKey(NS + "SpatialRepresentation"));
        assertFalse(schema.getObjectProperties().containsKey(NS + "nextNode"));
        assertFalse(schema.getObjectProperties().containsKey(NS + "locatedIn"));
        String ttl = Files.readString(Path.of("ontology/atm_knowledge_graph.ttl"));
        assertFalse(ttl.contains("atmkg:sourceField"));
        assertFalse(ttl.contains("atmkg:designRationale"));
        assertFalse(ttl.contains("atmkg:designStatus"));
    }
}
