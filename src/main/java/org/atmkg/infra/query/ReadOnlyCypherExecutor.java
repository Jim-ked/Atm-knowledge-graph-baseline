package org.atmkg.infra.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.atmkg.core.error.ReadOnlyCypherException;
import org.atmkg.core.model.GraphDTO;
import org.atmkg.core.model.GraphNodeDTO;
import org.atmkg.core.model.GraphRelationshipDTO;
import org.atmkg.core.spi.ReadOnlyCypherService;
import org.atmkg.infra.neo4j.Neo4jConnectionSettings;
import org.neo4j.driver.AccessMode;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Value;
import org.neo4j.driver.summary.QueryType;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Path;
import org.neo4j.driver.types.Relationship;

/**
 * Viewer 专用 raw Cypher 执行器。只读性由 Neo4j EXPLAIN 的官方 queryType 判定，
 * AccessMode.READ 只是第二层连接约束；本类不属于 QueryService/QuerySpec 主查询链。
 */
public final class ReadOnlyCypherExecutor implements ReadOnlyCypherService {
    private static final String ENTITY_LABEL = "KGEntity";
    private static final String CONTRIBUTION_LABEL = "KGEntityContribution";
    private static final String EXPLAIN_PROFILE_MESSAGE =
            "Viewer Cypher用于返回图结果，请直接输入MATCH/RETURN等只读查询；EXPLAIN/PROFILE请在Neo4j Browser中使用。";

    private final Driver driver;
    private final SessionConfig sessionConfig;
    private final String projectId;
    private final String schemaVersion;
    private final int maxResultNodes;
    private final int maxResultRelationships;

    public ReadOnlyCypherExecutor(Driver driver, Neo4jConnectionSettings settings, String schemaVersion,
                                  int maxResultNodes, int maxResultRelationships) {
        this.driver = Objects.requireNonNull(driver, "driver");
        Objects.requireNonNull(settings, "settings");
        this.sessionConfig = SessionConfig.builder()
                .withDatabase(settings.getDatabase())
                .withDefaultAccessMode(AccessMode.READ)
                .build();
        this.projectId = settings.getProjectId();
        this.schemaVersion = requireText(schemaVersion, "schemaVersion");
        if (maxResultNodes < 1 || maxResultRelationships < 1) {
            throw new IllegalArgumentException("结果上限必须大于 0");
        }
        this.maxResultNodes = maxResultNodes;
        this.maxResultRelationships = maxResultRelationships;
    }

    @Override
    public GraphDTO execute(String cypher) {
        String query = validateInput(cypher);
        try (Session session = driver.session(sessionConfig)) {
            QueryType queryType = session.run("EXPLAIN " + query).consume().queryType();
            if (queryType != QueryType.READ_ONLY) {
                throw new ReadOnlyCypherException(400, "CYPHER_READ_ONLY_REQUIRED",
                        "Viewer 只允许只读 Cypher 查询（EXPLAIN 判定为 " + queryType + "）");
            }
            Result result = session.run(query);
            GraphAccumulator graph = new GraphAccumulator();
            while (result.hasNext()) graph.accept(result.next());
            result.consume();
            return graph.toDto();
        } catch (ReadOnlyCypherException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ReadOnlyCypherException(400, "CYPHER_INVALID", "Cypher 语法或执行失败", Map.of());
        }
    }

    private String validateInput(String cypher) {
        if (cypher == null || cypher.isBlank()) {
            throw new ReadOnlyCypherException(400, "INVALID_REQUEST", "cypher 不能为空");
        }
        String query = cypher.trim();
        String upper = query.toUpperCase(java.util.Locale.ROOT);
        if (upper.startsWith("EXPLAIN") || upper.startsWith("PROFILE")) {
            throw new ReadOnlyCypherException(400, "CYPHER_EXPLAIN_PROFILE_NOT_ALLOWED", EXPLAIN_PROFILE_MESSAGE);
        }
        if (query.length() > 65536) {
            throw new ReadOnlyCypherException(400, "INVALID_REQUEST", "cypher 过长");
        }
        return query;
    }

    private final class GraphAccumulator {
        private final Map<String, GraphNodeDTO> nodes = new LinkedHashMap<>();
        private final Map<String, GraphRelationshipDTO> relationships = new LinkedHashMap<>();
        private final Map<String, String> internalToUid = new LinkedHashMap<>();
        private final Set<Relationship> pendingRelationships = Collections.newSetFromMap(new IdentityHashMap<>());

        void accept(Record record) {
            for (Value value : record.values()) acceptValue(value);
            resolvePendingRelationships();
        }

