package org.atmkg.service.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Supplier;
import org.atmkg.core.model.ChangeEvent;
import org.atmkg.core.error.SyncException;
import org.atmkg.core.model.GraphEntity;
import org.atmkg.core.model.GraphRelationship;
import org.atmkg.core.model.GraphStoreStats;
import org.atmkg.core.model.MappingResult;
import org.atmkg.core.model.SourceRecord;
import org.atmkg.core.model.SourceRef;
import org.atmkg.core.model.SourceScope;
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
    void successfulUpsertPublishesActualMappedEntityAndRelationshipUids() {
        RecordingStore store = new RecordingStore();
        List<GraphChangeNotice> notices = new ArrayList<>();
        MappingResult mapped = new MappingResult(
                List.of(
                        new GraphEntity("U1", "urn:test:Entity", "one", Map.of(), Map.of()),
                        new GraphEntity("U2", "urn:test:Entity", "two", Map.of(), Map.of())),
                List.of(new GraphRelationship("R1", "urn:test:related", "U1", "U2", Map.of(), Map.of())));
        DefaultSyncService service = new DefaultSyncService(
                Map.of("fixture", adapter(Optional.of(RECORD))), record -> mapped, store, notices::add);

        service.handle(new ChangeEvent("E-NOTICE", "fixture", "OBJECT", "K1",
                ChangeEvent.Operation.UPSERT, Instant.parse("2026-08-21T00:01:00Z")));

        assertEquals(1, notices.size());
        GraphChangeNotice notice = notices.get(0);
        assertEquals(GraphChangeNotice.Operation.UPSERT, notice.getOperation());
        assertEquals("K1", notice.getSourceRef().getSourceKey());
        assertEquals(List.of("U1", "U2"), notice.getEntityUids());
        assertEquals(List.of("R1"), notice.getRelationshipUids());
    }

    @Test
    void mappingFailureDoesNotPublishNotice() {
        List<GraphChangeNotice> notices = new ArrayList<>();
        DefaultSyncService service = new DefaultSyncService(
                Map.of("fixture", adapter(Optional.of(RECORD))),
                record -> { throw new IllegalStateException("simulated mapping failure"); },
                new RecordingStore(), notices::add);

        assertThrows(SyncException.class, () -> service.handle(new ChangeEvent(
                "E-MAP-FAIL", "fixture", "OBJECT", "K1", ChangeEvent.Operation.UPSERT, Instant.now())));

        assertTrue(notices.isEmpty());
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
        List<GraphChangeNotice> notices = new ArrayList<>();
        DefaultSyncService service = new DefaultSyncService(
                Map.of("fixture", adapter(Optional.empty())), r -> new MappingResult(List.of(), List.of()),
                store, notices::add);

        service.handle(new ChangeEvent("E2", "fixture", "OBJECT", "K1",
                ChangeEvent.Operation.DELETE, Instant.now()));
        assertEquals("K1", store.lastDeleted.getSourceKey());
        assertEquals(1, notices.size());
        assertEquals(GraphChangeNotice.Operation.DELETE, notices.get(0).getOperation());
        assertTrue(notices.get(0).getEntityUids().isEmpty());
        assertTrue(notices.get(0).getRelationshipUids().isEmpty());
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
    void recentEventCacheIsBoundedAndEvictedIdsCanBeProcessedAgain() {
        RecordingStore store = new RecordingStore();
        DefaultSyncService service = new DefaultSyncService(
                Map.of("fixture", adapter(Optional.of(RECORD))),
                r -> new MappingResult(List.of(), List.of()), store);
        ChangeEvent oldest = new ChangeEvent("E-0", "fixture", "OBJECT", "K1",
                ChangeEvent.Operation.UPSERT, Instant.now());

        service.handle(oldest);
        service.handle(oldest);
        for (int index = 1; index <= DefaultSyncService.RECENT_EVENT_LIMIT; index++) {
            service.handle(new ChangeEvent("E-" + index, "fixture", "OBJECT", "K1",
                    ChangeEvent.Operation.UPSERT, Instant.now()));
        }
        service.handle(oldest);

        assertEquals(DefaultSyncService.RECENT_EVENT_LIMIT + 2, store.replaceCount);
    }

    @Test
    void fullSyncClosesReadAllIteratorWhenMappingFails() {
        CloseTrackingIterable records = new CloseTrackingIterable(List.of(RECORD));
        DefaultSyncService service = new DefaultSyncService(
                Map.of("fixture", adapter(Optional.of(RECORD), () -> records, List::of)),
                record -> { throw new IllegalStateException("mapping failure"); }, new RecordingStore());

        assertThrows(SyncException.class, () -> service.fullSync("fixture", "OBJECT"));

        assertTrue(records.closed);
    }

    @Test
    void fullSyncClosesSecondPassIteratorWhenGraphStoreFails() {
        CloseTrackingIterable secondPass = new CloseTrackingIterable(List.of(RECORD));
        ArrayDeque<Iterable<SourceRecord>> passes = new ArrayDeque<>();
        passes.add(List.of(RECORD));
        passes.add(secondPass);
        RecordingStore store = new RecordingStore();
        store.failNextReplace = true;
        DefaultSyncService service = new DefaultSyncService(
                Map.of("fixture", adapter(Optional.of(RECORD), passes::removeFirst, List::of)),
                record -> new MappingResult(List.of(), List.of()), store);

        assertThrows(IllegalStateException.class, () -> service.fullSync("fixture", "OBJECT"));

        assertTrue(secondPass.closed);
    }

    @Test
    void compensationClosesIteratorWhenRecordScopeIsInvalid() {
        SourceRecord invalid = new SourceRecord(
                "other", "OBJECT", "K1", Map.of(), Instant.parse("2026-08-21T01:00:00Z"));
        CloseTrackingIterable changed = new CloseTrackingIterable(List.of(invalid));
        DefaultSyncService service = new DefaultSyncService(
                Map.of("fixture", adapter(Optional.of(RECORD), List::of, () -> changed)),
                record -> new MappingResult(List.of(), List.of()), new RecordingStore());

        assertThrows(SyncException.class, () -> service.compensateSince(
                "fixture", "OBJECT", Instant.parse("2026-08-21T00:00:00Z")));

        assertTrue(changed.closed);
    }

    @Test
    void failedEventIsNotMarkedProcessedAndCanBeRetried() {
        RecordingStore store = new RecordingStore();
        store.failNextReplace = true;
        List<GraphChangeNotice> notices = new ArrayList<>();
        DefaultSyncService service = new DefaultSyncService(
                Map.of("fixture", adapter(Optional.of(RECORD))),
                r -> new MappingResult(List.of(new GraphEntity("U1", "urn:test:Entity", "K1", Map.of(), Map.of())), List.of()),
                store, notices::add);
        ChangeEvent event = new ChangeEvent("E-RETRY", "fixture", "OBJECT", "K1",
                ChangeEvent.Operation.UPSERT, Instant.now());

        assertThrows(IllegalStateException.class, () -> service.handle(event));
        assertTrue(notices.isEmpty());
        service.handle(event);

        assertEquals(2, store.replaceAttempts);
        assertEquals(1, store.replaceCount);
        assertEquals(1, notices.size());
    }

    @Test
    void fullSyncAndFullRebuildDoNotPublishIncrementalNotices() {
        List<GraphChangeNotice> notices = new ArrayList<>();
        DefaultSyncService service = new DefaultSyncService(
                Map.of("fixture", adapter(Optional.of(RECORD))),
                record -> new MappingResult(
                        List.of(new GraphEntity("U1", "urn:test:Entity", "K1", Map.of(), Map.of())), List.of()),
                new RecordingStore(), notices::add);

        service.fullSync("fixture", "OBJECT");
        service.fullRebuild(List.of(new SourceScope("fixture", "OBJECT")));

        assertTrue(notices.isEmpty());
    }

    @Test
    void upsertWhoseAuthoritativeRecordDisappearedDoesNotGuessAffectedUids() {
        List<GraphChangeNotice> notices = new ArrayList<>();
        DefaultSyncService service = new DefaultSyncService(
                Map.of("fixture", adapter(Optional.empty())),
                record -> new MappingResult(List.of(), List.of()), new RecordingStore(), notices::add);

        service.handle(new ChangeEvent("E-MISSING", "fixture", "OBJECT", "K1",
                ChangeEvent.Operation.UPSERT, Instant.now()));

        assertTrue(notices.isEmpty());
    }

    @Test
    void resyncRejectsRecordWhoseReturnedSourceKeyDoesNotMatchLookupKey() {
        DefaultSyncService service = new DefaultSyncService(
                Map.of("fixture", adapter(Optional.of(RECORD))),
                r -> new MappingResult(List.of(), List.of()), new RecordingStore());

        assertThrows(SyncException.class, () -> service.resync("fixture", "OBJECT", "K2"));
    }

    private SourceAdapter adapter(Optional<SourceRecord> byKey) {
        return adapter(byKey, () -> byKey.map(List::of).orElseGet(List::of),
                () -> byKey.map(List::of).orElseGet(List::of));
    }

    private SourceAdapter adapter(Optional<SourceRecord> byKey,
                                  Supplier<Iterable<SourceRecord>> readAll,
                                  Supplier<Iterable<SourceRecord>> changed) {
        return new SourceAdapter() {
            public Iterable<SourceRecord> readAll(String objectName) { return readAll.get(); }
            public Optional<SourceRecord> readByKey(String objectName, String sourceKey) { return byKey; }
            public Iterable<SourceRecord> scanChangedSince(String objectName, Instant since) { return changed.get(); }
        };
    }

    private static final class CloseTrackingIterable implements Iterable<SourceRecord> {
        private final List<SourceRecord> records;
        private boolean closed;

        private CloseTrackingIterable(List<SourceRecord> records) {
            this.records = records;
        }

        @Override
        public Iterator<SourceRecord> iterator() {
            return new CloseTrackingIterator();
        }

        private final class CloseTrackingIterator implements Iterator<SourceRecord>, AutoCloseable {
            private int index;
            @Override public boolean hasNext() { return index < records.size(); }
            @Override public SourceRecord next() {
                if (!hasNext()) throw new NoSuchElementException();
                return records.get(index++);
            }
            @Override public void close() { closed = true; }
        }
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
