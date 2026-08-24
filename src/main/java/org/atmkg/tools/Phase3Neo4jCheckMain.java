package org.atmkg.tools;

import static org.neo4j.driver.Values.parameters;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.atmkg.core.ProjectConstants;
import org.atmkg.core.error.GraphStoreException;
import org.atmkg.core.model.ChangeEvent;
import org.atmkg.core.model.GraphStoreStats;
import org.atmkg.core.model.SourceRef;
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
import org.atmkg.infra.ontology.JenaOntologyService;
import org.atmkg.service.sync.DefaultSyncService;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;

/** Explicit real-Neo4j gate for Phase 3 source-projection reconciliation. */
public final class Phase3Neo4jCheckMain {
    private static final String NS = "urn:atm-knowledge-graph:";
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

    private Phase3Neo4jCheckMain() {}

    public static void main(String[] args) {
        Path root = args.length == 0
                ? Path.of(".").toAbsolutePath().normalize()
                : Path.of(args[0]).toAbsolutePath().normalize();
        Neo4jConnectionSettings settings = Neo4jConnectionSettings.fromEnvironment(
                ProjectConstants.PROJECT_ID, 500);
        var schema = new JenaOntologyService().load(root.resolve("ontology/atm_knowledge_graph.ttl"));
        MappingCatalog catalog = new PoiMappingRegistry().load(
                root.resolve("fixtures/mapping/fixture_mapping.xlsx"), schema);
        DeterministicIdentityResolver ids =
                new DeterministicIdentityResolver(ProjectConstants.IDENTITY_NAMESPACE);
        DefaultMappingEngine mapping = new DefaultMappingEngine(catalog, ids);
        CsvFixtureSourceAdapter base = new CsvFixtureSourceAdapter(
                "fixture", root.resolve("fixtures/generated/small"), KEYS);
        CsvFixtureSourceAdapter changed = new CsvFixtureSourceAdapter(
                "fixture", root.resolve("fixtures/generated/small/changed"), KEYS);

        try (Driver driver = Neo4jDriverFactory.create(settings)) {
            driver.verifyConnectivity();
            Neo4jGraphStore store = new Neo4jGraphStore(driver, settings, schema);
            store.initializeSchema();
            DefaultSyncService baseSync = new DefaultSyncService(Map.of("fixture", base), mapping, store);
            baseSync.fullRebuild(OBJECTS.stream().map(name -> new SourceScope("fixture", name)).toList());
            GraphStoreStats initial = store.stats();
            require(initial.getEntityCount() == 122, "base fixture 实体数应为 122");
            require(initial.getRelationshipCount() == 186, "base fixture 关系数应为 186");

            EntityMappingSpec airport = entityMapping(catalog, "Airport");
            EntityMappingSpec runway = entityMapping(catalog, "Runway");
            EntityMappingSpec routeNode = entityMapping(catalog, "RouteNode");
            EntityMappingSpec reportingPoint = entityMapping(catalog, "ReportingPoint");
            EntityMappingSpec controlArea = entityMapping(catalog, "ControlArea");
            EntityMappingSpec airspace = entityMapping(catalog, "Airspace");
            EntityMappingSpec airspaceGeometry = entityMapping(catalog, "AirspaceGeometry");
            String z001 = ids.entityUid(airport, "Z001");
            String z002 = ids.entityUid(airport, "Z002");
            String z999 = ids.entityUid(airport, "Z999");
            String changedRunway = ids.entityUid(runway, "Z001-01/19");
            String insertedRunway = ids.entityUid(runway, "Z999-01/19");
            String changedNode = ids.entityUid(routeNode, "R003:N005");
            String oldNextNode = ids.entityUid(routeNode, "R003:N006");
            String missedReportingPoint = ids.entityUid(reportingPoint, "RPT002");
            String deletedControlArea = ids.entityUid(controlArea, "CTA003");
            String protectedAirspace = ids.entityUid(airspace, "AS0003");
            String protectedGeometry = ids.entityUid(airspaceGeometry, "AS0003:G001");

            try (Session session = driver.session(SessionConfig.forDatabase(settings.getDatabase()))) {
                require(propertyEquals(session, settings.getProjectId(), z001, NS + "nameZh", "模拟机场1"),
                        "Z001 初始名称不正确");
                require(propertyEquals(session, settings.getProjectId(), changedNode, NS + "nodeName", "模拟节点5"),
                        "R003:N005 初始可选属性不存在");
                require(relationshipExists(session, settings.getProjectId(), NS + "hasRunway", "HAS_RUNWAY",
                        z001, changedRunway), "变化前 Z001 HAS_RUNWAY 关系不存在");
                require(relationshipExists(session, settings.getProjectId(), NS + "nextNode", "NEXT_NODE",
                        changedNode, oldNextNode), "变化前 R003:N005 NEXT_NODE 关系不存在");
            }

            // AS0003 is still referenced by a relationship owned by its geometry record.
            // Deleting it must fail atomically rather than detach another record's projection.
            try {
                store.deleteProjection(new SourceRef("fixture", "AIRSPACE", "AS0003"));
                throw new IllegalStateException("跨记录所有权删除未被拒绝");
            } catch (GraphStoreException expected) {
                require(containsMessage(expected, "拒绝删除仍被其他源记录关系引用的实体"),
                        "AS0003 删除失败原因不是 source ownership 冲突");
                require(store.findEntity(protectedAirspace).isPresent(), "所有权冲突回滚后 AS0003 不应消失");
                try (Session session = driver.session(SessionConfig.forDatabase(settings.getDatabase()))) {
                    require(relationshipExists(session, settings.getProjectId(), NS + "hasGeometry", "HAS_GEOMETRY",
                            protectedAirspace, protectedGeometry), "所有权冲突回滚后 HAS_GEOMETRY 不应消失");
                }
            }

            DefaultSyncService sync = new DefaultSyncService(Map.of("fixture", changed), mapping, store);
            List<ChangeEvent> events = new FixtureChangeEventReader().read(
                    root.resolve("fixtures/generated/small/changes.csv"));
            for (ChangeEvent event : events) sync.handle(event);
            GraphStoreStats afterEvents = store.stats();

            // Same event ID must be ignored after the first successful processing.
            sync.handle(events.get(0));
            require(sameStats(afterEvents, store.stats()), "重复 eventId 改变了图统计");

            try (Session session = driver.session(SessionConfig.forDatabase(settings.getDatabase()))) {
                require(entityExists(session, settings.getProjectId(), z999), "新增机场 Z999 不存在");
                require(entityExists(session, settings.getProjectId(), insertedRunway), "新增跑道 Z999-01/19 不存在");
                require(propertyEquals(session, settings.getProjectId(), insertedRunway, NS + "length", 2800.0),
                        "新增跑道属性不正确");
                require(relationshipExists(session, settings.getProjectId(), NS + "hasRunway", "HAS_RUNWAY",
                        z999, insertedRunway), "新增跑道应有 HAS_RUNWAY 关系");
                require(propertyEquals(session, settings.getProjectId(), z001, NS + "nameZh", "模拟机场1-已更新"),
                        "Z001 新属性值不存在");
                require(!propertyEquals(session, settings.getProjectId(), z001, NS + "nameZh", "模拟机场1"),
                        "Z001 旧属性值仍存在");
                require(relationshipExists(session, settings.getProjectId(), NS + "hasRunway", "HAS_RUNWAY",
                        z002, changedRunway), "引用更新后的 Z002 HAS_RUNWAY 关系不存在");
                require(!relationshipExists(session, settings.getProjectId(), NS + "hasRunway", "HAS_RUNWAY",
                        z001, changedRunway), "引用更新前的 Z001 HAS_RUNWAY 关系仍存在");
                require(!propertyExists(session, settings.getProjectId(), changedNode, NS + "nodeName"),
                        "已清空的 nodeName 属性仍存在");
                require(!relationshipExists(session, settings.getProjectId(), NS + "nextNode", "NEXT_NODE",
                        changedNode, oldNextNode), "已清空的 NEXT_NODE 关系仍存在");
                require(!entityExists(session, settings.getProjectId(), deletedControlArea), "CTA003 删除失败");
                require(propertyEquals(session, settings.getProjectId(), missedReportingPoint,
                        NS + "reportingPointName", "模拟报告点2"), "漏事件记录不应提前变化");
            }

            sync.resync("fixture", "RUNWAY", "Z001-01/19");
            require(sameStats(afterEvents, store.stats()), "重复 record resync 改变了图统计");

            sync.compensateSince("fixture", "REPORTING_POINT", Instant.EPOCH);
            GraphStoreStats finalStats = store.stats();
            require(sameStats(afterEvents, finalStats), "补偿扫描改变了无关图统计");
            long duplicateUidGroups;
            try (Session session = driver.session(SessionConfig.forDatabase(settings.getDatabase()))) {
                require(propertyEquals(session, settings.getProjectId(), missedReportingPoint,
                        NS + "reportingPointName", "模拟报告点2-补偿更新"), "补偿扫描未恢复 RPT002");
                duplicateUidGroups = duplicateUidGroups(session, settings.getProjectId());
                require(duplicateUidGroups == 0, "kg_uid 存在重复分组");
            }

            System.out.println("PHASE3_SYNC_OK");
            System.out.println("initial_entities=" + initial.getEntityCount());
            System.out.println("initial_relationships=" + initial.getRelationshipCount());
            System.out.println("insert=PASS");
            System.out.println("property_update=PASS");
            System.out.println("reference_update=PASS");
            System.out.println("stale_relationship_removed=PASS");
            System.out.println("stale_property_removed=PASS");
            System.out.println("record_delete=PASS");
            System.out.println("duplicate_event_idempotent=PASS");
            System.out.println("record_resync_idempotent=PASS");
            System.out.println("compensation_scan_recovered=PASS");
            System.out.println("duplicate_uid_groups=" + duplicateUidGroups);
            System.out.println("final_entities=" + finalStats.getEntityCount());
            System.out.println("final_relationships=" + finalStats.getRelationshipCount());
        }
    }

