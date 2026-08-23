package org.atmkg.infra.query;

import static org.neo4j.driver.Values.parameters;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.atmkg.core.error.QueryExecutionException;
import org.atmkg.core.model.GraphDTO;
import org.atmkg.core.model.GraphNodeDTO;
import org.atmkg.core.model.GraphRelationshipDTO;
import org.atmkg.core.model.QuerySpec;
import org.atmkg.core.spi.QueryService;
import org.atmkg.infra.neo4j.Neo4jConnectionSettings;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;

/**
 * 新增固定业务查询不要修改本类；在 {@code queries/query-templates.yaml} 复制 NEIGHBORS/K_HOP 模板，
 * 由 TemplateAwareQueryService 展开。API depth/结果上限改 {@code config/api.yaml}。
 *
 * <p>只有 ENTITY/NEIGHBORS/K_HOP/PATH 到参数化 Cypher 的通用语义或诱导子图加载有 bug 才写 Java。
 * 加 raw Cypher、LIMIT/top-N、空间推理或业务 if 会破坏 QueryService/GraphDTO 契约。Review 正常而 API
 * 查询失败时先比较 QuerySpec 的 direction/types/classes/depth 和 config/api 上限，再进入本类。
 */
public final class Neo4jQueryService implements QueryService {
    private static final String ENTITY_LABEL = "KGEntity";

    private final Driver driver;
    private final SessionConfig sessionConfig;
    private final String projectId;
    private final String schemaVersion;

    public Neo4jQueryService(Driver driver, Neo4jConnectionSettings settings, String schemaVersion) {
        this.driver = Objects.requireNonNull(driver, "driver");
        Objects.requireNonNull(settings, "settings");
        this.sessionConfig = SessionConfig.forDatabase(settings.getDatabase());
        this.projectId = settings.getProjectId();
        this.schemaVersion = requireText(schemaVersion, "schemaVersion");
    }

    @Override
    public GraphDTO query(QuerySpec spec) {
        Objects.requireNonNull(spec, "spec");
        try {
            switch (spec.getType()) {
                case ENTITY: return entity(spec);
                case NEIGHBORS: return neighbors(spec);
                case K_HOP: return kHop(spec);
                case PATH: return path(spec);
                case NAMED: throw new QueryExecutionException("命名查询将在外置 QueryRegistry 阶段实现；当前不可静默回退为任意 Cypher");
                default: throw new QueryExecutionException("不支持的查询类型：" + spec.getType());
            }
        } catch (QueryExecutionException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new QueryExecutionException("Neo4j 查询执行失败：" + spec.getType(), ex);
        }
    }

    private GraphDTO entity(QuerySpec spec) {
        String uid = requireText(spec.getStartUid(), "startUid");
        try (Session session = driver.session(sessionConfig)) {
            List<Record> rows = session.executeRead(tx -> tx.run(
                    "MATCH (n:" + ENTITY_LABEL + " {kg_project: $projectId, kg_uid: $uid}) " +
                    "RETURN properties(n) AS props, labels(n) AS labels",
                    parameters("projectId", projectId, "uid", uid)).list());
            if (rows.isEmpty()) return graph(List.of(), List.of(), spec, true);
            Record row = rows.get(0);
            return graph(List.of(nodeFromProps(row.get("props").asMap(),
                    row.get("labels").asList(value -> value.asString()))), List.of(), spec, true);
        }
    }

    private GraphDTO neighbors(QuerySpec spec) {
        String startUid = requireText(spec.getStartUid(), "startUid");
        Set<String> uids = new LinkedHashSet<>();
        uids.add(startUid);

        String pattern = directedPattern(spec.getDirection(), "s", "r", "n");
        String cypher = "MATCH " + pattern + " " +
                "WHERE s.kg_project = $projectId AND s.kg_uid = $startUid " +
                "AND n.kg_project = $projectId " + predicateFilter("r") + classFilter("n") +
                "RETURN DISTINCT n.kg_uid AS uid";

        try (Session session = driver.session(sessionConfig)) {
            session.executeRead(tx -> tx.run(cypher, commonParams(spec, startUid)).list())
                    .forEach(row -> uids.add(row.get("uid").asString()));
            return loadInducedSubgraph(session, uids, spec);
        }
    }

    private GraphDTO kHop(QuerySpec spec) {
        String startUid = requireText(spec.getStartUid(), "startUid");
        int depth = requireDepth(spec);
        Set<String> uids = new LinkedHashSet<>();
        uids.add(startUid);

        String pathPattern = pathPattern(spec.getDirection(), depth);
        String cypher = "MATCH p=" + pathPattern + " " +
                "WHERE s.kg_project = $projectId AND s.kg_uid = $startUid " +
                "AND n.kg_project = $projectId " + pathPredicateFilter() + pathClassFilter() +
                "RETURN DISTINCT n.kg_uid AS uid";

        try (Session session = driver.session(sessionConfig)) {
            session.executeRead(tx -> tx.run(cypher, commonParams(spec, startUid)).list())
                    .forEach(row -> uids.add(row.get("uid").asString()));
            return loadInducedSubgraph(session, uids, spec);
        }
    }

