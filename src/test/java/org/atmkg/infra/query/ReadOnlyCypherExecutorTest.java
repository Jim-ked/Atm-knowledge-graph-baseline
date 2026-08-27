package org.atmkg.infra.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.atmkg.core.error.ReadOnlyCypherException;
import org.atmkg.core.model.CypherResultDTO;
import org.atmkg.infra.neo4j.Neo4jConnectionSettings;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AccessMode;
import org.neo4j.driver.Record;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;
import org.neo4j.driver.internal.InternalNode;
import org.neo4j.driver.internal.InternalPath;
import org.neo4j.driver.internal.InternalRelationship;
import org.neo4j.driver.summary.QueryType;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Path;
import org.neo4j.driver.types.Relationship;

class ReadOnlyCypherExecutorTest {
    @Test
    void scalarAndTableResultsAreJsonSafeAndProduceAnEmptyGraph() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("total", 3L);
        fields.put("text", "ok");
        fields.put("flag", true);
        fields.put("nothing", null);
        fields.put("values", List.of(1L, 2L, 3L));
        fields.put("nullableValues", Arrays.asList(1L, null, 3L));
        fields.put("value", Map.of("a", 1L, "b", "x"));
        fields.put("date", LocalDate.of(2026, 8, 28));
        Neo4jTestDriver neo4j = execution(Neo4jTestDriver.record(fields));

        CypherResultDTO result = executor(neo4j).execute(
                "MATCH (n:KGEntity) RETURN count(n) AS total");

