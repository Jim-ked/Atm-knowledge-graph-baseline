package org.atmkg.infra.query;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.atmkg.core.error.EntityLookupException;
import org.atmkg.core.error.QueryExecutionException;
import org.atmkg.core.model.GraphDTO;
import org.atmkg.core.model.GraphNodeDTO;
import org.atmkg.core.spi.EntityLookupService;
import org.atmkg.infra.neo4j.Neo4jConnectionSettings;
import org.neo4j.driver.AccessMode;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;

/** Exact business-key lookup over canonical {@code KGEntity} nodes only. */
public final class Neo4jEntityLookupService implements EntityLookupService {
    static final int MAX_RESULTS = 50;
    private static final String ENTITY_LABEL = "KGEntity";
    private static final String QUERY = "MATCH (n:" + ENTITY_LABEL + " {kg_project: $projectId}) "
            + "WHERE NOT n:KGEntityContribution AND n.kg_caption IS NOT NULL "
            + "AND toLower(n.kg_caption) = toLower($key) "
            + "AND ($classIri IS NULL OR n.kg_class_iri = $classIri) "
            + "RETURN properties(n) AS props, labels(n) AS labels "
            + "ORDER BY n.kg_class_iri, n.kg_uid LIMIT $limit";

    private final Driver driver;
    private final SessionConfig sessionConfig;
    private final String projectId;
    private final String schemaVersion;

    public Neo4jEntityLookupService(Driver driver, Neo4jConnectionSettings settings, String schemaVersion) {
        this.driver = Objects.requireNonNull(driver, "driver");
        Objects.requireNonNull(settings, "settings");
        this.sessionConfig = SessionConfig.builder()
                .withDatabase(settings.getDatabase())
                .withDefaultAccessMode(AccessMode.READ)
                .build();
        this.projectId = settings.getProjectId();
        this.schemaVersion = requireText(schemaVersion, "schemaVersion");
    }

    @Override
    public GraphDTO lookup(String key, String classIri) {
        String exactKey = requireText(key, "key");
        String exactClass = classIri == null ? null : requireText(classIri, "classIri");
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("projectId", projectId);
        parameters.put("key", exactKey);
        parameters.put("classIri", exactClass);
        parameters.put("limit", MAX_RESULTS + 1);

        List<Record> rows;
        try (Session session = driver.session(sessionConfig)) {
            Result result = session.run(QUERY, parameters);
            rows = new ArrayList<>(result.list());
            result.consume();
        } catch (EntityLookupException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new QueryExecutionException("实体定位查询失败", ex);
        }
        if (rows.size() > MAX_RESULTS) {
            throw new EntityLookupException(413, "RESULT_TOO_LARGE", "实体定位结果超过固定上限",
                    Map.of("limit", MAX_RESULTS));
        }

        rows.sort(Comparator
                .comparing((Record row) -> String.valueOf(row.get("props").asMap().get("kg_class_iri")))
                .thenComparing(row -> String.valueOf(row.get("props").asMap().get("kg_uid"))));
        List<GraphNodeDTO> nodes = rows.stream().map(this::nodeFrom).toList();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("queryType", "ENTITY_LOOKUP");
        meta.put("nodeCount", nodes.size());
        meta.put("relationshipCount", 0);
        meta.put("complete", true);
        return new GraphDTO(schemaVersion, nodes, List.of(), meta);
    }

    private GraphNodeDTO nodeFrom(Record row) {
        Map<String, Object> props = new LinkedHashMap<>(row.get("props").asMap());
        String uid = requiredProperty(props.remove("kg_uid"), "kg_uid");
        String classIri = requiredProperty(props.remove("kg_class_iri"), "kg_class_iri");
        Object caption = props.remove("kg_caption");
        props.keySet().removeIf(key -> key.startsWith("kg_"));
        List<String> labels = new ArrayList<>(row.get("labels").asList(value -> value.asString()));
        labels.removeIf(ENTITY_LABEL::equals);
        labels.sort(String::compareTo);
        return new GraphNodeDTO(uid, labels, localName(classIri),
                caption == null ? null : String.valueOf(caption), props);
    }

    private String requiredProperty(Object value, String name) {
        if (value == null || String.valueOf(value).isBlank()) {
            throw new QueryExecutionException("canonical KGEntity 缺少 " + name);
        }
        return String.valueOf(value);
    }

    private String localName(String iri) {
        int index = Math.max(iri.lastIndexOf('#'), Math.max(iri.lastIndexOf('/'), iri.lastIndexOf(':')));
        return index >= 0 && index + 1 < iri.length() ? iri.substring(index + 1) : iri;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        if (value.length() > 4096) throw new IllegalArgumentException(name + " 过长");
        return value;
    }
}
