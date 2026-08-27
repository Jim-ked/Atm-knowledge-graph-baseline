package org.atmkg.infra.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.atmkg.core.error.EntityLookupException;
import org.atmkg.core.model.GraphDTO;
import org.atmkg.infra.neo4j.Neo4jConnectionSettings;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AccessMode;
import org.neo4j.driver.Record;
import org.neo4j.driver.summary.QueryType;

class Neo4jEntityLookupServiceTest {
    private static final String AIRPORT = "urn:atm-knowledge-graph:Airport";

    @Test
    void findsAllExactBusinessKeyMatchesInStableClassAndUidOrderWithoutUidInput() {
        Neo4jTestDriver neo4j = new Neo4jTestDriver();
        neo4j.enqueue(Neo4jTestDriver.result(QueryType.READ_ONLY,
                entity("u-runway", "urn:atm-knowledge-graph:Runway", "ZBAA", List.of("KGEntity", "Runway")),
                entity("u-airport", AIRPORT, "ZBAA", List.of("KGEntity", "Airport"))));
        Neo4jEntityLookupService service = new Neo4jEntityLookupService(
                neo4j.driver(), settings(), "1");

        GraphDTO graph = service.lookup("ZBAA", null);

        assertEquals(List.of("u-airport", "u-runway"), graph.getNodes().stream().map(node -> node.getId()).toList());
        assertTrue(graph.getRelationships().isEmpty());
        assertEquals("ENTITY_LOOKUP", graph.getMeta().get("queryType"));
        assertEquals(AccessMode.READ, neo4j.sessionConfig().defaultAccessMode());
        assertEquals("ZBAA", neo4j.calls().get(0).parameters().get("key"));
        assertEquals(null, neo4j.calls().get(0).parameters().get("classIri"));
        assertTrue(neo4j.calls().get(0).query().contains("toLower(trim(n.kg_caption)) = toLower($key)"));
        assertTrue(neo4j.calls().get(0).query().contains("NOT n:KGEntityContribution"));
        assertTrue(!neo4j.calls().get(0).query().contains("CONTAINS"));
        assertTrue(!neo4j.calls().get(0).query().contains("ZBAA"));
    }

    @Test
    void passesOptionalClassFilterAndReturnsOnlyThatClass() {
        Neo4jTestDriver neo4j = new Neo4jTestDriver();
        neo4j.enqueue(Neo4jTestDriver.result(QueryType.READ_ONLY,
                entity("u-airport", AIRPORT, "ZBAA", List.of("KGEntity", "Airport"))));
        Neo4jEntityLookupService service = new Neo4jEntityLookupService(
                neo4j.driver(), settings(), "1");

        GraphDTO graph = service.lookup("ZBAA", AIRPORT);

        assertEquals(List.of("u-airport"), graph.getNodes().stream().map(node -> node.getId()).toList());
        assertTrue(graph.getRelationships().isEmpty());
        assertEquals(AIRPORT, neo4j.calls().get(0).parameters().get("classIri"));
    }

    @Test
    void trimsBusinessKeyAndClassIriBeforeExactMatch() {
        Neo4jTestDriver neo4j = new Neo4jTestDriver();
        neo4j.enqueue(Neo4jTestDriver.result(QueryType.READ_ONLY));
        Neo4jEntityLookupService service = new Neo4jEntityLookupService(
                neo4j.driver(), settings(), "1");

        GraphDTO graph = service.lookup(" ZBAA ", " urn:test:Airport ");

        assertTrue(graph.getNodes().isEmpty());
        assertTrue(graph.getRelationships().isEmpty());
        assertEquals("ZBAA", neo4j.calls().get(0).parameters().get("key"));
        assertEquals("urn:test:Airport", neo4j.calls().get(0).parameters().get("classIri"));
    }

    @Test
    void rejectsMoreThanFiftyMatchesInsteadOfSilentlyTruncating() {
        Neo4jTestDriver neo4j = new Neo4jTestDriver();
        List<Record> records = new ArrayList<>();
        for (int i = 0; i < 51; i++) records.add(entity("u-" + i, AIRPORT, "ZBAA", List.of("KGEntity")));
        neo4j.enqueue(Neo4jTestDriver.result(QueryType.READ_ONLY, records.toArray(Record[]::new)));
        Neo4jEntityLookupService service = new Neo4jEntityLookupService(
                neo4j.driver(), settings(), "1");

        EntityLookupException failure = assertThrows(
                EntityLookupException.class, () -> service.lookup("ZBAA", null));

        assertEquals(413, failure.getStatus());
        assertEquals("RESULT_TOO_LARGE", failure.getCode());
    }

    private Record entity(String uid, String classIri, String caption, List<String> labels) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("kg_uid", uid);
        properties.put("kg_project", "project");
        properties.put("kg_class_iri", classIri);
        properties.put("kg_caption", caption);
        properties.put("name", caption + " name");
        return Neo4jTestDriver.record(Map.of("props", properties, "labels", labels));
    }

    private Neo4jConnectionSettings settings() {
        return new Neo4jConnectionSettings(
                "bolt://unused", "neo4j", "neo4j", "password", "project", 100);
    }
}
