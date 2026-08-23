package org.atmkg.infra.identity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.atmkg.core.error.MappingExecutionException;
import org.atmkg.core.model.SourceRecord;
import org.atmkg.core.model.mapping.EntityMappingSpec;
import org.atmkg.core.model.mapping.RelationshipMappingSpec;
import org.atmkg.core.spi.IdentityResolver;

/** Deterministic, readable identity generation without Neo4j internal IDs or cryptographic hashes. */
public final class DeterministicIdentityResolver implements IdentityResolver {
    private final String namespace;

    public DeterministicIdentityResolver(String namespace) {
        if (namespace == null || namespace.isBlank()) throw new IllegalArgumentException("namespace 不能为空");
        this.namespace = namespace.endsWith(":") ? namespace : namespace + ":";
    }

    @Override
    public String entityUid(EntityMappingSpec mapping, String businessKeyValue) {
        if (businessKeyValue == null || businessKeyValue.isBlank()) {
            throw new MappingExecutionException("实体业务主键为空：" + mapping.getClassIri());
        }
        return namespace + "entity:" + enc(mapping.getClassIri()) + ":" + enc(businessKeyValue.trim());
    }

    @Override
    public String relationshipUid(RelationshipMappingSpec mapping, String sourceUid, String targetUid, SourceRecord record) {
        return namespace + "rel:" + enc(mapping.getPredicateIri()) + ":" + enc(sourceUid) + ":" + enc(targetUid);
    }

    private String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
