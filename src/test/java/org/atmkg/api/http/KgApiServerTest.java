package org.atmkg.api.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.atmkg.core.error.QueryExecutionException;
import org.atmkg.core.model.CypherResultDTO;
import org.atmkg.core.model.GraphDTO;
import org.atmkg.core.model.GraphNodeDTO;
import org.atmkg.core.model.GraphRelationshipDTO;
import org.atmkg.core.model.OntologySchema;
import org.atmkg.core.model.OntologyTerm;
import org.atmkg.core.model.QuerySpec;
import org.atmkg.core.spi.EntityLookupService;
import org.atmkg.core.spi.QueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KgApiServerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String UID = "urn:test:node%2F1";

    private RecordingQueryService queryService;
    private RecordingEntityLookupService entityLookupService;
    private KgApiServer server;
    private HttpClient client;
    private URI baseUri;

    @TempDir
    Path tempDir;

    @BeforeEach
    void startServer() {
        queryService = new RecordingQueryService();
        entityLookupService = new RecordingEntityLookupService();
        server = new KgApiServer(
                config(100, 100), queryService, entityLookupService, null, schema(), () -> true);
        server.start();
        client = HttpClient.newHttpClient();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1");
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void healthReportsServiceAndNeo4jUp() throws Exception {
        HttpResponse<String> response = get("/health");

        assertEquals(200, response.statusCode());
        assertEquals("UP", json(response).get("status").asText());
        assertEquals("UP", json(response).get("neo4j").asText());
    }

    @Test
    void healthKeepsRespondingWhenNeo4jIsUnavailable() throws Exception {
        server.close();
        server = new KgApiServer(config(100, 100), queryService, schema(), () -> false);
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1");

        HttpResponse<String> response = get("/health");

        assertEquals(503, response.statusCode());
        assertEquals("DEGRADED", json(response).get("status").asText());
        assertEquals("DOWN", json(response).get("neo4j").asText());
        assertFalse(response.body().contains("bolt://"));
    }

    @Test
    void entityReturnsGraphDtoAndMissingEntityUsesUnifiedError() throws Exception {
        HttpResponse<String> found = get("/entities/" + pathSegment(UID));
        HttpResponse<String> missing = get("/entities/" + pathSegment("missing"));

        assertEquals(200, found.statusCode());
        assertEquals(UID, json(found).get("nodes").get(0).get("id").asText());
        assertEquals(QuerySpec.Type.ENTITY, queryService.lastSpec.getType());
        assertEquals(404, missing.statusCode());
        assertEquals("ENTITY_NOT_FOUND", json(missing).get("code").asText());
    }

    @Test
    void entityLookupAcceptsBusinessKeyWithOptionalClassAndEmptyResultIsNotAnError() throws Exception {
        HttpResponse<String> allClasses = post("/entities/lookup", "{\"key\":\"ZBAA\"}");
        HttpResponse<String> missing = post("/entities/lookup", "{\"key\":\"missing\"}");
        HttpResponse<String> oneClass = post("/entities/lookup",
                "{\"key\":\"ZBAA\",\"classIri\":\"urn:test:Airport\"}");
        HttpResponse<String> unknown = post("/entities/lookup", "{\"key\":\"ZBAA\",\"uid\":\"wrong\"}");

        assertEquals(200, allClasses.statusCode());
        assertEquals("ZBAA", json(allClasses).get("nodes").get(0).get("caption").asText());
        assertEquals(200, oneClass.statusCode());
        assertEquals("urn:test:Airport", entityLookupService.lastClassIri);
        assertEquals(200, missing.statusCode());
        assertEquals(0, json(missing).get("nodes").size());
        assertEquals(400, unknown.statusCode());
    }

    @Test
    void oneHopMapsTheThinRequestToQuerySpec() throws Exception {
        HttpResponse<String> response = post("/graph/one-hop", "{\"uid\":\"" + UID + "\"}");

        assertEquals(200, response.statusCode());
        assertEquals(QuerySpec.Type.NEIGHBORS, queryService.lastSpec.getType());
        assertEquals(UID, queryService.lastSpec.getStartUid());
        assertEquals(QuerySpec.Direction.BOTH, queryService.lastSpec.getDirection());
        assertEquals(1, json(response).get("relationships").size());
    }

    @Test
    void kHopPreservesFiltersDirectionAndCompleteResult() throws Exception {
        String body = "{\"uid\":\"" + UID + "\",\"depth\":2," +
                "\"relationshipTypes\":[\"urn:test:rel\"],\"classFilters\":[\"urn:test:Class\"]," +
                "\"direction\":\"OUTGOING\"}";

        HttpResponse<String> response = post("/graph/k-hop", body);

        assertEquals(200, response.statusCode());
        assertEquals(QuerySpec.Type.K_HOP, queryService.lastSpec.getType());
        assertEquals(2, queryService.lastSpec.getDepth());
        assertEquals(Set.of("urn:test:rel"), queryService.lastSpec.getRelationshipTypes());
        assertEquals(Set.of("urn:test:Class"), queryService.lastSpec.getClassFilters());
        assertEquals(QuerySpec.Direction.OUTGOING, queryService.lastSpec.getDirection());
        assertTrue(json(response).get("meta").get("complete").asBoolean());
    }

    @Test
    void pathUsesOutgoingDefaultAndMaxDepth() throws Exception {
        HttpResponse<String> response = post("/graph/path",
                "{\"fromUid\":\"from\",\"toUid\":\"to\",\"maxDepth\":6}");

        assertEquals(200, response.statusCode());
        assertEquals(QuerySpec.Type.PATH, queryService.lastSpec.getType());
        assertEquals("from", queryService.lastSpec.getStartUid());
        assertEquals("to", queryService.lastSpec.getTargetUid());
        assertEquals(6, queryService.lastSpec.getDepth());
        assertEquals(QuerySpec.Direction.OUTGOING, queryService.lastSpec.getDirection());
    }

    @Test
    void unifiedQueryUsesExistingQuerySpecShape() throws Exception {
        HttpResponse<String> response = post("/graph/query",
                "{\"type\":\"K_HOP\",\"startUid\":\"start\",\"depth\":2,\"direction\":\"BOTH\"}");

        assertEquals(200, response.statusCode());
        assertEquals(QuerySpec.Type.K_HOP, queryService.lastSpec.getType());
        assertEquals("start", queryService.lastSpec.getStartUid());
    }

    @Test
    void namedEndpointBuildsExistingNamedQuerySpecAndRejectsUnknownFields() throws Exception {
        HttpResponse<String> response = post("/graph/named",
                "{\"queryId\":\"route-two-hop\",\"startUid\":\"start\"}");
        HttpResponse<String> unknown = post("/graph/named",
                "{\"queryId\":\"route-two-hop\",\"startUid\":\"start\",\"cypher\":\"RETURN 1\"}");

        assertEquals(200, response.statusCode());
        assertEquals(QuerySpec.Type.NAMED, queryService.lastSpec.getType());
        assertEquals("route-two-hop", queryService.lastSpec.getQueryId());
        assertEquals("start", queryService.lastSpec.getStartUid());
        assertEquals(400, unknown.statusCode());
    }

    @Test
    void cypherEndpointAcceptsOnlyCypherFieldAndReturnsRowsPlusGraph() throws Exception {
        server.close();
        GraphDTO graph = new GraphDTO("1",
                List.of(new GraphNodeDTO("stable-1", List.of("Airport"), "Airport", "A", Map.of("name", "A"))),
                List.of(), Map.of("queryType", "CYPHER", "complete", true));
        CypherResultDTO result = new CypherResultDTO("1", List.of("total"),
                List.of(Map.of("total", 1L)), graph,
                Map.of("queryType", "CYPHER", "rowCount", 1, "nodeCount", 1,
                        "relationshipCount", 0, "complete", true));
        server = new KgApiServer(config(100, 100), queryService, cypher -> result, schema(), () -> true);
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1");

        HttpResponse<String> response = post("/graph/cypher", "{\"cypher\":\"MATCH (n) RETURN n LIMIT 1\"}");
        HttpResponse<String> unknown = post("/graph/cypher", "{\"cypher\":\"MATCH (n) RETURN n\",\"params\":{}}");

        assertEquals(200, response.statusCode());
        assertEquals(List.of("total"), JSON.convertValue(json(response).get("columns"), List.class));
        assertEquals(1L, json(response).get("rows").get(0).get("total").asLong());
        assertEquals("stable-1", json(response).get("graph").get("nodes").get(0).get("id").asText());
        assertEquals(400, unknown.statusCode());
        assertEquals("INVALID_REQUEST", json(unknown).get("code").asText());
    }

    @Test
    void schemaComesFromLoadedOntology() throws Exception {
        HttpResponse<String> response = get("/schema");

        assertEquals(200, response.statusCode());
        assertEquals("1", json(response).get("schemaVersion").asText());
        assertEquals("urn:test:Class", json(response).get("classes").get(0).asText());
        assertTrue(json(response).get("datatypeProperties").toString().contains("urn:test:name"));
        assertEquals("urn:test:rel", json(response).get("objectProperties").get(0).asText());
        assertEquals("测试类", json(response).get("classLabels").get("urn:test:Class").asText());
        assertEquals("名称", json(response).get("datatypePropertyLabels").get("urn:test:name").asText());
        assertEquals("noLabel", json(response).get("datatypePropertyLabels").get("urn:test:noLabel").asText());
        assertEquals("关系", json(response).get("objectPropertyLabels").get("urn:test:rel").asText());
    }

    @Test
    void optionalStaticViewerServesGetAndHeadButBlocksTraversal() throws Exception {
        server.close();
        Files.writeString(tempDir.resolve("index.html"), "<h1>viewer</h1>", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("app.js"), "console.log('viewer')", StandardCharsets.UTF_8);
        server = new KgApiServer(config(100, 100), queryService, schema(), () -> true);
        server.mountStatic("/viewer", tempDir);
        server.start();
        URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());

        HttpResponse<String> index = client.send(HttpRequest.newBuilder(origin.resolve("/viewer/")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        HttpResponse<Void> head = client.send(HttpRequest.newBuilder(origin.resolve("/viewer/app.js"))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());
        HttpResponse<String> traversal = client.send(HttpRequest.newBuilder(
                        URI.create(origin + "/viewer/%2e%2e/pom.xml")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, index.statusCode());
        assertTrue(index.body().contains("viewer"));
        assertEquals("text/html; charset=utf-8", index.headers().firstValue("Content-Type").orElseThrow());
        assertEquals(200, head.statusCode());
        assertEquals(404, traversal.statusCode());
        assertFalse(traversal.body().contains("<project"));
    }

    @Test
    void invalidDepthAndMalformedJsonReturnInvalidRequestWithoutInternals() throws Exception {
        HttpResponse<String> invalidDepth = post("/graph/k-hop", "{\"uid\":\"x\",\"depth\":0}");
        HttpResponse<String> malformed = post("/graph/one-hop", "{");

        assertEquals(400, invalidDepth.statusCode());
        assertEquals("INVALID_DEPTH", json(invalidDepth).get("code").asText());
        assertEquals(400, malformed.statusCode());
        assertEquals("INVALID_REQUEST", json(malformed).get("code").asText());
        assertFalse(malformed.body().contains("JsonEOFException"));
    }

    @Test
    void queryFailureAndOversizedResultUseSafeExplicitErrors() throws Exception {
        queryService.failure = new QueryExecutionException("MATCH secret cypher at bolt://internal");
        HttpResponse<String> failed = post("/graph/one-hop", "{\"uid\":\"x\"}");
        assertEquals(500, failed.statusCode());
        assertEquals("QUERY_FAILED", json(failed).get("code").asText());
        assertFalse(failed.body().contains("MATCH secret"));
        assertFalse(failed.body().contains("bolt://"));

        server.close();
        queryService.failure = null;
        server = new KgApiServer(config(1, 1), queryService, schema(), () -> true);
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1");
        HttpResponse<String> tooLarge = post("/graph/one-hop", "{\"uid\":\"x\"}");
        assertEquals(413, tooLarge.statusCode());
        assertEquals("RESULT_TOO_LARGE", json(tooLarge).get("code").asText());
        assertEquals(2, json(tooLarge).get("details").get("nodeCount").asInt());
    }

    private ApiConfig config(int maxNodes, int maxRelationships) {
        return new ApiConfig("127.0.0.1", 0, "/api/v1", "1", 8,
                65536, maxNodes, maxRelationships);
    }

    private OntologySchema schema() {
        OntologyTerm clazz = new OntologyTerm("urn:test:Class", "测试类", Set.of(), Set.of(), Set.of());
        OntologyTerm name = new OntologyTerm(
                "urn:test:name", "名称", Set.of("urn:test:Class"), Set.of(), Set.of());
        OntologyTerm noLabel = new OntologyTerm(
                "urn:test:noLabel", null, Set.of("urn:test:Class"), Set.of(), Set.of());
        OntologyTerm relationship = new OntologyTerm(
                "urn:test:rel", "关系", Set.of("urn:test:Class"), Set.of("urn:test:Class"), Set.of());
        return new OntologySchema(Map.of(clazz.getIri(), clazz),
                Map.of(name.getIri(), name, noLabel.getIri(), noLabel), Map.of(relationship.getIri(), relationship));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(baseUri.resolve(baseUri.getPath() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(baseUri.getPath() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body());
    }

    private String pathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static final class RecordingQueryService implements QueryService {
        QuerySpec lastSpec;
        RuntimeException failure;

        @Override
        public GraphDTO query(QuerySpec spec) {
            lastSpec = spec;
            if (failure != null) throw failure;
            if (spec.getType() == QuerySpec.Type.ENTITY && "missing".equals(spec.getStartUid())) {
                return graph(List.of(), List.of(), spec);
            }
            GraphNodeDTO first = new GraphNodeDTO(spec.getStartUid(), List.of("Class"), "Class", "first", Map.of());
            GraphNodeDTO second = new GraphNodeDTO("node-2", List.of("Class"), "Class", "second", Map.of());
            GraphRelationshipDTO relationship = new GraphRelationshipDTO(
                    "rel-1", first.getId(), second.getId(), "REL", Map.of());
            if (spec.getType() == QuerySpec.Type.ENTITY) return graph(List.of(first), List.of(), spec);
            return graph(List.of(first, second), List.of(relationship), spec);
        }

        private GraphDTO graph(List<GraphNodeDTO> nodes, List<GraphRelationshipDTO> relationships, QuerySpec spec) {
            return new GraphDTO("1", nodes, relationships,
                    Map.of("queryType", spec.getType().name(), "nodeCount", nodes.size(),
                            "relationshipCount", relationships.size(), "complete", true));
        }
    }

    private static final class RecordingEntityLookupService implements EntityLookupService {
        String lastKey;
        String lastClassIri;

        @Override
        public GraphDTO lookup(String key, String classIri) {
            lastKey = key;
            lastClassIri = classIri;
            List<GraphNodeDTO> nodes = "missing".equals(key)
                    ? List.of()
                    : List.of(new GraphNodeDTO("uid-" + key, List.of("Airport"), "Airport", key, Map.of()));
            return new GraphDTO("1", nodes, List.of(), Map.of(
                    "queryType", "ENTITY_LOOKUP", "nodeCount", nodes.size(),
                    "relationshipCount", 0, "complete", true));
        }
    }
}
