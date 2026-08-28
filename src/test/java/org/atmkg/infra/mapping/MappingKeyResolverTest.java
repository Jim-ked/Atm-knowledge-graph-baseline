package org.atmkg.infra.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.atmkg.core.error.MappingExecutionException;
import org.junit.jupiter.api.Test;

class MappingKeyResolverTest {
    @Test
    void singleFieldKeepsLegacyTrimmedValue() {
        assertEquals("DOGAR", MappingKeyResolver.resolve(Map.of("nodeCode", "  DOGAR  "), "nodeCode"));
    }

    @Test
    void multipleFieldsAreStableAndEscapedWithoutDelimiterCollisions() {
        String first = MappingKeyResolver.resolve(Map.of("type", "A|B", "code", "C\\D"), "type;code");
        String second = MappingKeyResolver.resolve(Map.of("type", "A", "code", "B|C\\D"), "type;code");

        assertEquals("A\\|B|C\\\\D", first);
        assertEquals("A|B\\|C\\\\D", second);
    }

    @Test
    void missingOrBlankPartFailsClearly() {
        assertThrows(MappingExecutionException.class,
                () -> MappingKeyResolver.resolve(Map.of("type", "A"), "type;code"));
        assertThrows(MappingExecutionException.class,
                () -> MappingKeyResolver.resolve(Map.of("type", "A", "code", "  "), "type;code"));
    }

    @Test
    void nestedPathsAreResolvedForCompositeKeys() {
        assertEquals("N|DOGAR", MappingKeyResolver.resolve(
                Map.of("current", Map.of("type", "N", "code", " DOGAR ")),
                "current.type;current.code"));
    }

    @Test
    void unsupportedExpressionIsNotEvaluatedAsAKey() {
        assertThrows(MappingExecutionException.class,
                () -> MappingKeyResolver.resolve(Map.of("nodeCode", "DOGAR"), "concat(nodeType,nodeCode)"));
    }
}
