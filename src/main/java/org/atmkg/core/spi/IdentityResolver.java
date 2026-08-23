package org.atmkg.core.spi;

import org.atmkg.core.model.SourceRecord;
import org.atmkg.core.model.mapping.EntityMappingSpec;
import org.atmkg.core.model.mapping.RelationshipMappingSpec;

public interface IdentityResolver {
    /** Stable entity UID derived from mapping plus a stable logical business-key value. */
    String entityUid(EntityMappingSpec mapping, String businessKeyValue);

    default String entityUid(EntityMappingSpec mapping, SourceRecord record, String businessKeyValue) {
        return entityUid(mapping, businessKeyValue);
    }

    String relationshipUid(RelationshipMappingSpec mapping, String sourceUid, String targetUid, SourceRecord record);
}
