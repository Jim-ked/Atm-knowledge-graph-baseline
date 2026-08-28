package org.atmkg.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.atmkg.core.model.OntologySchema;
import org.atmkg.infra.ontology.JenaOntologyService;
import org.junit.jupiter.api.Test;

class JenaOntologyServiceTest {
    @Test
    void loadsCurrentOntologySchema() {
        OntologySchema schema = new JenaOntologyService().load(Path.of("ontology/atm_knowledge_graph.ttl"));
        assertEquals(18, schema.getClasses().size());
        assertEquals(82, schema.getDatatypeProperties().size());
        assertEquals(13, schema.getObjectProperties().size());
        assertTrue(schema.isClassCompatible("urn:atm-knowledge-graph:Airport", "urn:atm-knowledge-graph:AviationBaseObject"));
        assertTrue(schema.isClassCompatible(
                "urn:atm-knowledge-graph:PlannedFlightRoute",
                "urn:atm-knowledge-graph:RouteSequence"));
        assertTrue(schema.getObjectProperties().get("urn:atm-knowledge-graph:hasNode").getDomains()
                .contains("urn:atm-knowledge-graph:RouteSequence"));
        assertTrue(schema.getObjectProperties().containsKey("urn:atm-knowledge-graph:hasPlannedRoute"));
        assertTrue(!schema.getObjectProperties().containsKey("urn:atm-knowledge-graph:nextNode"));
    }
}
