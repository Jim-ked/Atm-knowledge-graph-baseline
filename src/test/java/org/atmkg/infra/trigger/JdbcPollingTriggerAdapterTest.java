package org.atmkg.infra.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.atmkg.core.model.GraphEntity;
import org.atmkg.core.model.GraphRelationship;
import org.atmkg.core.model.GraphStoreStats;
import org.atmkg.core.model.GraphProjectionSnapshot;
import org.atmkg.core.model.MappingResult;
import org.atmkg.core.model.GraphDTO;
import org.atmkg.core.model.SourceRecord;
import org.atmkg.core.model.SourceRef;
import org.atmkg.core.spi.GraphStore;
import org.atmkg.core.spi.SourceAdapter;
import org.atmkg.service.sync.DefaultSyncService;
import org.atmkg.service.sync.GraphChangeNotice;
import org.atmkg.service.change.GraphChangeNeighborhoodProjector;
import org.atmkg.service.change.GraphChangeNeighborhoodResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcPollingTriggerAdapterTest {
    private static final Instant T0 = Instant.parse("2026-08-23T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-08-23T00:01:00Z");
    @TempDir Path temp;

    @Test
    void discoversKeyThenSyncServiceRereadsAuthoritativeRecordAndAdvancesWatermark() {
        FakeJdbcSource source = new FakeJdbcSource();
        source.discovered.put("airport-base", List.of(record("airport-base", "ZBAA", "scan-old", T1)));
        source.authoritative.put("airport-base/ZBAA", record("airport-base", "ZBAA", "北京首都", T1));
        RecordingStore store = new RecordingStore();
        List<GraphChangeNotice> notices = new ArrayList<>();
        List<GraphChangeNeighborhoodResult> neighborhoods = new ArrayList<>();
        GraphChangeNeighborhoodProjector projector = new GraphChangeNeighborhoodProjector(
                spec -> new GraphDTO("1", List.of(), List.of(), Map.of("anchor", spec.getStartUid())),
                neighborhoods::add);
        DefaultSyncService sync = new DefaultSyncService(Map.of("jdbc-main", source), record ->
                new MappingResult(List.of(new GraphEntity("airport:" + record.getSourceKey(),
                        "urn:test:Airport", String.valueOf(record.getFields().get("name")),
                        Map.of("name", record.getFields().get("name")), Map.of())), List.of()), store, notice -> {
                            notices.add(notice);
                            projector.accept(notice);
                        });
        JdbcPollingTriggerAdapter trigger = trigger(source,
                List.of(new JdbcPollingTriggerAdapter.PollingScope("jdbc-main", "airport-base", T0)));

        trigger.pollOnce(sync::handle);

        assertEquals("北京首都", store.lastProjection.getEntities().get(0).getProperties().get("name"));
        assertEquals(1, source.readByKeyCount);
        assertEquals(T1, trigger.watermark("jdbc-main", "airport-base"));
        assertEquals(1, notices.size());
        assertEquals(List.of("airport:ZBAA"), notices.get(0).getEntityUids());
        assertEquals(1, neighborhoods.size());
        assertEquals("airport:ZBAA", neighborhoods.get(0).getSnapshots().get(0).getAnchorUid());

        trigger.pollOnce(sync::handle);

        assertEquals(List.of(T0.minusSeconds(5), T1.minusSeconds(5)),
                source.scannedSince.get("airport-base"));
        assertEquals(1, source.readByKeyCount);
        assertEquals(1, store.replaceCount);
        assertEquals(1, notices.size());
    }

    @Test
    void maintainsIndependentWatermarksForEachSourceObject() {
        Instant routeTime = T1.plusSeconds(30);
        FakeJdbcSource source = new FakeJdbcSource();
        source.discovered.put("airport-base", List.of(record("airport-base", "ZBAA", "airport", T1)));
        source.discovered.put("route-row", List.of(record("route-row", "R001|1", "route", routeTime)));
        JdbcPollingTriggerAdapter trigger = trigger(source, List.of(
                new JdbcPollingTriggerAdapter.PollingScope("jdbc-main", "airport-base", T0),
                new JdbcPollingTriggerAdapter.PollingScope("jdbc-main", "route-row", T0)));

        trigger.pollOnce(event -> {});

        assertEquals(T1, trigger.watermark("jdbc-main", "airport-base"));
        assertEquals(routeTime, trigger.watermark("jdbc-main", "route-row"));
        assertEquals(List.of(T0.minusSeconds(5)), source.scannedSince.get("airport-base"));
        assertEquals(List.of(T0.minusSeconds(5)), source.scannedSince.get("route-row"));
    }

    @Test
    void persistedCheckpointOverridesInitialWatermarkAndIsRestoredAfterRestart() {
        PollingCheckpointStore checkpoints = checkpointStore();
        checkpoints.save("jdbc-main", "airport-base", T1);
        Instant next = T1.plusSeconds(30);
        FakeJdbcSource source = new FakeJdbcSource();
        source.discovered.put("airport-base", List.of(record("airport-base", "ZBAA", "airport", next)));

        JdbcPollingTriggerAdapter first = trigger(source,
                List.of(new JdbcPollingTriggerAdapter.PollingScope("jdbc-main", "airport-base", T0)));
        first.pollOnce(event -> {});

        assertEquals(List.of(T1.minusSeconds(5)), source.scannedSince.get("airport-base"));
        assertEquals(next, checkpoints.load("jdbc-main", "airport-base").orElseThrow());

        JdbcPollingTriggerAdapter restarted = trigger(new FakeJdbcSource(),
                List.of(new JdbcPollingTriggerAdapter.PollingScope("jdbc-main", "airport-base", T0)));
        assertEquals(next, restarted.watermark("jdbc-main", "airport-base"));
    }

    @Test
    void lookbackAllowsSameTimestampRecordToBeProcessedAgainWithoutMovingCheckpointBackward() {
        PollingCheckpointStore checkpoints = checkpointStore();
        checkpoints.save("jdbc-main", "airport-base", T1);
        FakeJdbcSource source = new FakeJdbcSource();
        source.discovered.put("airport-base", List.of(record("airport-base", "LATE", "late", T1)));
        JdbcPollingTriggerAdapter trigger = trigger(source,
                List.of(new JdbcPollingTriggerAdapter.PollingScope("jdbc-main", "airport-base", T0)));
        List<String> keys = new ArrayList<>();

        trigger.pollOnce(event -> keys.add(event.getSourceKey()));

        assertEquals(List.of("LATE"), keys);
        assertEquals(List.of(T1.minusSeconds(5)), source.scannedSince.get("airport-base"));
        assertEquals(T1, trigger.watermark("jdbc-main", "airport-base"));
    }

    @Test
    void successfulBatchPersistsMaximumSourceTimestamp() {
        Instant latest = T1.plusSeconds(45);
        FakeJdbcSource source = new FakeJdbcSource();
        source.discovered.put("airport-base", List.of(
                record("airport-base", "A", "first", T1),
                record("airport-base", "B", "latest", latest),
                record("airport-base", "C", "middle", T1.plusSeconds(10))));
        JdbcPollingTriggerAdapter trigger = trigger(source,
                List.of(new JdbcPollingTriggerAdapter.PollingScope("jdbc-main", "airport-base", T0)));

        trigger.pollOnce(event -> {});

        assertEquals(latest, trigger.watermark("jdbc-main", "airport-base"));
        assertEquals(latest, checkpointStore().load("jdbc-main", "airport-base").orElseThrow());
    }

    @Test
    void emptyScanDoesNotCreateOrAdvanceCheckpoint() {
        FakeJdbcSource source = new FakeJdbcSource();
        JdbcPollingTriggerAdapter trigger = trigger(source,
                List.of(new JdbcPollingTriggerAdapter.PollingScope("jdbc-main", "airport-base", T0)));

        trigger.pollOnce(event -> {});

        assertEquals(T0, trigger.watermark("jdbc-main", "airport-base"));
        assertTrue(checkpointStore().load("jdbc-main", "airport-base").isEmpty());
        assertFalse(Files.exists(checkpointFile()));
    }

    @Test
    void checkpointSaveFailureFailsTheRoundAndKeepsInMemoryWatermark() throws Exception {
        FakeJdbcSource source = new FakeJdbcSource();
        source.discovered.put("airport-base", List.of(record("airport-base", "ZBAA", "airport", T1)));
        JdbcPollingTriggerAdapter trigger = trigger(source,
                List.of(new JdbcPollingTriggerAdapter.PollingScope("jdbc-main", "airport-base", T0)));
        Files.createDirectories(checkpointFile());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> trigger.pollOnce(event -> {}));

        assertTrue(failure.getMessage().contains("polling checkpoint"));
        assertEquals(T0, trigger.watermark("jdbc-main", "airport-base"));
    }

    @Test
    void failedConsumerDoesNotAdvanceWatermarkAndDeterministicEventCanBeRetried() {
        FakeJdbcSource source = new FakeJdbcSource();
        source.discovered.put("airport-base", List.of(record("airport-base", "ZBAA", "airport", T1)));
        JdbcPollingTriggerAdapter trigger = trigger(source,
                List.of(new JdbcPollingTriggerAdapter.PollingScope("jdbc-main", "airport-base", T0)));
        AtomicBoolean failFirst = new AtomicBoolean(true);
        List<String> eventIds = new ArrayList<>();

        assertThrows(IllegalStateException.class, () -> trigger.pollOnce(event -> {
            eventIds.add(event.getEventId());
            if (failFirst.getAndSet(false)) throw new IllegalStateException("simulated sync failure");
        }));
        assertEquals(T0, trigger.watermark("jdbc-main", "airport-base"));

        trigger.pollOnce(event -> eventIds.add(event.getEventId()));

        assertEquals(T1, trigger.watermark("jdbc-main", "airport-base"));
        assertEquals(2, eventIds.size());
        assertEquals(eventIds.get(0), eventIds.get(1));
    }

    @Test
    void failedConsumerStillClosesChangedRecordIterator() {
        FakeJdbcSource source = new FakeJdbcSource();
        CloseTrackingIterable changed = new CloseTrackingIterable(
                List.of(record("airport-base", "ZBAA", "airport", T1)));
        source.changedRecords = changed;
        JdbcPollingTriggerAdapter trigger = trigger(source,
                List.of(new JdbcPollingTriggerAdapter.PollingScope("jdbc-main", "airport-base", T0)));

        assertThrows(IllegalStateException.class, () -> trigger.pollOnce(event -> {
            throw new IllegalStateException("consumer failure");
        }));

        assertTrue(changed.closed);
        assertEquals(T0, trigger.watermark("jdbc-main", "airport-base"));
    }

    @Test
    void pollingGraphWriteFailurePublishesNoNoticeUntilSuccessfulRetry() {
        FakeJdbcSource source = new FakeJdbcSource();
        source.discovered.put("airport-base", List.of(record("airport-base", "ZBAA", "scan-old", T1)));
        source.authoritative.put("airport-base/ZBAA", record("airport-base", "ZBAA", "北京首都", T1));
        RecordingStore store = new RecordingStore();
        store.failNextReplace = true;
        List<GraphChangeNotice> notices = new ArrayList<>();
        DefaultSyncService sync = new DefaultSyncService(Map.of("jdbc-main", source), record ->
                new MappingResult(List.of(new GraphEntity("airport:ZBAA", "urn:test:Airport", "北京首都",
                        Map.of(), Map.of())), List.of()), store, notices::add);
        JdbcPollingTriggerAdapter trigger = trigger(source,
                List.of(new JdbcPollingTriggerAdapter.PollingScope("jdbc-main", "airport-base", T0)));

        assertThrows(IllegalStateException.class, () -> trigger.pollOnce(sync::handle));
        assertEquals(T0, trigger.watermark("jdbc-main", "airport-base"));
        assertTrue(notices.isEmpty());

        trigger.pollOnce(sync::handle);
        assertEquals(T1, trigger.watermark("jdbc-main", "airport-base"));
        assertEquals(1, notices.size());
    }

    @Test
    void pollingGraphChangeFailureDoesNotAdvanceCheckpointAndRetriesTheProjection() {
        FakeJdbcSource source = new FakeJdbcSource();
        source.discovered.put("airport-base", List.of(record("airport-base", "ZBAA", "scan-old", T1)));
        source.authoritative.put("airport-base/ZBAA", record("airport-base", "ZBAA", "北京首都", T1));
        RecordingStore store = new RecordingStore();
        AtomicBoolean failFirstGraphChange = new AtomicBoolean(true);
        List<GraphChangeNotice> delivered = new ArrayList<>();
        DefaultSyncService sync = new DefaultSyncService(Map.of("jdbc-main", source), record ->
                new MappingResult(List.of(new GraphEntity("airport:ZBAA", "urn:test:Airport", "北京首都",
                        Map.of(), Map.of())), List.of()), store, notice -> {
                            if (failFirstGraphChange.getAndSet(false)) {
                                throw new IllegalStateException("simulated GraphChange failure");
                            }
                            delivered.add(notice);
                        });
        JdbcPollingTriggerAdapter trigger = trigger(source,
                List.of(new JdbcPollingTriggerAdapter.PollingScope("jdbc-main", "airport-base", T0)));

        assertThrows(IllegalStateException.class, () -> trigger.pollOnce(sync::handle));
        assertEquals(T0, trigger.watermark("jdbc-main", "airport-base"));
        assertTrue(checkpointStore().load("jdbc-main", "airport-base").isEmpty());

        trigger.pollOnce(sync::handle);

        assertEquals(T1, trigger.watermark("jdbc-main", "airport-base"));
        assertEquals(2, store.replaceCount);
        assertEquals(1, delivered.size());
    }

    @Test
    void rejectsDiscoveryWithoutStrictlyNewSourceTimestamp() {
        FakeJdbcSource source = new FakeJdbcSource();
        source.discovered.put("airport-base", List.of(record("airport-base", "ZBAA", "airport", null)));
        JdbcPollingTriggerAdapter trigger = trigger(source,
                List.of(new JdbcPollingTriggerAdapter.PollingScope("jdbc-main", "airport-base", T0)));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> trigger.pollOnce(event -> {}));

        assertTrue(failure.getMessage().contains("sourceTimestamp"));
        assertEquals(T0, trigger.watermark("jdbc-main", "airport-base"));
    }

    @Test
    void startSchedulesPollingAndStopAllowsAControlledRestart() throws Exception {
        FakeJdbcSource source = new FakeJdbcSource();
        source.discovered.put("airport-base", List.of(record("airport-base", "ZBAA", "airport", T1)));
        JdbcPollingTriggerAdapter trigger = trigger(source,
                List.of(new JdbcPollingTriggerAdapter.PollingScope("jdbc-main", "airport-base", T0)));
        CountDownLatch received = new CountDownLatch(1);

        trigger.start(event -> received.countDown());
        try {
            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> trigger.start(event -> {}));
        } finally {
            trigger.stop();
        }

        trigger.start(event -> {});
        trigger.stop();
    }

    private JdbcPollingTriggerAdapter trigger(FakeJdbcSource source,
                                              List<JdbcPollingTriggerAdapter.PollingScope> scopes) {
        return new JdbcPollingTriggerAdapter(Map.of("jdbc-main", source), scopes, Duration.ofSeconds(30),
                Duration.ofSeconds(5), checkpointStore());
    }

    private PollingCheckpointStore checkpointStore() {
        return new PollingCheckpointStore(checkpointFile());
    }

    private Path checkpointFile() {
        return temp.resolve("runtime/state/polling-checkpoints.json");
    }

    private static SourceRecord record(String objectName, String key, String name, Instant timestamp) {
        return new SourceRecord("jdbc-main", objectName, key, Map.of("name", name), timestamp);
    }

    private static final class FakeJdbcSource implements SourceAdapter {
        final Map<String, List<SourceRecord>> discovered = new LinkedHashMap<>();
        final Map<String, SourceRecord> authoritative = new LinkedHashMap<>();
        final Map<String, List<Instant>> scannedSince = new LinkedHashMap<>();
        Iterable<SourceRecord> changedRecords;
        int readByKeyCount;

        @Override
        public Iterable<SourceRecord> readAll(String objectName) {
            return List.of();
        }

        @Override
        public Optional<SourceRecord> readByKey(String objectName, String sourceKey) {
            readByKeyCount++;
            return Optional.ofNullable(authoritative.get(objectName + "/" + sourceKey));
        }

        @Override
        public Iterable<SourceRecord> scanChangedSince(String objectName, Instant since) {
            scannedSince.computeIfAbsent(objectName, ignored -> new ArrayList<>()).add(since);
            if (changedRecords != null) return changedRecords;
            return discovered.getOrDefault(objectName, List.of()).stream()
                    .filter(record -> record.getSourceTimestamp() == null
                            || record.getSourceTimestamp().isAfter(since))
                    .toList();
        }
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
        MappingResult lastProjection;
        int replaceCount;
        boolean failNextReplace;

        @Override public void initializeSchema() {}
        @Override public void upsertEntities(Collection<GraphEntity> entities) {}
        @Override public void upsertRelationships(Collection<GraphRelationship> relationships) {}
        @Override public void replaceProjection(SourceRef sourceRef, MappingResult currentProjection) {
            if (failNextReplace) {
                failNextReplace = false;
                throw new IllegalStateException("simulated write failure");
            }
            lastProjection = currentProjection;
            replaceCount++;
        }
        @Override public GraphProjectionSnapshot deleteProjection(SourceRef sourceRef) {
            return GraphProjectionSnapshot.empty();
        }
        @Override public void deleteEntity(String uid) {}
        @Override public void deleteRelationship(String uid) {}
        @Override public Optional<GraphEntity> findEntity(String uid) { return Optional.empty(); }
        @Override public void clearProject() {}
        @Override public GraphStoreStats stats() { return new GraphStoreStats(0, 0); }
    }
}