    private static EntityMappingSpec entityMapping(MappingCatalog catalog, String localName) {
        return catalog.uniqueEntityMapping("fixture", NS + localName).orElseThrow();
    }

    private static boolean entityExists(Session session, String project, String uid) {
        return session.executeRead(tx -> tx.run(
                "MATCH (n:KGEntity {kg_project: $project, kg_uid: $uid}) RETURN count(n) AS c",
                parameters("project", project, "uid", uid)).single().get("c").asLong() == 1);
    }

    private static boolean propertyExists(Session session, String project, String uid, String property) {
        return session.executeRead(tx -> tx.run(
                "MATCH (n:KGEntity {kg_project: $project, kg_uid: $uid}) RETURN $property IN keys(n) AS present",
                parameters("project", project, "uid", uid, "property", property))
                .single().get("present").asBoolean());
    }

    private static boolean propertyEquals(Session session, String project, String uid, String property, Object expected) {
        return session.executeRead(tx -> tx.run(
                "MATCH (n:KGEntity {kg_project: $project, kg_uid: $uid}) RETURN n[$property] = $expected AS matches",
                parameters("project", project, "uid", uid, "property", property, "expected", expected))
                .single().get("matches").asBoolean(false));
    }

    private static boolean relationshipExists(Session session, String project, String predicate, String type,
                                              String sourceUid, String targetUid) {
        return session.executeRead(tx -> tx.run(
                "MATCH (s:KGEntity {kg_project: $project, kg_uid: $sourceUid})-[r]->" +
                        "(t:KGEntity {kg_project: $project, kg_uid: $targetUid}) " +
                        "WHERE r.kg_project = $project AND r.kg_predicate_iri = $predicate AND type(r) = $type " +
                        "RETURN count(r) AS c",
                parameters("project", project, "sourceUid", sourceUid, "targetUid", targetUid,
                        "predicate", predicate, "type", type)).single().get("c").asLong() == 1);
    }

    private static long duplicateUidGroups(Session session, String project) {
        long nodeGroups = session.executeRead(tx -> tx.run(
                "MATCH (n:KGEntity {kg_project: $project}) WITH n.kg_uid AS uid, count(*) AS c " +
                        "WHERE c > 1 RETURN count(*) AS c", parameters("project", project))
                .single().get("c").asLong());
        long relationshipGroups = session.executeRead(tx -> tx.run(
                "MATCH ()-[r {kg_project: $project}]->() WITH r.kg_uid AS uid, count(*) AS c " +
                        "WHERE c > 1 RETURN count(*) AS c", parameters("project", project))
                .single().get("c").asLong());
        return nodeGroups + relationshipGroups;
    }

    private static boolean sameStats(GraphStoreStats left, GraphStoreStats right) {
        return left.getEntityCount() == right.getEntityCount()
                && left.getRelationshipCount() == right.getRelationshipCount();
    }

    private static boolean containsMessage(Throwable error, String fragment) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(fragment)) return true;
        }
        return false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
