package org.atmkg.infra.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.Map;
import org.atmkg.core.error.GraphStoreException;
import org.junit.jupiter.api.Test;

class Neo4jValueNormalizerTest {
    @Test
    void convertsBigDecimalToNeo4jDouble() {
        assertEquals(123.25d, Neo4jValueNormalizer.normalize(new BigDecimal("123.25")));
    }

    @Test
    void rejectsNestedMapProperty() {
        assertThrows(GraphStoreException.class, () -> Neo4jValueNormalizer.normalize(Map.of("x", 1)));
    }
}
