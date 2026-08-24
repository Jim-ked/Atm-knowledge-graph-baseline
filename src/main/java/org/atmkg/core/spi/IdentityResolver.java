package org.atmkg.core.spi;

import org.atmkg.core.model.SourceRecord;
import org.atmkg.core.model.mapping.EntityMappingSpec;
import org.atmkg.core.model.mapping.RelationshipMappingSpec;

/**
 * 根据类语义和稳定业务键生成外部 UID；不得依赖文件名、表名、Sheet 或 Neo4j internal id。
 * 普通业务键选择应修改 mapping，不修改身份接口。
 */
public interface IdentityResolver {
    /** 根据实体 mapping 和稳定业务键值生成实体 UID。 */
    String entityUid(EntityMappingSpec mapping, String businessKeyValue);

    default String entityUid(EntityMappingSpec mapping, SourceRecord record, String businessKeyValue) {
        return entityUid(mapping, businessKeyValue);
    }

    String relationshipUid(RelationshipMappingSpec mapping, String sourceUid, String targetUid, SourceRecord record);
}
