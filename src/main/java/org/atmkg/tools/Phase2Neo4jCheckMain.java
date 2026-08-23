package org.atmkg.tools;

import static org.neo4j.driver.Values.parameters;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.atmkg.core.model.GraphDTO;
import org.atmkg.core.model.GraphStoreStats;
import org.atmkg.core.model.OntologySchema;
import org.atmkg.core.model.QuerySpec;
import org.atmkg.core.model.SourceScope;
import org.atmkg.core.model.mapping.EntityMappingSpec;
import org.atmkg.core.model.mapping.MappingCatalog;
import org.atmkg.fixture.CsvFixtureSourceAdapter;
import org.atmkg.fixture.FixtureChangeEventReader;
import org.atmkg.infra.identity.DeterministicIdentityResolver;
import org.atmkg.infra.mapping.DefaultMappingEngine;
import org.atmkg.infra.mapping.PoiMappingRegistry;
import org.atmkg.infra.neo4j.Neo4jConnectionSettings;
import org.atmkg.infra.neo4j.Neo4jDriverFactory;
import org.atmkg.infra.neo4j.Neo4jGraphStore;
import org.atmkg.infra.neo4j.Neo4jOntologyMetadata;
import org.atmkg.infra.ontology.JenaOntologyService;
import org.atmkg.infra.query.Neo4jQueryService;
import org.atmkg.service.sync.DefaultSyncService;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;

/** Phase 2 ontology coverage gate. */
public final class Phase2Neo4jCheckMain {
    private static final String NS = "urn:atm-knowledge-graph:";
    private static final List<String> OBJECTS = List.of("AIRPORT", "RUNWAY", "RUNWAY_DIRECTION", "NAVIGATION_AID", "REPORTING_POINT", "ROUTE", "SCHEDULED_FLIGHT_ROUTE", "ROUTE_NODE", "ROUTE_SEGMENT", "AIRSPACE", "AIRSPACE_GEOMETRY", "BOUNDARY_POINT", "CONTROL_AREA", "FLIGHT_INFORMATION_REGION");
    private static final Map<String, String> KEYS = Map.ofEntries(
            Map.entry("AIRPORT", "airportCode"), Map.entry("RUNWAY", "runwayCode"), Map.entry("RUNWAY_DIRECTION", "directionKey"), Map.entry("NAVIGATION_AID", "navigationAidCode"), Map.entry("REPORTING_POINT", "reportingPointCode"), Map.entry("ROUTE", "routeCode"), Map.entry("SCHEDULED_FLIGHT_ROUTE", "scheduledRouteCode"), Map.entry("ROUTE_NODE", "nodeKey"), Map.entry("ROUTE_SEGMENT", "segmentKey"), Map.entry("AIRSPACE", "airspaceCode"), Map.entry("AIRSPACE_GEOMETRY", "geometryKey"), Map.entry("BOUNDARY_POINT", "boundaryPointKey"), Map.entry("CONTROL_AREA", "controlAreaCode"), Map.entry("FLIGHT_INFORMATION_REGION", "flightInformationRegionCode"));
    private static final List<String> COVERED_CLASSES = List.of("Airport", "Runway", "RunwayDirection", "NavigationAid", "ReportingPoint", "Route", "ScheduledFlightRoute", "RouteNode", "RouteSegment", "Airspace", "AirspaceGeometry", "BoundaryPoint", "ControlArea", "FlightInformationRegion");
    private static final List<String> COVERED_RELATIONSHIPS = List.of("HAS_RUNWAY", "HAS_DIRECTION", "HAS_NODE", "HAS_SEGMENT", "NEXT_NODE", "FROM_NODE", "TO_NODE", "REFERS_TO", "HAS_GEOMETRY", "HAS_BOUNDARY_POINT");

    private Phase2Neo4jCheckMain() {}

