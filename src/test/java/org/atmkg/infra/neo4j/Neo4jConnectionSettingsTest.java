package org.atmkg.infra.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Neo4jConnectionSettingsTest {
    @Test
    void requiresExplicitConnectionValues() {
        assertThrows(IllegalStateException.class,
                () -> Neo4jConnectionSettings.fromMap(Map.of(), "atm-knowledge-graph", 500));
    }

    @Test
    void readsAllRequiredValuesWithoutFallback() {
        Map<String, String> env = new HashMap<>();
        env.put("ATMKG_NEO4J_URI", "bolt://example:7687");
        env.put("ATMKG_NEO4J_DATABASE", "atmkg");
        env.put("ATMKG_NEO4J_USERNAME", "neo4j");
        env.put("ATMKG_NEO4J_PASSWORD", "secret");
        Neo4jConnectionSettings settings = Neo4jConnectionSettings.fromMap(env, "atm-knowledge-graph", 500);
        assertEquals("bolt://example:7687", settings.getUri());
        assertEquals("atmkg", settings.getDatabase());
        assertEquals(500, settings.getBatchSize());
    }
}