    private GraphDTO path(QuerySpec spec) {
        String startUid = requireText(spec.getStartUid(), "startUid");
        String targetUid = requireText(spec.getTargetUid(), "targetUid");
        int depth = requireDepth(spec);
        if (!spec.getClassFilters().isEmpty() || !spec.getRelationshipTypes().isEmpty()) {
            throw new QueryExecutionException("PATH 当前只执行无过滤最短路径；带类型过滤的路径语义留给命名查询显式定义");
        }

        String pathPattern = pathBetweenPattern(spec.getDirection(), depth);
        String cypher = "MATCH p=shortestPath(" + pathPattern + ") " +
                "WHERE s.kg_project = $projectId AND s.kg_uid = $startUid " +
                "AND t.kg_project = $projectId AND t.kg_uid = $targetUid " + pathPredicateFilter() +
                "RETURN [n IN nodes(p) | properties(n)] AS nodes, " +
                "[n IN nodes(p) | labels(n)] AS nodeLabels, " +
                "[r IN relationships(p) | {props: properties(r), source: startNode(r).kg_uid, " +
                "target: endNode(r).kg_uid, type: type(r)}] AS rels";

        Map<String, Object> params = commonParams(spec, startUid);
        params.put("targetUid", targetUid);
        try (Session session = driver.session(sessionConfig)) {
            List<Record> rows = session.executeRead(tx -> tx.run(cypher, params).list());
            if (rows.isEmpty()) return graph(List.of(), List.of(), spec, true);
            Record row = rows.get(0);
            List<GraphNodeDTO> nodes = new ArrayList<>();
            List<org.neo4j.driver.Value> nodeValues = new ArrayList<>();
            row.get("nodes").values().forEach(nodeValues::add);
            List<org.neo4j.driver.Value> labelValues = new ArrayList<>();
            row.get("nodeLabels").values().forEach(labelValues::add);
            for (int i = 0; i < nodeValues.size(); i++) {
                nodes.add(nodeFromProps(nodeValues.get(i).asMap(),
                        labelValues.get(i).asList(value -> value.asString())));
            }
            List<GraphRelationshipDTO> rels = new ArrayList<>();
            row.get("rels").values().forEach(v -> {
                Map<String, Object> rel = v.asMap();
                rels.add(relationshipFrom(
                        castMap(rel.get("props")),
                        String.valueOf(rel.get("source")),
                        String.valueOf(rel.get("target")),
                        String.valueOf(rel.get("type"))));
            });
            return graph(nodes, rels, spec, true);
        }
    }

    private GraphDTO loadInducedSubgraph(Session session, Set<String> uids, QuerySpec spec) {
        if (uids.isEmpty()) return graph(List.of(), List.of(), spec, true);
        List<String> uidList = List.copyOf(uids);
        List<Record> nodeRows = session.executeRead(tx -> tx.run(
                "MATCH (n:" + ENTITY_LABEL + " {kg_project: $projectId}) " +
                "WHERE n.kg_uid IN $uids RETURN properties(n) AS props, labels(n) AS labels ORDER BY n.kg_uid",
                parameters("projectId", projectId, "uids", uidList)).list());

        List<Record> relRows = session.executeRead(tx -> tx.run(
                "MATCH (a:" + ENTITY_LABEL + " {kg_project: $projectId})-[r]->" +
                "(b:" + ENTITY_LABEL + " {kg_project: $projectId}) " +
                "WHERE a.kg_uid IN $uids AND b.kg_uid IN $uids " +
                "RETURN properties(r) AS props, a.kg_uid AS source, b.kg_uid AS target, type(r) AS type " +
                "ORDER BY r.kg_uid",
                parameters("projectId", projectId, "uids", uidList)).list());

        List<GraphNodeDTO> nodes = nodeRows.stream().map(r -> nodeFromProps(
                r.get("props").asMap(), r.get("labels").asList(value -> value.asString()))).toList();
        List<GraphRelationshipDTO> relationships = relRows.stream().map(r -> relationshipFrom(
                r.get("props").asMap(), r.get("source").asString(), r.get("target").asString(),
                r.get("type").asString())).toList();
        return graph(nodes, relationships, spec, true);
    }