    public static void main(String[] args) {
        Path root = args.length == 0 ? Path.of(".").toAbsolutePath().normalize() : Path.of(args[0]).toAbsolutePath().normalize();
        Neo4jConnectionSettings settings = Neo4jConnectionSettings.fromEnvironment("atm-knowledge-graph", 500);
        OntologySchema schema = new JenaOntologyService().load(root.resolve("ontology/atm_knowledge_graph.ttl"));
        Neo4jOntologyMetadata metadata = Neo4jOntologyMetadata.from(schema);
        MappingCatalog catalog = new PoiMappingRegistry().load(root.resolve("fixtures/mapping/fixture_mapping.xlsx"), schema);
        DeterministicIdentityResolver ids = new DeterministicIdentityResolver(NS);
        DefaultMappingEngine mapping = new DefaultMappingEngine(catalog, ids);
        CsvFixtureSourceAdapter base = new CsvFixtureSourceAdapter("fixture", root.resolve("fixtures/generated/small"), KEYS);
        CsvFixtureSourceAdapter changed = new CsvFixtureSourceAdapter("fixture", root.resolve("fixtures/generated/small/changed"), KEYS);
        try (Driver driver = Neo4jDriverFactory.create(settings)) {
            driver.verifyConnectivity();
            Neo4jGraphStore store = new Neo4jGraphStore(driver, settings, schema);
            Neo4jQueryService query = new Neo4jQueryService(driver, settings, "1");
            store.initializeSchema();
            DefaultSyncService baseSync = new DefaultSyncService(Map.of("fixture", base), mapping, store);
            List<SourceScope> scopes = OBJECTS.stream().map(name -> new SourceScope("fixture", name)).toList();
            baseSync.fullRebuild(scopes);
            GraphStoreStats first = store.stats();
            require(first.getEntityCount() > 0 && first.getRelationshipCount() > 0, "fixture 图不应为空");
            baseSync.fullRebuild(scopes);
            GraphStoreStats repeated = store.stats();
            require(first.getEntityCount() == repeated.getEntityCount(), "重复全量导入后实体数变化");
            require(first.getRelationshipCount() == repeated.getRelationshipCount(), "重复全量导入后关系数变化");
            try (Session session = driver.session(SessionConfig.forDatabase(settings.getDatabase()))) {
                validateCoverage(session, settings.getProjectId(), metadata, first);
            }

            EntityMappingSpec airport = catalog.uniqueEntityMapping("fixture", NS + "Airport").orElseThrow();
            EntityMappingSpec route = catalog.uniqueEntityMapping("fixture", NS + "Route").orElseThrow();
            EntityMappingSpec node = catalog.uniqueEntityMapping("fixture", NS + "RouteNode").orElseThrow();
            String z001 = ids.entityUid(airport, "Z001"), z002 = ids.entityUid(airport, "Z002");
            String r001 = ids.entityUid(route, "R001");
            String r001n001 = ids.entityUid(node, "R001:N001"), r001n006 = ids.entityUid(node, "R001:N006");
            GraphDTO z001OneHop = query.query(new QuerySpec(QuerySpec.Type.NEIGHBORS, z001, null, 1, Set.of(), Set.of(), QuerySpec.Direction.BOTH, null, Map.of()));
            GraphDTO z002OneHop = query.query(new QuerySpec(QuerySpec.Type.NEIGHBORS, z002, null, 1, Set.of(), Set.of(), QuerySpec.Direction.BOTH, null, Map.of()));
            GraphDTO r001TwoHop = query.query(new QuerySpec(QuerySpec.Type.K_HOP, r001, null, 2, Set.of(), Set.of(), QuerySpec.Direction.BOTH, null, Map.of()));
            GraphDTO r001Path = query.query(new QuerySpec(QuerySpec.Type.PATH, r001n001, r001n006, 5, Set.of(), Set.of(), QuerySpec.Direction.OUTGOING, null, Map.of()));
            require(z001OneHop.getNodes().size() >= 3, "Z001 一跳应包含机场及跑道");
            require(z002OneHop.getNodes().size() >= 3, "Z002 一跳应包含机场及跑道");
            require(r001TwoHop.getNodes().size() > 1 && r001TwoHop.getRelationships().size() > 0, "R001 两跳不应为空");
            require(r001Path.getNodes().size() == 6 && r001Path.getRelationships().size() == 5, "R001 节点路径应为 6 节点/5 关系");

            DefaultSyncService sync = new DefaultSyncService(Map.of("fixture", changed), mapping, store);
            for (var event : new FixtureChangeEventReader().read(root.resolve("fixtures/generated/small/changes.csv"))) sync.handle(event);
            GraphStoreStats after = store.stats();
            require(after.getEntityCount() == first.getEntityCount() + 1, "变更 fixture 后实体总数不符合预期");
            System.out.println("PHASE2_ONTOLOGY_GRAPH_OK");
            System.out.println("total_entities=" + first.getEntityCount());
            System.out.println("total_relationships=" + first.getRelationshipCount());
            System.out.println("z001_one_hop_nodes=" + z001OneHop.getNodes().size());
            System.out.println("z002_one_hop_nodes=" + z002OneHop.getNodes().size());
            System.out.println("r001_two_hop_nodes=" + r001TwoHop.getNodes().size());
            System.out.println("r001_two_hop_relationships=" + r001TwoHop.getRelationships().size());
            System.out.println("r001_path_nodes=" + r001Path.getNodes().size());
            System.out.println("r001_path_relationships=" + r001Path.getRelationships().size());
            System.out.println("locatedIn/crosses=SKIPPED_BY_ONTOLOGY_STATUS");
        }
    }

