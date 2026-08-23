package org.atmkg.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.atmkg.api.http.ApiConfig;
import org.atmkg.api.http.KgApiServer;
import org.atmkg.core.model.SourceScope;
import org.atmkg.core.model.mapping.EntityMappingSpec;
import org.atmkg.core.model.mapping.MappingCatalog;
import org.atmkg.fixture.CsvFixtureSourceAdapter;
import org.atmkg.infra.identity.DeterministicIdentityResolver;
import org.atmkg.infra.mapping.DefaultMappingEngine;
import org.atmkg.infra.mapping.PoiMappingRegistry;
import org.atmkg.infra.neo4j.Neo4jConnectionSettings;
import org.atmkg.infra.neo4j.Neo4jDriverFactory;
import org.atmkg.infra.neo4j.Neo4jGraphStore;
import org.atmkg.infra.ontology.JenaOntologyService;
import org.atmkg.infra.query.Neo4jQueryService;
import org.atmkg.service.sync.DefaultSyncService;
import org.neo4j.driver.Driver;

/** Real HTTP -> QueryService -> Neo4j -> GraphDTO JSON acceptance gate. */
public final class Phase4Neo4jApiCheckMain {
    private static final String NS = "urn:atm-knowledge-graph:";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> OBJECTS = List.of(
            "AIRPORT", "RUNWAY", "RUNWAY_DIRECTION", "NAVIGATION_AID", "REPORTING_POINT",
            "ROUTE", "SCHEDULED_FLIGHT_ROUTE", "ROUTE_NODE", "ROUTE_SEGMENT", "AIRSPACE",
            "AIRSPACE_GEOMETRY", "BOUNDARY_POINT", "CONTROL_AREA", "FLIGHT_INFORMATION_REGION");
    private static final Map<String, String> KEYS = Map.ofEntries(
            Map.entry("AIRPORT", "airportCode"), Map.entry("RUNWAY", "runwayCode"),
            Map.entry("RUNWAY_DIRECTION", "directionKey"), Map.entry("NAVIGATION_AID", "navigationAidCode"),
            Map.entry("REPORTING_POINT", "reportingPointCode"), Map.entry("ROUTE", "routeCode"),
            Map.entry("SCHEDULED_FLIGHT_ROUTE", "scheduledRouteCode"), Map.entry("ROUTE_NODE", "nodeKey"),
            Map.entry("ROUTE_SEGMENT", "segmentKey"), Map.entry("AIRSPACE", "airspaceCode"),
            Map.entry("AIRSPACE_GEOMETRY", "geometryKey"), Map.entry("BOUNDARY_POINT", "boundaryPointKey"),
            Map.entry("CONTROL_AREA", "controlAreaCode"),
            Map.entry("FLIGHT_INFORMATION_REGION", "flightInformationRegionCode"));

    private Phase4Neo4jApiCheckMain() {}

