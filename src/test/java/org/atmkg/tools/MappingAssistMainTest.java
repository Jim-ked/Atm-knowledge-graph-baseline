package org.atmkg.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.atmkg.infra.source.compose.RecordCompositionSpec;
import org.junit.jupiter.api.Test;

class MappingAssistMainTest {
    @Test
    void logicalPathsAreDerivedWithoutSamplingValues() {
        RecordCompositionSpec spec = new RecordCompositionSpec(
                RecordCompositionSpec.RecordMode.ADJACENT_NEXT, List.of("id"), List.of("group"), "seq");
        List<String> paths = spec.logicalFieldPaths(List.of("AIRPORT_CODE", "__sourceKey", "CODE"));
        assertEquals(List.of(
                "AIRPORT_CODE", "CODE", "current.AIRPORT_CODE", "next.AIRPORT_CODE",
                "current.CODE", "next.CODE"), paths);
    }
}
