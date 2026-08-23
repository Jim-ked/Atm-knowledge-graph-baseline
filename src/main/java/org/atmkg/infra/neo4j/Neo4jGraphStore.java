package org.atmkg.infra.neo4j;

import static org.neo4j.driver.Values.parameters;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import org.atmkg.core.error.GraphStoreException;
import org.atmkg.core.model.GraphEntity;
import org.atmkg.core.model.GraphRelationship;
import org.atmkg.core.model.GraphStoreStats;
import org.atmkg.core.model.MappingResult;
import org.atmkg.core.model.OntologySchema;
import org.atmkg.core.model.SourceRef;
import org.atmkg.core.spi.GraphStore;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.TransactionContext;

/** Neo4j projection with one technical entity label and ontology-derived labels/types. */
public final class Neo4jGraphStore implements GraphStore {
    static final String ENTITY_LABEL = "KGEntity";

    private final Driver driver;
    private final SessionConfig sessionConfig;
    private final String projectId;
    private final int batchSize;
    private final Neo4jOntologyMetadata ontology;

    public Neo4jGraphStore(Driver driver, Neo4jConnectionSettings settings, OntologySchema schema) {
        this.driver = Objects.requireNonNull(driver, "driver");
        Objects.requireNonNull(settings, "settings");
        this.sessionConfig = SessionConfig.forDatabase(settings.getDatabase());
        this.projectId = settings.getProjectId();
        this.batchSize = settings.getBatchSize();
        this.ontology = Neo4jOntologyMetadata.from(schema);
    }

    @Override
    public void initializeSchema() {
        try (Session session = driver.session(sessionConfig)) {
            session.executeWrite(tx -> {
                tx.run("CREATE CONSTRAINT atmkg_entity_identity IF NOT EXISTS " +
                        "FOR (n:" + ENTITY_LABEL + ") REQUIRE (n.kg_project, n.kg_uid) IS UNIQUE").consume();
                tx.run("CREATE INDEX atmkg_entity_class IF NOT EXISTS " +
                        "FOR (n:" + ENTITY_LABEL + ") ON (n.kg_class_iri)").consume();
                tx.run("CREATE INDEX atmkg_entity_source IF NOT EXISTS " +
                        "FOR (n:" + ENTITY_LABEL + ") ON (n.kg_source_id, n.kg_source_object, n.kg_source_key)").consume();
                dropLegacyRelationshipSchema(tx);
                for (String type : ontology.allRelationshipTypes().stream().sorted().toList()) {
                    String token = identifier(type);
                    String prefix = "atmkg_rel_" + type;
                    tx.run("CREATE CONSTRAINT " + identifier(prefix + "_identity") + " IF NOT EXISTS " +
                            "FOR ()-[r:" + token + "]-() REQUIRE (r.kg_project, r.kg_uid) IS UNIQUE").consume();
                    tx.run("CREATE INDEX " + identifier(prefix + "_predicate") + " IF NOT EXISTS " +
                            "FOR ()-[r:" + token + "]-() ON (r.kg_predicate_iri)").consume();
                    tx.run("CREATE INDEX " + identifier(prefix + "_source") + " IF NOT EXISTS " +
                            "FOR ()-[r:" + token + "]-() ON (r.kg_source_id, r.kg_source_object, r.kg_source_key)").consume();
                }
                return null;
            });
        } catch (RuntimeException ex) {
            throw new GraphStoreException("Neo4j schema 初始化失败", ex);
        }
    }

    @Override
    public void upsertEntities(Collection<GraphEntity> entities) {
        if (entities == null || entities.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(entities.size());
        for (GraphEntity entity : entities) rows.add(entityRow(entity));
        executeInBatches(rows, true);
    }

    @Override
    public void upsertRelationships(Collection<GraphRelationship> relationships) {
        if (relationships == null || relationships.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(relationships.size());
        for (GraphRelationship relationship : relationships) rows.add(relationshipRow(relationship));
        executeInBatches(rows, false);
    }

    private void executeInBatches(List<Map<String, Object>> rows, boolean entities) {
        try (Session session = driver.session(sessionConfig)) {
            for (int start = 0; start < rows.size(); start += batchSize) {
                List<Map<String, Object>> batch = rows.subList(start, Math.min(rows.size(), start + batchSize));
                session.executeWrite(tx -> {
                    if (entities) {
                        writeEntityBatch(tx, batch);
                    } else {
                        writeRelationshipBatches(tx, batch);
                    }
                    return null;
                });
            }
        } catch (RuntimeException ex) {
            throw new GraphStoreException((entities ? "实体" : "关系") + "批量写入失败", ex);
        }
    }

    private void writeEntityBatches(TransactionContext tx, List<Map<String, Object>> rows) {
        for (int start = 0; start < rows.size(); start += batchSize) {
            writeEntityBatch(tx, rows.subList(start, Math.min(rows.size(), start + batchSize)));
        }
    }

    private void writeEntityBatch(TransactionContext tx, List<Map<String, Object>> rows) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            @SuppressWarnings("unchecked")
            List<String> labels = (List<String>) row.get("labels");
            groups.computeIfAbsent(String.join("\u0000", labels), ignored -> new ArrayList<>()).add(row);
        }
        for (List<Map<String, Object>> group : groups.values()) {
            @SuppressWarnings("unchecked")
            List<String> labels = (List<String>) group.get(0).get("labels");
            String cypher = "UNWIND $rows AS row " +
                    "MERGE (n:" + ENTITY_LABEL + " {kg_project: $projectId, kg_uid: row.uid}) " +
                    "SET n = row.props " +
                    "REMOVE n:" + labelsClause(ontology.allClassLabels()) + " " +
                    "SET n:" + labelsClause(labels);
            tx.run(cypher, parameters("rows", group, "projectId", projectId)).consume();
        }
    }

