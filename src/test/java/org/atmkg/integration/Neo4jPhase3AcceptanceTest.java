package org.atmkg.integration;

import org.atmkg.tools.Phase3Neo4jCheckMain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Explicit real-Neo4j Phase 3 gate; skipped during the normal unit-test suite. */
class Neo4jPhase3AcceptanceTest {
    @Test
    @EnabledIfSystemProperty(named = "atmkg.neo4j.phase3.it", matches = "true")
    void realNeo4jPhase3Gate() {
        Phase3Neo4jCheckMain.main(new String[]{"."});
    }
}
