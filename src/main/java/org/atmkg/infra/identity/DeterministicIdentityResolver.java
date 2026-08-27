package org.atmkg.infra.identity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.atmkg.core.error.MappingExecutionException;
import org.atmkg.core.model.SourceRecord;
import org.atmkg.core.model.mapping.RelationshipMappingSpec;
import org.atmkg.core.spi.IdentityResolver;

/**
 * 新增实体/表/文件时不要修改本类。在 {@code mapping/字段映射.xlsx} 的“业务主键”选择稳定语义字段；
 * 物理读取键在 {@code config/sources.yaml keyFields} 配置。实体身份算法固定为 Class IRI + 业务键值。
 *
 * <p>只有经过全图迁移设计的 namespace/UID 编码算法变化才写 Java。把文件名、路径、table、Sheet、行号或
 * Neo4j internal ID 加入算法，会让同一业务对象跨部署变 UID，并使已有关系端点失配。UID 不稳定先比较两次
 * MappingResult 的 businessKey/classIri，再查装配 namespace，不要先改编码函数。
 */
public final class DeterministicIdentityResolver implements IdentityResolver {
    private final String namespace;

    public DeterministicIdentityResolver(String namespace) {
        if (namespace == null || namespace.isBlank()) throw new IllegalArgumentException("namespace 不能为空");
        this.namespace = namespace.endsWith(":") ? namespace : namespace + ":";
    }

    @Override
    public String entityUid(String classIri, String businessKeyValue) {
        if (businessKeyValue == null || businessKeyValue.isBlank()) {
            throw new MappingExecutionException("实体业务主键为空：" + classIri);
        }
        return namespace + "entity:" + enc(classIri) + ":" + enc(businessKeyValue.trim());
    }

    @Override
    public String relationshipUid(RelationshipMappingSpec mapping, String sourceUid, String targetUid, SourceRecord record) {
        return namespace + "rel:" + enc(mapping.getPredicateIri()) + ":" + enc(sourceUid) + ":" + enc(targetUid);
    }

    private String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