        void acceptValue(Value value) {
            if (value == null || value.isNull()) return;
            if (value.asObject() instanceof Node) { addNode(value.asNode()); return; }
            if (value.asObject() instanceof Relationship) { pendingRelationships.add(value.asRelationship()); return; }
            if (value.asObject() instanceof Path) {
                Path path = value.asPath();
                for (Node node : path.nodes()) addNode(node);
                for (Relationship relationship : path.relationships()) pendingRelationships.add(relationship);
                return;
            }
        }

        void addNode(Node node) {
            Map<String, Object> source = node.asMap();
            Object project = source.get("kg_project");
            Object uidValue = source.get("kg_uid");
            if (!hasEntityLabel(node) || hasContributionLabel(node)
                    || !projectId.equals(String.valueOf(project))
                    || uidValue == null || String.valueOf(uidValue).isBlank()) return;
            String uid = String.valueOf(uidValue);
            internalToUid.put(node.elementId(), uid);
            nodes.putIfAbsent(uid, nodeFrom(node, uid));
            enforceLimits();
        }

        void resolvePendingRelationships() {
            for (Relationship relationship : pendingRelationships) {
                String source = internalToUid.get(relationship.startNodeElementId());
                String target = internalToUid.get(relationship.endNodeElementId());
                Map<String, Object> props = relationship.asMap();
                Object idValue = props.get("kg_uid");
                if (source == null || target == null || idValue == null || String.valueOf(idValue).isBlank()) continue;
                String id = String.valueOf(idValue);
                relationships.putIfAbsent(id, relationshipFrom(relationship, source, target));
                enforceLimits();
            }
            pendingRelationships.clear();
        }

        GraphDTO toDto() {
            if (nodes.isEmpty() && relationships.isEmpty()) {
                throw new ReadOnlyCypherException(400, "CYPHER_NO_GRAPH_RESULT",
                        "查询没有可展示的图结果，请返回 n、n,r,m 或 p；关系查询请同时 RETURN 两端节点，例如 RETURN a,r,b");
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("queryType", "CYPHER");
            meta.put("nodeCount", nodes.size());
            meta.put("relationshipCount", relationships.size());
            meta.put("complete", true);
            return new GraphDTO(schemaVersion, new ArrayList<>(nodes.values()),
                    new ArrayList<>(relationships.values()), meta);
        }

        void enforceLimits() {
            if (nodes.size() > maxResultNodes || relationships.size() > maxResultRelationships) {
                throw new ReadOnlyCypherException(413, "RESULT_TOO_LARGE", "完整查询结果超过服务配置上限",
                        Map.of("nodeCount", nodes.size(), "relationshipCount", relationships.size()));
            }
        }
    }

    private GraphNodeDTO nodeFrom(Node node, String uid) {
        Map<String, Object> props = cleanProperties(node.asMap(), Set.of("kg_uid", "kg_class_iri", "kg_caption"));
        String classIri = String.valueOf(node.asMap().getOrDefault("kg_class_iri", ""));
        Object caption = node.asMap().get("kg_caption");
        List<String> labels = new ArrayList<>();
        for (String label : node.labels()) if (!ENTITY_LABEL.equals(label)) labels.add(label);
        labels.sort(String::compareTo);
        return new GraphNodeDTO(uid, labels, localName(classIri), caption == null ? null : String.valueOf(caption), props);
    }

    private GraphRelationshipDTO relationshipFrom(Relationship relationship, String source, String target) {
        Map<String, Object> props = cleanProperties(relationship.asMap(), Set.of("kg_uid", "kg_predicate_iri"));
        return new GraphRelationshipDTO(String.valueOf(relationship.asMap().get("kg_uid")), source, target,
                relationship.type(), props);
    }

    private Map<String, Object> cleanProperties(Map<String, Object> source, Set<String> reserved) {
        Map<String, Object> props = new LinkedHashMap<>(source);
        props.keySet().removeIf(key -> reserved.contains(key) || key.startsWith("kg_"));
        return props;
    }

    private String localName(String iri) {
        int index = Math.max(iri.lastIndexOf('#'), Math.max(iri.lastIndexOf('/'), iri.lastIndexOf(':')));
        return index >= 0 && index + 1 < iri.length() ? iri.substring(index + 1) : iri;
    }

    private boolean hasEntityLabel(Node node) {
        for (String label : node.labels()) if (ENTITY_LABEL.equals(label)) return true;
        return false;
    }

    private boolean hasContributionLabel(Node node) {
        for (String label : node.labels()) if (CONTRIBUTION_LABEL.equals(label)) return true;
        return false;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        return value;
    }
}
