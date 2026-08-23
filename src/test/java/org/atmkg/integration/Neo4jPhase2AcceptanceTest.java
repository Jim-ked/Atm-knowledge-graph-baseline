package org.atmkg.integration;

import org.atmkg.tools.Phase2Neo4jCheckMain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Explicit real-Neo4j gate. It is skipped during normal unit tests and only runs when requested.
 * Required environment variables are validated by Neo4jConnectionSettings; no defaults are used.
 */
class Neo4jPhase2AcceptanceTest {
    @Test
    @EnabledIfSystemProperty(named = "atmkg.neo4j.it", matches = "true")
    void realNeo4jPhase2Gate() {
        Phase2Neo4jCheckMain.main(new String[]{"."});
    }
}
