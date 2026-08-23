package org.atmkg.integration;

import org.atmkg.tools.Phase4Neo4jApiCheckMain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Explicit real-Neo4j Phase 4 HTTP API gate; skipped during the normal test suite. */
class Neo4jPhase4AcceptanceTest {
    @Test
    @EnabledIfSystemProperty(named = "atmkg.neo4j.phase4.it", matches = "true")
    void realNeo4jPhase4Gate() {
        Phase4Neo4jApiCheckMain.main(new String[]{"."});
    }
}