    private static void validateCoverage(Session session, String project, Neo4jOntologyMetadata metadata, GraphStoreStats stats) {
        List<Record> classRows = session.executeRead(tx -> tx.run("MATCH (n:KGEntity {kg_project: $project}) RETURN n.kg_class_iri AS iri, count(n) AS c", parameters("project", project)).list());
        Map<String, Long> classes = new LinkedHashMap<>();
        for (Record row : classRows) classes.put(localName(row.get("iri").asString()), row.get("c").asLong());
        for (String expected : COVERED_CLASSES) require(classes.getOrDefault(expected, 0L) > 0, "缺少 ontology class fixture：" + expected);
        List<Record> relationshipRows = session.executeRead(tx -> tx.run("MATCH ()-[r {kg_project: $project}]->() RETURN type(r) AS type, count(r) AS c", parameters("project", project)).list());
        Map<String, Long> relationships = new LinkedHashMap<>();
        for (Record row : relationshipRows) relationships.put(row.get("type").asString(), row.get("c").asLong());
        for (String expected : COVERED_RELATIONSHIPS) require(relationships.getOrDefault(expected, 0L) > 0, "缺少 ontology relationship fixture：" + expected);
        long duplicateNodes = session.executeRead(tx -> tx.run("MATCH (n:KGEntity {kg_project: $project}) WITH n.kg_uid AS uid, count(*) AS c WHERE c > 1 RETURN count(*) AS c", parameters("project", project)).single().get("c").asLong());
        long duplicateRelationships = session.executeRead(tx -> tx.run("MATCH ()-[r {kg_project: $project}]->() WITH r.kg_uid AS uid, count(*) AS c WHERE c > 1 RETURN count(*) AS c", parameters("project", project)).single().get("c").asLong());
        require(duplicateNodes == 0 && duplicateRelationships == 0, "kg_uid 重复检查失败");
        long orphans = session.executeRead(tx -> tx.run("MATCH ()-[r {kg_project: $project}]->() WHERE startNode(r).kg_project <> $project OR endNode(r).kg_project <> $project RETURN count(r) AS c", parameters("project", project)).single().get("c").asLong());
        require(orphans == 0, "关系存在跨项目悬空端点");
        Set<String> allowedLabels = new LinkedHashSet<>(metadata.allClassLabels());
        long foreignLabels = session.executeRead(tx -> tx.run("MATCH (n:KGEntity {kg_project: $project}) UNWIND labels(n) AS label WITH label WHERE NOT label IN $allowed AND label <> 'KGEntity' RETURN count(*) AS c", parameters("project", project, "allowed", new ArrayList<>(allowedLabels))).single().get("c").asLong());
        require(foreignLabels == 0, "产生 ontology 外 Neo4j Label");
        long foreignTypes = session.executeRead(tx -> tx.run("MATCH ()-[r {kg_project: $project}]->() WHERE NOT type(r) IN $allowed RETURN count(r) AS c", parameters("project", project, "allowed", new ArrayList<>(metadata.allRelationshipTypes()))).single().get("c").asLong());
        require(foreignTypes == 0, "产生 ontology 外 Neo4j Relationship Type");
        require(stats.getEntityCount() == classes.values().stream().mapToLong(Long::longValue).sum(), "实体统计不一致");
    }

    private static String localName(String iri) {
        int index = Math.max(iri.lastIndexOf('#'), Math.max(iri.lastIndexOf('/'), iri.lastIndexOf(':')));
        return index >= 0 ? iri.substring(index + 1) : iri;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
