package org.atmkg.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.atmkg.core.model.SourceRecord;
import org.junit.jupiter.api.Test;

class MappingAssistMainTest {
    @Test
    void detectsOrdinaryAndAdjacentFieldPathsWithoutSourceKey() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("AIRPORT_CODE", "ZBAA");
        fields.put("__sourceKey", "hidden");
        fields.put("current", row("CODE", "NAME", "__sourceKey"));
        fields.put("next", row("CODE", "NAME", "__sourceKey"));

        List<String> paths = MappingAssistMain.fieldPaths(List.of(
                new SourceRecord("fixture", "adjacent", "1", fields, null)));

        assertEquals(List.of(
                "AIRPORT_CODE", "current.CODE", "current.NAME", "next.CODE", "next.NAME"), paths);
    }

    private static Map<String, Object> row(String... names) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String name : names) row.put(name, "not printed");
        return row;
    }
}
