package org.atmkg.tools.review;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReviewResultSummaryTest {
    @Test
    void scalarOnlyQueryPrintsDataFieldsWithoutAnEmptyGraphHeading() {
        ReviewResultSummary summary = new ReviewResultSummary();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("entities", 122L);
        row.put("relationships", 185L);
        summary.acceptScalarRow(row);
        StringWriter text = new StringWriter();

        summary.print(new PrintWriter(text, true));

        assertTrue(text.toString().contains("数据结果:"));
        assertTrue(text.toString().contains("  entities=122"));
        assertTrue(text.toString().contains("  relationships=185"));
        assertFalse(text.toString().contains("图结果:"));
    }

    @Test
    void duplicateCheckKeepsEmptyListReadable() {
        ReviewResultSummary summary = new ReviewResultSummary();
        summary.acceptScalarRow(Map.of("duplicate_uid_groups", 0L, "duplicates", List.of()));
        StringWriter text = new StringWriter();

        summary.print(new PrintWriter(text, true));

        assertTrue(text.toString().contains("duplicate_uid_groups=0"));
        assertTrue(text.toString().contains("duplicates=[]"));
    }
}
