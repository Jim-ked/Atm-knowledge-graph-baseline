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
        assertEquals(73, schema.getDatatypeProperties().size());
        assertEquals(12, schema.getObjectProperties().size());
        assertEquals("后续推导", schema.getObjectProperties()
                .get("urn:atm-knowledge-graph:locatedIn").getDesignStatus());
        assertTrue(schema.isClassCompatible("urn:atm-knowledge-graph:Airport", "urn:atm-knowledge-graph:AviationBaseObject"));
    }
}