    public static void main(String[] args) {
        Path root = args.length == 0
                ? Path.of(".").toAbsolutePath().normalize()
                : Path.of(args[0]).toAbsolutePath().normalize();
        Neo4jConnectionSettings neo4j = Neo4jConnectionSettings.fromEnvironment("atm-knowledge-graph", 500);
        var schema = new JenaOntologyService().load(root.resolve("ontology/atm_knowledge_graph.ttl"));
        MappingCatalog catalog = new PoiMappingRegistry().load(
                root.resolve("fixtures/mapping/fixture_mapping.xlsx"), schema);
        DeterministicIdentityResolver ids = new DeterministicIdentityResolver(NS);
        DefaultMappingEngine mapping = new DefaultMappingEngine(catalog, ids);
        CsvFixtureSourceAdapter fixture = new CsvFixtureSourceAdapter(
                "fixture", root.resolve("fixtures/generated/small"), KEYS);

        try (Driver driver = Neo4jDriverFactory.create(neo4j)) {
            driver.verifyConnectivity();
            Neo4jGraphStore store = new Neo4jGraphStore(driver, neo4j, schema);
            store.initializeSchema();
            new DefaultSyncService(Map.of("fixture", fixture), mapping, store).fullRebuild(
                    OBJECTS.stream().map(name -> new SourceScope("fixture", name)).toList());

            ApiConfig api = new ApiConfig("127.0.0.1", 0, "/api/v1", "1", 8,
                    65536, 10000, 50000);
            Neo4jQueryService query = new Neo4jQueryService(driver, neo4j, api.getSchemaVersion());
            try (KgApiServer server = new KgApiServer(api, query, schema, () -> {
                try {
                    driver.verifyConnectivity();
                    return true;
                } catch (RuntimeException ex) {
                    return false;
                }
            })) {
                server.start();
                URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + api.getBasePath());
                HttpClient client = HttpClient.newHttpClient();

                String z001 = uid(catalog, ids, "Airport", "Z001");
                String r001 = uid(catalog, ids, "Route", "R001");
                String r001n001 = uid(catalog, ids, "RouteNode", "R001:N001");
                String r001n006 = uid(catalog, ids, "RouteNode", "R001:N006");
                String as0001 = uid(catalog, ids, "Airspace", "AS0001");

                HttpResponse<String> healthResponse = get(client, base, "/health");
                JsonNode health = json(healthResponse);
                require(healthResponse.statusCode() == 200
                        && "UP".equals(health.path("status").asText())
                        && "UP".equals(health.path("neo4j").asText()), "health 失败");

                HttpResponse<String> entityResponse = get(client, base, "/entities/" + pathSegment(z001));
                JsonNode entity = json(entityResponse);
                require(entityResponse.statusCode() == 200 && entity.path("nodes").size() == 1, "entity 失败");
                require(z001.equals(entity.path("nodes").get(0).path("id").asText()), "entity 未使用稳定 UID");

                HttpResponse<String> oneHopResponse = post(client, base, "/graph/one-hop", Map.of("uid", z001));
                JsonNode oneHop = json(oneHopResponse);
                require(oneHopResponse.statusCode() == 200, "one-hop HTTP 失败");
                require(oneHop.path("nodes").size() == 3 && oneHop.path("relationships").size() == 2,
                        "Z001 一跳应为 3 节点/2 关系");
                require(hasLabel(oneHop, z001, "Airport") && hasLabel(oneHop, z001, "AviationBaseObject"),
                        "Z001 GraphDTO labels 未反映 ontology labels");
                require(hasRelationshipType(oneHop, "HAS_RUNWAY"), "Z001 GraphDTO 缺少 HAS_RUNWAY type");

                HttpResponse<String> routeResponse = post(client, base, "/graph/k-hop",
                        Map.of("uid", r001, "depth", 2, "relationshipTypes", List.of(),
                                "classFilters", List.of(), "direction", "BOTH"));
                JsonNode route = json(routeResponse);
                require(routeResponse.statusCode() == 200, "R001 K=2 HTTP 失败");
                require(route.path("nodes").size() == 14 && route.path("relationships").size() == 28,
                        "R001 K=2 应为 14 节点/28 关系");

                HttpResponse<String> airspaceResponse = post(client, base, "/graph/k-hop",
                        Map.of("uid", as0001, "depth", 2, "relationshipTypes", List.of(),
                                "classFilters", List.of(), "direction", "BOTH"));
                JsonNode airspace = json(airspaceResponse);
                require(airspaceResponse.statusCode() == 200, "AS0001 K=2 HTTP 失败");
                require(airspace.path("nodes").size() == 5 && airspace.path("relationships").size() == 4,
                        "AS0001 K=2 应为 5 节点/4 关系");
                require(hasRelationshipType(airspace, "HAS_GEOMETRY")
                        && hasRelationshipType(airspace, "HAS_BOUNDARY_POINT"), "AS0001 结构关系不完整");

                HttpResponse<String> pathResponse = post(client, base, "/graph/path",
                        Map.of("fromUid", r001n001, "toUid", r001n006, "maxDepth", 6));
                JsonNode path = json(pathResponse);
                require(pathResponse.statusCode() == 200, "path HTTP 失败");
                require(path.path("nodes").size() == 6 && path.path("relationships").size() == 5,
                        "R001 path 应为 6 节点/5 关系");

                HttpResponse<String> schemaResponse = get(client, base, "/schema");
                JsonNode schemaJson = json(schemaResponse);
                require(schemaResponse.statusCode() == 200
                        && schemaJson.path("classes").size() == schema.getClasses().size()
                        && schemaJson.path("objectProperties").size() == schema.getObjectProperties().size(),
                        "schema endpoint 失败");

                HttpResponse<String> errorResponse = post(client, base, "/graph/k-hop",
                        Map.of("uid", z001, "depth", 0));
                JsonNode error = json(errorResponse);
                require(errorResponse.statusCode() == 400
                        && "INVALID_DEPTH".equals(error.path("code").asText())
                        && error.has("message") && error.has("details"), "错误模型失败");

                assertStableIds(oneHop);
                String allJson = oneHopResponse.body() + routeResponse.body() + airspaceResponse.body()
                        + pathResponse.body() + entityResponse.body();
                require(!allJson.contains("elementId") && !allJson.contains("\"identity\"")
                        && !allJson.contains("neo4j://"), "响应泄漏 Neo4j internal id/细节");

                System.out.println("PHASE4_API_OK");
                System.out.println("health=PASS");
                System.out.println("entity=PASS");
                System.out.println("one_hop=PASS");
                System.out.println("k_hop=PASS");
                System.out.println("path=PASS");
                System.out.println("schema=PASS");
                System.out.println("graphdto_stable_ids=PASS");
                System.out.println("neo4j_internal_ids_absent=PASS");
                System.out.println("error_model=PASS");
                System.out.println("z001_one_hop_nodes=" + oneHop.path("nodes").size());
                System.out.println("z001_one_hop_relationships=" + oneHop.path("relationships").size());
                System.out.println("r001_k2_nodes=" + route.path("nodes").size());
                System.out.println("r001_k2_relationships=" + route.path("relationships").size());
                System.out.println("as0001_k2_nodes=" + airspace.path("nodes").size());
                System.out.println("as0001_k2_relationships=" + airspace.path("relationships").size());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Phase 4 Gate 被中断", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Phase 4 Gate 失败", ex);
        }
    }

    private static String uid(MappingCatalog catalog, DeterministicIdentityResolver ids,
                              String className, String sourceKey) {
        EntityMappingSpec mapping = catalog.uniqueEntityMapping("fixture", NS + className).orElseThrow();
        return ids.entityUid(mapping, sourceKey);
    }

    private static HttpResponse<String> get(HttpClient client, URI base, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(endpoint(base, path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> post(HttpClient client, URI base, String path, Object body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(endpoint(base, path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static URI endpoint(URI base, String path) {
        return URI.create(base.getScheme() + "://" + base.getAuthority() + base.getPath() + path);
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body());
    }

    private static String pathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static boolean hasLabel(JsonNode graph, String uid, String label) {
        for (JsonNode node : graph.path("nodes")) {
            if (uid.equals(node.path("id").asText())) {
                for (JsonNode value : node.path("labels")) if (label.equals(value.asText())) return true;
            }
        }
        return false;
    }

    private static boolean hasRelationshipType(JsonNode graph, String type) {
        for (JsonNode relationship : graph.path("relationships")) {
            if (type.equals(relationship.path("type").asText())) return true;
        }
        return false;
    }

    private static void assertStableIds(JsonNode graph) {
        Set<String> nodeIds = new HashSet<>();
        for (JsonNode node : graph.path("nodes")) {
            String id = node.path("id").asText();
            require(id.startsWith(NS + "entity:"), "节点未使用稳定 kg_uid：" + id);
            nodeIds.add(id);
        }
        for (JsonNode relationship : graph.path("relationships")) {
            String id = relationship.path("id").asText();
            require(id.startsWith(NS + "rel:"), "关系未使用稳定 kg_uid：" + id);
            require(nodeIds.contains(relationship.path("source").asText())
                    && nodeIds.contains(relationship.path("target").asText()), "关系端点未使用 GraphDTO node.id");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