    private Map<String, Object> commonParams(QuerySpec spec, String startUid) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("projectId", projectId);
        params.put("startUid", startUid);
        params.put("relationshipTypes", List.copyOf(spec.getRelationshipTypes()));
        params.put("classFilters", List.copyOf(spec.getClassFilters()));
        return params;
    }

    private String predicateFilter(String relationshipVariable) {
        return "AND (size($relationshipTypes) = 0 OR " + relationshipVariable + ".kg_predicate_iri IN $relationshipTypes) ";
    }

    private String pathPredicateFilter() {
        return "AND (size($relationshipTypes) = 0 OR ALL(x IN relationships(p) WHERE x.kg_predicate_iri IN $relationshipTypes)) ";
    }

    private String classFilter(String nodeVariable) {
        return "AND (size($classFilters) = 0 OR " + nodeVariable + ".kg_class_iri IN $classFilters) ";
    }

    private String pathClassFilter() {
        return "AND (size($classFilters) = 0 OR ALL(v IN nodes(p)[1..] WHERE v.kg_class_iri IN $classFilters)) ";
    }

    private String directedPattern(QuerySpec.Direction direction, String s, String r, String n) {
        switch (direction) {
            case OUTGOING: return "(" + s + ":" + ENTITY_LABEL + ")-[" + r + "]->(" + n + ":" + ENTITY_LABEL + ")";
            case INCOMING: return "(" + s + ":" + ENTITY_LABEL + ")<-[" + r + "]-(" + n + ":" + ENTITY_LABEL + ")";
            case BOTH:
            default: return "(" + s + ":" + ENTITY_LABEL + ")-[" + r + "]-(" + n + ":" + ENTITY_LABEL + ")";
        }
    }

    private String pathPattern(QuerySpec.Direction direction, int depth) {
        switch (direction) {
            case OUTGOING: return "(s:" + ENTITY_LABEL + ")-[*1.." + depth + "]->(n:" + ENTITY_LABEL + ")";
            case INCOMING: return "(s:" + ENTITY_LABEL + ")<-[*1.." + depth + "]-(n:" + ENTITY_LABEL + ")";
            case BOTH:
            default: return "(s:" + ENTITY_LABEL + ")-[*1.." + depth + "]-(n:" + ENTITY_LABEL + ")";
        }
    }

    private String pathBetweenPattern(QuerySpec.Direction direction, int depth) {
        switch (direction) {
            case OUTGOING: return "(s:" + ENTITY_LABEL + ")-[*1.." + depth + "]->(t:" + ENTITY_LABEL + ")";
            case INCOMING: return "(s:" + ENTITY_LABEL + ")<-[*1.." + depth + "]-(t:" + ENTITY_LABEL + ")";
            case BOTH:
            default: return "(s:" + ENTITY_LABEL + ")-[*1.." + depth + "]-(t:" + ENTITY_LABEL + ")";
        }
    }

    private int requireDepth(QuerySpec spec) {
        Integer depth = spec.getDepth();
        if (depth == null || depth < 1) throw new QueryExecutionException("K_HOP/PATH 必须显式提供 depth >= 1");
        return depth;
    }

    private GraphNodeDTO nodeFromProps(Map<String, Object> source, List<String> neo4jLabels) {
        Map<String, Object> props = new LinkedHashMap<>(source);
        String id = String.valueOf(props.remove("kg_uid"));
        String classIri = String.valueOf(props.remove("kg_class_iri"));
        Object caption = props.remove("kg_caption");
        removeTechnical(props);
        List<String> labels = neo4jLabels.stream().filter(label -> !ENTITY_LABEL.equals(label)).sorted().toList();
        return new GraphNodeDTO(id, labels, localName(classIri),
                caption == null ? null : String.valueOf(caption), props);
    }

    private GraphRelationshipDTO relationshipFrom(Map<String, Object> source, String sourceUid,
                                                   String targetUid, String relationshipType) {
        Map<String, Object> props = new LinkedHashMap<>(source);
        String id = String.valueOf(props.remove("kg_uid"));
        props.remove("kg_predicate_iri");
        removeTechnical(props);
        return new GraphRelationshipDTO(id, sourceUid, targetUid, relationshipType, props);
    }

    private void removeTechnical(Map<String, Object> props) {
        props.keySet().removeIf(k -> k.startsWith("kg_"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        if (value instanceof Map<?, ?>) return (Map<String, Object>) value;
        throw new QueryExecutionException("Neo4j 返回的关系属性不是 Map");
    }

    private GraphDTO graph(List<GraphNodeDTO> nodes, List<GraphRelationshipDTO> relationships,
                           QuerySpec spec, boolean complete) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("queryType", spec.getType().name());
        meta.put("nodeCount", nodes.size());
        meta.put("relationshipCount", relationships.size());
        meta.put("complete", complete);
        return new GraphDTO(schemaVersion, nodes, relationships, meta);
    }

    private String localName(String iri) {
        int hash = iri.lastIndexOf('#');
        int slash = iri.lastIndexOf('/');
        int colon = iri.lastIndexOf(':');
        int index = Math.max(hash, Math.max(slash, colon));
        return index >= 0 && index + 1 < iri.length() ? iri.substring(index + 1) : iri;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new QueryExecutionException(name + " 不能为空");
        return value;
    }
}
