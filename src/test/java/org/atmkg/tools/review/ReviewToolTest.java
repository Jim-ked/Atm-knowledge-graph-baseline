package org.atmkg.tools.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReviewToolTest {
    @Test
    void singleEntityLocatorPrefersSourceKey() throws Exception {
        Map<String, Object> captured = new LinkedHashMap<>();
        StringWriter output = new StringWriter();

        ReviewTool.run(
                ReviewQueryCatalog.load(Path.of("review/queries.yaml")),
                new BufferedReader(new StringReader("1\nZ002\n0\n")),
                new PrintWriter(output, true),
                (template, cypher, parameters) -> captured.putAll(parameters));

        assertEquals("Z002", captured.get("source_key"));
        assertEquals("", captured.get("kg_uid"));
        assertTrue(output.toString().contains("source_key（推荐"));
    }

    @Test
    void blankSourceKeyFallsBackToStableUid() throws Exception {
        Map<String, Object> captured = new LinkedHashMap<>();

        ReviewTool.run(
                ReviewQueryCatalog.load(Path.of("review/queries.yaml")),
                new BufferedReader(new StringReader("1\n\nurn:test:uid\n0\n")),
                new PrintWriter(new StringWriter(), true),
                (template, cypher, parameters) -> captured.putAll(parameters));

        assertEquals("", captured.get("source_key"));
        assertEquals("urn:test:uid", captured.get("kg_uid"));
    }

    @Test
    void driverKeepsParameterBindingWhileOutputIncludesBrowserReadyCypher() throws Exception {
        StringWriter output = new StringWriter();

        ReviewTool.run(
                ReviewQueryCatalog.load(Path.of("review/queries.yaml")),
                new BufferedReader(new StringReader("2\nO'Brien\\West\n0\n")),
                new PrintWriter(output, true),
                (template, cypher, parameters) -> {
                    assertTrue(cypher.contains("$project_id"));
                    assertTrue(cypher.contains("$source_key"));
                });

        String text = output.toString();
        assertTrue(text.contains("Browser 可直接执行"));
        assertTrue(text.contains("'atm-knowledge-graph'"));
        assertTrue(text.contains("'O\\'Brien\\\\West'"));
    }

    @Test
    void rejectsInvalidRelationshipTypeBeforeDriverExecution() throws Exception {
        boolean[] executed = {false};
        StringWriter output = new StringWriter();

        ReviewTool.run(
                ReviewQueryCatalog.load(Path.of("review/queries.yaml")),
                new BufferedReader(new StringReader("5\nHAS_RUNWAY') DELETE relationship //\n0\n")),
                new PrintWriter(output, true),
                (template, cypher, parameters) -> executed[0] = true);

        assertFalse(executed[0]);
        assertTrue(output.toString().contains("relationship_type 必须是大写 Neo4j Relationship Type"));
    }
}
