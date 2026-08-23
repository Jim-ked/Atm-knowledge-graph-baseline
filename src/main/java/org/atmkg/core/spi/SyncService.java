package org.atmkg.core.spi;

import java.time.Instant;
import java.util.Collection;
import org.atmkg.core.model.ChangeEvent;
import org.atmkg.core.model.SourceScope;

public interface SyncService {
    void handle(ChangeEvent event);

    /**
     * Refresh every record currently present in one source object.
     * This is a two-pass operation (all entities first, then complete projections) so references
     * between records of the same object do not depend on source row order.
     * Records that disappeared without a DELETE event are not discovered by this method.
     */
    void fullSync(String sourceId, String objectName);

    /**
     * Explicit project-wide rebuild for initial load or manual reconciliation.
     * The project projection is cleared, then all entity endpoints are written across all scopes,
     * followed by the relationship/projection pass.
     */
    void fullRebuild(Collection<SourceScope> scopes);

    void compensateSince(String sourceId, String objectName, Instant since);
    void resync(String sourceId, String objectName, String sourceKey);
}
