package org.atmkg.infra.mapping;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.atmkg.core.error.MappingExecutionException;
import org.atmkg.core.model.GraphEntity;
import org.atmkg.core.model.GraphRelationship;
import org.atmkg.core.model.MappingResult;
import org.atmkg.core.model.SourceRecord;
import org.atmkg.core.model.mapping.EntityMappingSpec;
import org.atmkg.core.model.mapping.MappingCatalog;
import org.atmkg.core.model.mapping.PropertyMappingSpec;
import org.atmkg.core.model.mapping.RelationshipMappingSpec;
import org.atmkg.core.spi.IdentityResolver;
import org.atmkg.core.spi.MappingEngine;

/**
 * 新增属性、实体或关系不要修改本类：先在 {@code ontology/atm_knowledge_graph.ttl} 确认 IRI，再编辑
 * {@code mapping/字段映射.xlsx} 对应 Sheet。新增数据表则先改 {@code config/sources.yaml}。
 *
 * <p>只有所有实体共用的字段路径读取、trim/upper/lower/integer/long/decimal/boolean 转换或 MappingResult
 * 生成规则确需变化才写 Java。加入 Airport/Airspace/Flight if 会把 workbook 语义硬编码进 Core 主链。
 * 实体/属性 mapping 按 {@code record.sourceId + record.objectName} 选择；关系 mapping 当前按
 * {@code record.sourceId} 取该 sourceId 下全部关系行，再对每行读取 locator，任一端为空就跳过。
 * 因此同一 sourceId 下多个 object 只要出现相同 locator 字段，可能尝试同一关系；新增 sourceObject
 * 过滤需改 workbook/Core 契约，本轮不要做。映射失败先用 Excel preview 或 JDBC sync/resync 确认字段，
 * 再查工作簿 businessKey/locator；不要从查询结果补端点。
 */
public final class DefaultMappingEngine implements MappingEngine {
    private final MappingCatalog catalog;
    private final IdentityResolver identityResolver;

    public DefaultMappingEngine(MappingCatalog catalog, IdentityResolver identityResolver) {
        this.catalog = Objects.requireNonNull(catalog);
        this.identityResolver = Objects.requireNonNull(identityResolver);
    }

    @Override
    public MappingResult map(SourceRecord record) {
        List<GraphEntity> entities = new ArrayList<>();
        List<GraphRelationship> relationships = new ArrayList<>();

        for (EntityMappingSpec entitySpec : catalog.entityMappingsFor(record.getSourceId(), record.getObjectName())) {
            Object keyValue = readPath(record.getFields(), entitySpec.getBusinessKey());
            if (keyValue == null || String.valueOf(keyValue).isBlank()) {
                throw new MappingExecutionException("实体业务主键缺失：" + entitySpec.getBusinessKey()
                        + "，source=" + record.getSourceId() + "/" + record.getObjectName());
            }
            String uid = identityResolver.entityUid(entitySpec, String.valueOf(keyValue));
            Map<String, Object> properties = new LinkedHashMap<>();
            for (PropertyMappingSpec propertySpec : catalog.propertyMappingsFor(
                    record.getSourceId(), record.getObjectName(), entitySpec.getClassIri())) {
                Object raw = readPath(record.getFields(), propertySpec.getSourcePath());
                if (raw == null || (raw instanceof String && ((String) raw).isBlank())) {
                    if (propertySpec.isRequired()) {
                        throw new MappingExecutionException("必填字段缺失：" + propertySpec.getSourcePath()
                                + " -> " + propertySpec.getPropertyIri());
                    }
                    continue;
                }
                properties.put(propertySpec.getPropertyIri(), transform(raw, propertySpec.getTransform()));
            }
            entities.add(new GraphEntity(uid, entitySpec.getClassIri(), String.valueOf(keyValue), properties, provenance(record)));
        }

        for (RelationshipMappingSpec relationshipSpec : catalog.relationshipMappingsFor(record.getSourceId())) {
            Object subjectKey = readPath(record.getFields(), relationshipSpec.getSubjectLocator());
            Object objectKey = readPath(record.getFields(), relationshipSpec.getObjectLocator());
            if (isBlankValue(subjectKey) || isBlankValue(objectKey)) continue;
            EntityMappingSpec subjectMapping = catalog.compatibleEntityMapping(
                            record.getSourceId(), relationshipSpec.getSubjectClassIri())
                    .orElseThrow(() -> new MappingExecutionException(
                            "关系起点实体身份映射缺失或 UID规则不兼容：" + relationshipSpec.getPredicateIri()));
            EntityMappingSpec objectMapping = catalog.compatibleEntityMapping(
                            record.getSourceId(), relationshipSpec.getObjectClassIri())
                    .orElseThrow(() -> new MappingExecutionException(
                            "关系终点实体身份映射缺失或 UID规则不兼容：" + relationshipSpec.getPredicateIri()));
            String sourceUid = identityResolver.entityUid(subjectMapping, String.valueOf(subjectKey));
            String targetUid = identityResolver.entityUid(objectMapping, String.valueOf(objectKey));
            String relationUid = identityResolver.relationshipUid(relationshipSpec, sourceUid, targetUid, record);
            relationships.add(new GraphRelationship(relationUid, relationshipSpec.getPredicateIri(), sourceUid, targetUid,
                    Map.of(), provenance(record)));
        }

        return new MappingResult(entities, relationships);
    }

    @SuppressWarnings("unchecked")
    private Object readPath(Map<String, Object> fields, String path) {
        if (path == null || path.isBlank()) return null;
        Object current = fields;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map)) return null;
            current = ((Map<String, Object>) current).get(part);
            if (current == null) return null;
        }
        return current;
    }

    private boolean isBlankValue(Object value) {
        return value == null || (value instanceof String && ((String) value).isBlank());
    }

    private Object transform(Object value, String transform) {
        if (transform == null || transform.isBlank()) return value;
        String op = transform.trim().toLowerCase(Locale.ROOT);
        String text = String.valueOf(value);
        try {
            switch (op) {
                case "trim": return text.trim();
                case "upper": return text.trim().toUpperCase(Locale.ROOT);
                case "lower": return text.trim().toLowerCase(Locale.ROOT);
                case "integer": return Integer.valueOf(text.trim());
                case "long": return Long.valueOf(text.trim());
                case "decimal": return new BigDecimal(text.trim());
                case "boolean": return parseBoolean(text);
                default: throw new MappingExecutionException("不支持的必要转换：" + transform);
            }
        } catch (NumberFormatException ex) {
            throw new MappingExecutionException("字段转换失败：" + value + " -> " + transform, ex);
        }
    }

    private boolean parseBoolean(String value) {
        String v = value.trim();
        if ("true".equalsIgnoreCase(v) || "1".equals(v) || "是".equals(v) || "yes".equalsIgnoreCase(v)) return true;
        if ("false".equalsIgnoreCase(v) || "0".equals(v) || "否".equals(v) || "no".equalsIgnoreCase(v)) return false;
        throw new MappingExecutionException("无法转换为 boolean：" + value);
    }

    private Map<String, Object> provenance(SourceRecord record) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sourceId", record.getSourceId());
        values.put("sourceObject", record.getObjectName());
        values.put("sourceKey", record.getSourceKey());
        Instant ts = record.getSourceTimestamp();
        if (ts != null) values.put("sourceTimestamp", ts.toString());
        return values;
    }
}