        assertEquals(List.copyOf(fields.keySet()), result.getColumns());
        assertEquals(1, result.getRows().size());
        assertEquals(3L, result.getRows().get(0).get("total"));
        assertEquals(List.of(1L, 2L, 3L), result.getRows().get(0).get("values"));
        assertEquals(Arrays.asList(1L, null, 3L), result.getRows().get(0).get("nullableValues"));
        assertEquals(Map.of("a", 1L, "b", "x"), result.getRows().get(0).get("value"));
        assertEquals("2026-08-28", result.getRows().get(0).get("date"));
        assertTrue(result.getGraph().getNodes().isEmpty());
        assertEquals("CYPHER", result.getMeta().get("queryType"));
        assertEquals(1, result.getMeta().get("rowCount"));
    }

    @Test
    void returnedNodeAppearsInBothRowsAndGraph() {
        Node airport = node(1, "n1", "airport-1", "urn:test:Airport", "ZBAA");
        Neo4jTestDriver neo4j = execution(Neo4jTestDriver.record(Map.of("n", airport)));

        CypherResultDTO result = executor(neo4j).execute("MATCH (n:KGEntity) RETURN n LIMIT 1");

        Map<?, ?> tableNode = assertInstanceOf(Map.class, result.getRows().get(0).get("n"));
        assertEquals("node", tableNode.get("type"));
        assertEquals("airport-1", tableNode.get("uid"));
        assertFalse(tableNode.containsKey("id"));
        assertEquals(List.of("airport-1"), result.getGraph().getNodes().stream().map(n -> n.getId()).toList());
        assertEquals("2026-08-28", result.getGraph().getNodes().get(0).getProperties().get("observedOn"));
    }

    @Test
    void returnedPathAppearsInBothRowsAndCompleteGraphWithoutExposingElementIds() throws Exception {
        Node airport = node(1, "n1", "airport-1", "urn:test:Airport", "ZBAA");
        Node runway = node(2, "n2", "runway-1", "urn:test:Runway", "RWY01");
        Relationship relationship = relationship(airport, runway);
        Path path = new InternalPath(List.of(airport, relationship, runway));
        Neo4jTestDriver neo4j = execution(Neo4jTestDriver.record(Map.of("p", path)));

        CypherResultDTO result = executor(neo4j).execute("MATCH p=(a)-[r]->(b) RETURN p LIMIT 1");

        Map<?, ?> tablePath = assertInstanceOf(Map.class, result.getRows().get(0).get("p"));
        assertEquals("path", tablePath.get("type"));
        String serializedRows = new ObjectMapper().writeValueAsString(result.getRows());
        assertFalse(serializedRows.contains("\"id\""));
        assertFalse(serializedRows.contains("\"elementId\""));
        assertFalse(serializedRows.contains("\"startElementId\""));
        assertFalse(serializedRows.contains("\"endElementId\""));
        assertEquals(2, result.getGraph().getNodes().size());
        assertEquals(1, result.getGraph().getRelationships().size());
    }

    @Test
    void recursivelyExtractsCollectedNodesIntoGraph() {
        Node airport = node(1, "n1", "airport-1", "urn:test:Airport", "ZBAA");
        Neo4jTestDriver neo4j = execution(
                Neo4jTestDriver.record(Map.of("collected", List.of(airport))));

        CypherResultDTO result = executor(neo4j).execute("MATCH (n:KGEntity) RETURN collect(n)");

        assertEquals(1, result.getGraph().getNodes().size());
        List<?> tableValues = assertInstanceOf(List.class, result.getRows().get(0).get("collected"));
        assertEquals("node", assertInstanceOf(Map.class, tableValues.get(0)).get("type"));
    }

    @Test
    void relationshipOnlyResultKeepsTableButDoesNotInventGraphEndpoints() {
        Node airport = node(1, "n1", "airport-1", "urn:test:Airport", "ZBAA");
        Node runway = node(2, "n2", "runway-1", "urn:test:Runway", "RWY01");
        Neo4jTestDriver neo4j = execution(
                Neo4jTestDriver.record(Map.of("r", relationship(airport, runway))));

        CypherResultDTO result = executor(neo4j).execute("MATCH ()-[r]->() RETURN r LIMIT 1");

        Map<?, ?> tableRelationship = assertInstanceOf(Map.class, result.getRows().get(0).get("r"));
        assertEquals("relationship", tableRelationship.get("type"));
        assertEquals("rel-1", tableRelationship.get("uid"));
        assertFalse(tableRelationship.containsKey("id"));
        assertFalse(tableRelationship.containsKey("startElementId"));
        assertFalse(tableRelationship.containsKey("endElementId"));
        assertTrue(result.getGraph().getNodes().isEmpty());
        assertTrue(result.getGraph().getRelationships().isEmpty());
    }

    @Test
    void resolvesRelationshipWhenItsEndpointsAppearInLaterRows() {
        Node airport = node(1, "n1", "airport-1", "urn:test:Airport", "ZBAA");
        Node runway = node(2, "n2", "runway-1", "urn:test:Runway", "RWY01");
        Neo4jTestDriver neo4j = execution(
                Neo4jTestDriver.record(Map.of("value", relationship(airport, runway))),
                Neo4jTestDriver.record(Map.of("value", List.of(airport, runway))));

        CypherResultDTO result = executor(neo4j).execute("MATCH (a)-[r]->(b) RETURN r AS value UNION RETURN [a,b]");

        assertEquals(2, result.getGraph().getNodes().size());
        assertEquals(1, result.getGraph().getRelationships().size());
    }

    @Test
    void readOnlySafetyStillUsesExplainQueryTypeAndReadSession() {
        Neo4jTestDriver neo4j = new Neo4jTestDriver();
        neo4j.enqueue(Neo4jTestDriver.result(QueryType.WRITE_ONLY));

        ReadOnlyCypherException write = assertThrows(ReadOnlyCypherException.class,
                () -> executor(neo4j).execute("CREATE (:Forbidden)"));

        assertEquals("CYPHER_READ_ONLY_REQUIRED", write.getCode());
        assertEquals(1, neo4j.calls().size());
        assertTrue(neo4j.calls().get(0).query().startsWith("EXPLAIN "));
        assertEquals(AccessMode.READ, neo4j.sessionConfig().defaultAccessMode());

        assertEquals("CYPHER_EXPLAIN_PROFILE_NOT_ALLOWED", assertThrows(ReadOnlyCypherException.class,
                () -> executor(new Neo4jTestDriver()).execute("EXPLAIN RETURN 1")).getCode());
        assertEquals("CYPHER_EXPLAIN_PROFILE_NOT_ALLOWED", assertThrows(ReadOnlyCypherException.class,
                () -> executor(new Neo4jTestDriver()).execute("PROFILE RETURN 1")).getCode());
    }

    @Test
    void rejectsTheThousandAndFirstRowInsteadOfTruncating() {
        List<Record> records = new ArrayList<>();
        for (int i = 0; i < 1001; i++) records.add(Neo4jTestDriver.record(Map.of("value", i)));
        Neo4jTestDriver neo4j = execution(records.toArray(Record[]::new));

        ReadOnlyCypherException failure = assertThrows(
                ReadOnlyCypherException.class, () -> executor(neo4j).execute("UNWIND range(0,1000) AS value RETURN value"));

        assertEquals(413, failure.getStatus());
        assertEquals("RESULT_TOO_LARGE", failure.getCode());
    }

    private Neo4jTestDriver execution(Record... records) {
        Neo4jTestDriver neo4j = new Neo4jTestDriver();
        neo4j.enqueue(Neo4jTestDriver.result(QueryType.READ_ONLY));
        neo4j.enqueue(Neo4jTestDriver.result(QueryType.READ_ONLY, records));
        return neo4j;
    }

    private ReadOnlyCypherExecutor executor(Neo4jTestDriver neo4j) {
        return new ReadOnlyCypherExecutor(neo4j.driver(), settings(), "1", 100, 100);
    }

    private Neo4jConnectionSettings settings() {
        return new Neo4jConnectionSettings(
                "bolt://unused", "neo4j", "neo4j", "password", "project", 100);
    }

    private Node node(long id, String elementId, String uid, String classIri, String caption) {
        return new InternalNode(id, elementId, List.of("KGEntity", localName(classIri)), properties(Map.of(
                "kg_uid", uid, "kg_project", "project", "kg_class_iri", classIri,
                "kg_caption", caption, "business", caption, "observedOn", LocalDate.of(2026, 8, 28))));
    }

    private Relationship relationship(Node start, Node end) {
        return new InternalRelationship(10, "r10", start.id(), start.elementId(), end.id(), end.elementId(),
                "HAS_RUNWAY", properties(Map.of(
                        "kg_uid", "rel-1", "kg_predicate_iri", "urn:test:hasRunway", "distance", 1L)));
    }

    private Map<String, Value> properties(Map<String, Object> values) {
        Map<String, Value> properties = new LinkedHashMap<>();
        values.forEach((key, value) -> properties.put(key, Values.value(value)));
        return properties;
    }

    private String localName(String iri) {
        return iri.substring(iri.lastIndexOf(':') + 1);
    }
}
