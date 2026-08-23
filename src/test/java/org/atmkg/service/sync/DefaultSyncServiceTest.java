package org.atmkg.service.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.atmkg.core.model.ChangeEvent;
import org.atmkg.core.error.SyncException;
import org.atmkg.core.model.GraphEntity;
import org.atmkg.core.model.GraphRelationship;
import org.atmkg.core.model.GraphStoreStats;
import org.atmkg.core.model.MappingResult;
import org.atmkg.core.model.SourceRecord;
import org.atmkg.core.model.SourceRef;
import org.atmkg.core.spi.GraphStore;
import org.atmkg.core.spi.MappingEngine;
import org.atmkg.core.spi.SourceAdapter;
import org.junit.jupiter.api.Test;

class DefaultSyncServiceTest {
    private static final SourceRecord RECORD = new SourceRecord(
            "fixture", "OBJECT", "K1", Map.of("id", "K1"), Instant.parse("2026-08-21T00:00:00Z"));

    @Test
    void upsertEventRereadsAuthoritativeSourceAndReplacesProjection() {
        RecordingStore store = new RecordingStore();
        SourceAdapter adapter = adapter(Optional.of(RECORD));
        MappingEngine mapping = r -> new MappingResult(
                List.of(new GraphEntity("U1", "urn:test:Entity", "K1", Map.of(), Map.of())), List.of());
        DefaultSyncService service = new DefaultSyncService(Map.of("fixture", adapter), mapping, store);

        service.handle(new ChangeEvent("E1", "fixture", "OBJECT", "K1",
                ChangeEvent.Operation.UPSERT, Instant.now()));

        assertEquals("K1", store.lastReplaced.getSourceKey());
        assertEquals(1, store.lastProjection.getEntities().size());
    }

    @Test
    void missingAuthoritativeRecordRemovesExistingProjection() {
        RecordingStore store = new RecordingStore();
        DefaultSyncService service = new DefaultSyncService(
                Map.of("fixture", adapter(Optional.empty())), r -> new MappingResult(List.of(), List.of()), store);

        service.resync("fixture", "OBJECT", "K1");
        assertEquals("K1", store.lastDeleted.getSourceKey());
    }

    @Test
    void deleteEventDoesNotNeedSourcePayload() {
        RecordingStore store = new RecordingStore();
        DefaultSyncService service = new DefaultSyncService(
                Map.of("fixture", adapter(Optional.empty())), r -> new MappingResult(List.of(), List.of()), store);

        service.handle(new ChangeEvent("E2", "fixture", "OBJECT", "K1",
                ChangeEvent.Operation.DELETE, Instant.now()));
        assertEquals("K1", store.lastDeleted.getSourceKey());
    }

    @Test
    void duplicateEventIdIsSkippedAfterSuccessfulProcessing() {
        RecordingStore store = new RecordingStore();
        DefaultSyncService service = new DefaultSyncService(
                Map.of("fixture", adapter(Optional.of(RECORD))),
                r -> new MappingResult(List.of(new GraphEntity("U1", "urn:test:Entity", "K1", Map.of(), Map.of())), List.of()),
                store);
        ChangeEvent event = new ChangeEvent("E-DUP", "fixture", "OBJECT", "K1",
                ChangeEvent.Operation.UPSERT, Instant.now());

        service.handle(event);
        service.handle(event);

        assertEquals(1, store.replaceCount);
    }

    @Test
    void failedEventIsNotMarkedProcessedAndCanBeRetried() {
        RecordingStore store = new RecordingStore();
        store.failNextReplace = true;
        DefaultSyncService service = new DefaultSyncService(
                Map.of("fixture", adapter(Optional.of(RECORD))),
                r -> new MappingResult(List.of(new GraphEntity("U1", "urn:test:Entity", "K1", Map.of(), Map.of())), List.of()),
                store);
        ChangeEvent event = new ChangeEvent("E-RETRY", "fixture", "OBJECT", "K1",
                ChangeEvent.Operation.UPSERT, Instant.now());

        assertThrows(IllegalStateException.class, () -> service.handle(event));
        service.handle(event);

        assertEquals(2, store.replaceAttempts);
        assertEquals(1, store.replaceCount);
    }

    @Test
    void resyncRejectsRecordWhoseReturnedSourceKeyDoesNotMatchLookupKey() {
        DefaultSyncService service = new DefaultSyncService(
                Map.of("fixture", adapter(Optional.of(RECORD))),
                r -> new MappingResult(List.of(), List.of()), new RecordingStore());

        assertThrows(SyncException.class, () -> service.resync("fixture", "OBJECT", "K2"));
    }

    private SourceAdapter adapter(Optional<SourceRecord> byKey) {
        return new SourceAdapter() {
            public Iterable<SourceRecord> readAll(String objectName) { return byKey.map(List::of).orElseGet(List::of); }
            public Optional<SourceRecord> readByKey(String objectName, String sourceKey) { return byKey; }
            public Iterable<SourceRecord> scanChangedSince(String objectName, Instant since) { return byKey.map(List::of).orElseGet(List::of); }
        };
    }

    private static final class RecordingStore implements GraphStore {
        SourceRef lastReplaced;
        SourceRef lastDeleted;
        MappingResult lastProjection;
        int replaceAttempts;
        int replaceCount;
        boolean failNextReplace;
        public void initializeSchema() {}
        public void upsertEntities(Collection<GraphEntity> entities) {}
        public void upsertRelationships(Collection<GraphRelationship> relationships) {}
        public void replaceProjection(SourceRef sourceRef, MappingResult currentProjection) {
            replaceAttempts++;
            if (failNextReplace) {
                failNextReplace = false;
                throw new IllegalStateException("simulated write failure");
            }
            lastReplaced = sourceRef; lastProjection = currentProjection;
            replaceCount++;
        }
        public void deleteProjection(SourceRef sourceRef) { lastDeleted = sourceRef; }
        public void deleteEntity(String uid) {}
        public void deleteRelationship(String uid) {}
        public Optional<GraphEntity> findEntity(String uid) { return Optional.empty(); }
        public void clearProject() {}
        public GraphStoreStats stats() { return new GraphStoreStats(0, 0); }
    }
}
