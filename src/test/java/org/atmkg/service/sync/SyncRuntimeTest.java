package org.atmkg.service.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.atmkg.core.model.ChangeEvent;
import org.atmkg.core.model.GraphEntity;
import org.atmkg.core.model.GraphRelationship;
import org.atmkg.core.model.GraphStoreStats;
import org.atmkg.core.model.MappingResult;
import org.atmkg.core.model.SourceRecord;
import org.atmkg.core.model.SourceRef;
import org.atmkg.core.spi.GraphStore;
import org.atmkg.core.spi.SourceAdapter;
import org.atmkg.core.spi.SyncService;
import org.atmkg.core.spi.TriggerAdapter;
import org.junit.jupiter.api.Test;

class SyncRuntimeTest {
    @Test
    void startsPollingThroughDefaultSyncServiceAndStopsItOnClose() {
        SourceRecord authoritative = new SourceRecord("jdbc-main", "airport-base", "ZBAA",
                Map.of("name", "北京首都"), Instant.parse("2026-08-23T00:01:00Z"));
        SourceAdapter source = new SourceAdapter() {
            @Override public Iterable<SourceRecord> readAll(String objectName) { return List.of(authoritative); }
            @Override public Optional<SourceRecord> readByKey(String objectName, String sourceKey) {
                return Optional.of(authoritative);
            }
            @Override public Iterable<SourceRecord> scanChangedSince(String objectName, Instant since) {
                return List.of();
            }
        };
        RecordingStore store = new RecordingStore();
        DefaultSyncService sync = new DefaultSyncService(Map.of("jdbc-main", source), record ->
                new MappingResult(List.of(new GraphEntity("airport:ZBAA", "urn:test:Airport", "北京首都",
                        Map.of("name", record.getFields().get("name")), Map.of())), List.of()), store);
        RecordingTrigger polling = new RecordingTrigger();
        SyncRuntime runtime = SyncRuntime.enabled(sync, polling);

        runtime.start();

        assertTrue(polling.started);
        assertEquals("北京首都", store.lastProjection.getEntities().get(0).getProperties().get("name"));
        runtime.close();
        assertTrue(polling.stopped);
    }

    @Test
    void disabledRuntimeIsSafeForQueryOnlyMode() {
        SyncRuntime runtime = SyncRuntime.disabled();

        runtime.start();
        runtime.close();

        assertFalse(runtime.isEnabled());
        assertFalse(runtime.isPollingEnabled());
    }

    @Test
    void pollingStartupFailureStopsPartiallyStartedTrigger() {
        class FailingTrigger implements TriggerAdapter {
            boolean stopped;
            @Override public void start(Consumer<ChangeEvent> consumer) {
                throw new IllegalStateException("startup failed");
            }
            @Override public void stop() { stopped = true; }
        }
        FailingTrigger failing = new FailingTrigger();
        SyncService sync = new SyncService() {
            @Override public void handle(ChangeEvent event) {}
            @Override public void fullSync(String sourceId, String objectName) {}
            @Override public void fullRebuild(Collection<org.atmkg.core.model.SourceScope> scopes) {}
            @Override public void compensateSince(String sourceId, String objectName, Instant since) {}
            @Override public void resync(String sourceId, String objectName, String sourceKey) {}
        };
        SyncRuntime runtime = SyncRuntime.enabled(sync, failing);

        assertThrows(IllegalStateException.class, runtime::start);
        assertTrue(failing.stopped);
    }

    private static final class RecordingTrigger implements TriggerAdapter {
        boolean started;
        boolean stopped;

        @Override
        public void start(Consumer<ChangeEvent> consumer) {
            started = true;
            consumer.accept(new ChangeEvent("E1", "jdbc-main", "airport-base", "ZBAA",
                    ChangeEvent.Operation.UPSERT, Instant.parse("2026-08-23T00:01:00Z")));
        }

        @Override public void stop() { stopped = true; }
    }

    private static final class RecordingStore implements GraphStore {
        MappingResult lastProjection;
        @Override public void initializeSchema() {}
        @Override public void upsertEntities(Collection<GraphEntity> entities) {}
        @Override public void upsertRelationships(Collection<GraphRelationship> relationships) {}
        @Override public void replaceProjection(SourceRef sourceRef, MappingResult currentProjection) {
            lastProjection = currentProjection;
        }
        @Override public void deleteProjection(SourceRef sourceRef) {}
        @Override public void deleteEntity(String uid) {}
        @Override public void deleteRelationship(String uid) {}
        @Override public Optional<GraphEntity> findEntity(String uid) { return Optional.empty(); }
        @Override public void clearProject() {}
        @Override public GraphStoreStats stats() { return new GraphStoreStats(0, 0); }
    }
}