    private void writeRelationshipBatches(TransactionContext tx, List<Map<String, Object>> rows) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            groups.computeIfAbsent(String.valueOf(row.get("relationshipType")), ignored -> new ArrayList<>()).add(row);
        }
        for (Map.Entry<String, List<Map<String, Object>>> entry : groups.entrySet()) {
            String relationshipType = entry.getKey();
            List<Map<String, Object>> group = entry.getValue();
            for (int start = 0; start < group.size(); start += batchSize) {
                List<Map<String, Object>> batch = group.subList(start, Math.min(group.size(), start + batchSize));
                validateRelationshipEndpoints(tx, batch);
                String cypher = "UNWIND $rows AS row " +
                        "MATCH (s:" + ENTITY_LABEL + " {kg_uid: row.sourceUid, kg_project: $projectId}) " +
                        "MATCH (t:" + ENTITY_LABEL + " {kg_uid: row.targetUid, kg_project: $projectId}) " +
                        "MERGE (s)-[r:" + identifier(relationshipType) + " {kg_project: $projectId, kg_uid: row.uid}]->(t) " +
                        "SET r = row.props " +
                        "RETURN count(r) AS processed";
                long processed = tx.run(cypher, parameters("rows", batch, "projectId", projectId))
                        .single().get("processed").asLong();
                if (processed != batch.size()) {
                    throw new GraphStoreException("关系写入存在缺失端点：requested=" + batch.size() + ", processed=" + processed);
                }
            }
        }
    }

    private void validateRelationshipEndpoints(TransactionContext tx, List<Map<String, Object>> rows) {
        List<Record> endpointRows = tx.run(
                "UNWIND $rows AS row " +
                        "OPTIONAL MATCH (s:" + ENTITY_LABEL + " {kg_uid: row.sourceUid, kg_project: $projectId}) " +
                        "OPTIONAL MATCH (t:" + ENTITY_LABEL + " {kg_uid: row.targetUid, kg_project: $projectId}) " +
                        "RETURN row.uid AS uid, row.predicateIri AS predicateIri, " +
                        "s.kg_class_iri AS sourceClass, t.kg_class_iri AS targetClass",
                parameters("rows", rows, "projectId", projectId)).list();
        for (Record record : endpointRows) {
            String uid = record.get("uid").asString();
            if (record.get("sourceClass").isNull() || record.get("targetClass").isNull()) {
                throw new GraphStoreException("关系端点缺失：" + uid);
            }
            ontology.validateRelationship(record.get("predicateIri").asString(),
                    record.get("sourceClass").asString(), record.get("targetClass").asString());
        }
    }

    private void dropLegacyRelationshipSchema(TransactionContext tx) {
        tx.run("DROP CONSTRAINT atmkg_rel_identity IF EXISTS").consume();
        tx.run("DROP INDEX atmkg_rel_predicate IF EXISTS").consume();
        tx.run("DROP INDEX atmkg_rel_source IF EXISTS").consume();
    }

    private String labelsClause(Collection<String> labels) {
        StringJoiner joiner = new StringJoiner(":");
        for (String label : labels) joiner.add(identifier(label));
        return joiner.toString();
    }

    private String identifier(String token) {
        return "`" + token.replace("`", "``") + "`";
    }

    @Override
    public void replaceProjection(SourceRef sourceRef, MappingResult currentProjection) {
        Objects.requireNonNull(sourceRef, "sourceRef");
        Objects.requireNonNull(currentProjection, "currentProjection");
        List<Map<String, Object>> entityRows = currentProjection.getEntities().stream().map(this::entityRow).toList();
        List<Map<String, Object>> relationshipRows = currentProjection.getRelationships().stream().map(this::relationshipRow).toList();
        List<String> currentEntityUids = currentProjection.getEntities().stream().map(GraphEntity::getUid).toList();

        try (Session session = driver.session(sessionConfig)) {
            session.executeWrite(tx -> {
                Map<String, Object> ref = sourceRefParams(sourceRef);

                tx.run("MATCH ()-[r]->() " +
                        "WHERE r.kg_project = $projectId AND r.kg_source_id = $sourceId " +
                        "AND r.kg_source_object = $sourceObject AND r.kg_source_key = $sourceKey DELETE r", ref).consume();

                List<Record> ownershipConflicts = tx.run("MATCH (n:" + ENTITY_LABEL + ") " +
                        "WHERE n.kg_project = $projectId AND n.kg_source_id = $sourceId " +
                        "AND n.kg_source_object = $sourceObject AND n.kg_source_key = $sourceKey " +
                        "AND NOT n.kg_uid IN $currentEntityUids " +
                        "MATCH (n)-[r]-() RETURN n.kg_uid AS uid, r.kg_uid AS relationshipUid LIMIT 1",
                        parameters("projectId", projectId,
                                "sourceId", sourceRef.getSourceId(),
                                "sourceObject", sourceRef.getObjectName(),
                                "sourceKey", sourceRef.getSourceKey(),
                                "currentEntityUids", currentEntityUids)).list();
                if (!ownershipConflicts.isEmpty()) {
                    Record conflict = ownershipConflicts.get(0);
                    throw new GraphStoreException("拒绝删除仍被其他源记录关系引用的实体：uid="
                            + conflict.get("uid").asString() + ", relationshipUid="
                            + conflict.get("relationshipUid").asString("<missing>"));
                }

                tx.run("MATCH (n:" + ENTITY_LABEL + ") " +
                        "WHERE n.kg_project = $projectId AND n.kg_source_id = $sourceId " +
                        "AND n.kg_source_object = $sourceObject AND n.kg_source_key = $sourceKey " +
                        "AND NOT n.kg_uid IN $currentEntityUids DELETE n",
                        parameters("projectId", projectId,
                                "sourceId", sourceRef.getSourceId(),
                                "sourceObject", sourceRef.getObjectName(),
                                "sourceKey", sourceRef.getSourceKey(),
                                "currentEntityUids", currentEntityUids)).consume();

                if (!entityRows.isEmpty()) writeEntityBatches(tx, entityRows);
                if (!relationshipRows.isEmpty()) writeRelationshipBatches(tx, relationshipRows);
                return null;
            });
        } catch (RuntimeException ex) {
            throw new GraphStoreException("替换源记录图投影失败：" + sourceRef.getSourceId() + "/"
                    + sourceRef.getObjectName() + "/" + sourceRef.getSourceKey(), ex);
        }
    }

    @Override
    public void deleteProjection(SourceRef sourceRef) {
        replaceProjection(sourceRef, new MappingResult(List.of(), List.of()));
    }

    @Override
    public void deleteEntity(String uid) {
        requireUid(uid);
        try (Session session = driver.session(sessionConfig)) {
            session.executeWrite(tx -> {
                tx.run("MATCH (n:" + ENTITY_LABEL + " {kg_uid: $uid, kg_project: $projectId}) DETACH DELETE n",
                        parameters("uid", uid, "projectId", projectId)).consume();
                return null;
            });
        } catch (RuntimeException ex) {
            throw new GraphStoreException("删除实体失败：" + uid, ex);
        }
    }

    @Override
    public void deleteRelationship(String uid) {
        requireUid(uid);
        try (Session session = driver.session(sessionConfig)) {
            session.executeWrite(tx -> {
                tx.run("MATCH ()-[r {kg_uid: $uid, kg_project: $projectId}]->() DELETE r",
                        parameters("uid", uid, "projectId", projectId)).consume();
                return null;
            });
        } catch (RuntimeException ex) {
            throw new GraphStoreException("删除关系失败：" + uid, ex);
        }
    }

    @Override
    public Optional<GraphEntity> findEntity(String uid) {
        requireUid(uid);
        try (Session session = driver.session(sessionConfig)) {
            return session.executeRead(tx -> {
                List<Record> rows = tx.run(
                        "MATCH (n:" + ENTITY_LABEL + " {kg_uid: $uid, kg_project: $projectId}) RETURN properties(n) AS props",
                        parameters("uid", uid, "projectId", projectId)).list();
                if (rows.isEmpty()) return Optional.empty();
                return Optional.of(entityFromProperties(rows.get(0).get("props").asMap()));
            });
        } catch (RuntimeException ex) {
            throw new GraphStoreException("查询实体失败：" + uid, ex);
        }
    }

    @Override
    public void clearProject() {
        try (Session session = driver.session(sessionConfig)) {
            session.executeWrite(tx -> {
                tx.run("MATCH (n:" + ENTITY_LABEL + " {kg_project: $projectId}) DETACH DELETE n",
                        parameters("projectId", projectId)).consume();
                return null;
            });
        } catch (RuntimeException ex) {
            throw new GraphStoreException("清理项目图失败：" + projectId, ex);
        }
    }

    @Override
    public GraphStoreStats stats() {
        try (Session session = driver.session(sessionConfig)) {
            return session.executeRead(tx -> {
                long entities = tx.run("MATCH (n:" + ENTITY_LABEL + " {kg_project: $projectId}) RETURN count(n) AS c",
                        parameters("projectId", projectId)).single().get("c").asLong();
                long relationships = tx.run("MATCH ()-[r {kg_project: $projectId}]->() RETURN count(r) AS c",
                        parameters("projectId", projectId)).single().get("c").asLong();
                return new GraphStoreStats(entities, relationships);
            });
        } catch (RuntimeException ex) {
            throw new GraphStoreException("读取图统计失败", ex);
        }
    }

    private Map<String, Object> sourceRefParams(SourceRef ref) {
        return Map.of(
                "projectId", projectId,
                "sourceId", ref.getSourceId(),
                "sourceObject", ref.getObjectName(),
                "sourceKey", ref.getSourceKey());
    }

    private Map<String, Object> entityRow(GraphEntity entity) {
        Map<String, Object> props = new LinkedHashMap<>(Neo4jValueNormalizer.normalizeProperties(entity.getProperties()));
        props.put("kg_uid", entity.getUid());
        props.put("kg_project", projectId);
        props.put("kg_class_iri", entity.getClassIri());
        if (entity.getCaption() != null) props.put("kg_caption", entity.getCaption());
        addProvenance(props, entity.getProvenance());
        return Map.of("uid", entity.getUid(), "props", props,
                "labels", List.copyOf(ontology.labelsForClass(entity.getClassIri())));
    }

    private Map<String, Object> relationshipRow(GraphRelationship relationship) {
        Map<String, Object> props = new LinkedHashMap<>(Neo4jValueNormalizer.normalizeProperties(relationship.getProperties()));
        props.put("kg_uid", relationship.getUid());
        props.put("kg_project", projectId);
        props.put("kg_predicate_iri", relationship.getPredicateIri());
        addProvenance(props, relationship.getProvenance());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("uid", relationship.getUid());
        row.put("sourceUid", relationship.getSourceUid());
        row.put("targetUid", relationship.getTargetUid());
        row.put("props", props);
        row.put("predicateIri", relationship.getPredicateIri());
        row.put("relationshipType", ontology.relationshipType(relationship.getPredicateIri()));
        return row;
    }

    private void addProvenance(Map<String, Object> props, Map<String, Object> provenance) {
        putIfPresent(props, "kg_source_id", provenance.get("sourceId"));
        putIfPresent(props, "kg_source_object", provenance.get("sourceObject"));
        putIfPresent(props, "kg_source_key", provenance.get("sourceKey"));
        putIfPresent(props, "kg_source_timestamp", provenance.get("sourceTimestamp"));
    }

    private void putIfPresent(Map<String, Object> props, String key, Object value) {
        Object normalized = Neo4jValueNormalizer.normalize(value);
        if (normalized != null) props.put(key, normalized);
    }

    private GraphEntity entityFromProperties(Map<String, Object> all) {
        Map<String, Object> business = new LinkedHashMap<>(all);
        String uid = String.valueOf(business.remove("kg_uid"));
        String classIri = String.valueOf(business.remove("kg_class_iri"));
        Object captionValue = business.remove("kg_caption");
        business.remove("kg_project");

        Map<String, Object> provenance = new LinkedHashMap<>();
        moveTechnical(business, provenance, "kg_source_id", "sourceId");
        moveTechnical(business, provenance, "kg_source_object", "sourceObject");
        moveTechnical(business, provenance, "kg_source_key", "sourceKey");
        moveTechnical(business, provenance, "kg_source_timestamp", "sourceTimestamp");
        return new GraphEntity(uid, classIri, captionValue == null ? null : String.valueOf(captionValue), business, provenance);
    }

    private void moveTechnical(Map<String, Object> source, Map<String, Object> target, String from, String to) {
        Object value = source.remove(from);
        if (value != null) target.put(to, value);
    }

    private void requireUid(String uid) {
        if (uid == null || uid.isBlank()) throw new IllegalArgumentException("uid 不能为空");
    }
}
