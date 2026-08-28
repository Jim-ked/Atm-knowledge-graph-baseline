package org.atmkg.core.spi;

import org.atmkg.core.error.SyncException;
import org.atmkg.core.model.mapping.MappingScopeStatus;

/** Lightweight guard used by sync orchestration to isolate invalid mapping scopes. */
@FunctionalInterface
public interface MappingScopeValidator {
    MappingScopeStatus status(String sourceId, String sourceObject);

    default void requireValid(String sourceId, String sourceObject) {
        MappingScopeStatus value = status(sourceId, sourceObject);
        if (value != MappingScopeStatus.VALID) {
            throw new SyncException("Mapping scope " + sourceId + "/" + sourceObject + " 状态为 " + value);
        }
    }

    static MappingScopeValidator allowAll() { return (sourceId, sourceObject) -> MappingScopeStatus.VALID; }
}
