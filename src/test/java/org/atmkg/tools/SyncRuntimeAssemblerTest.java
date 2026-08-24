package org.atmkg.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.atmkg.core.model.ChangeEvent;
import org.atmkg.core.model.GraphEntity;
import org.atmkg.core.model.GraphProjectionSnapshot;
import org.atmkg.core.model.GraphRelationship;
import org.atmkg.core.model.GraphStoreStats;
import org.atmkg.core.model.MappingResult;
import org.atmkg.core.model.SourceRecord;
import org.atmkg.core.model.SourceRef;
import org.atmkg.core.spi.GraphStore;
import org.atmkg.core.spi.SourceAdapter;
import org.atmkg.service.sync.GraphChangeNotice;
import org.atmkg.service.sync.DefaultSyncService;
import org.atmkg.service.sync.SyncRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyncRuntimeAssemblerTest {
    @TempDir Path temp;

    @Test
    void emptySourcesShortCircuitToQueryOnlyWithoutLoadingMappingOrGraphStore() throws Exception {
        writeConfigs("sources: []\n", disabledPolling());

        SyncRuntime runtime = SyncRuntimeAssembler.assemble(temp, plan -> {
            throw new AssertionError("空 sources 不应创建 Mapping/GraphStore/SyncService");
        });

        assertFalse(runtime.isEnabled());
    }

    @Test
    void oldAndListenerAwareFormalOverloadsBothPreserveEmptySourceQueryOnlyMode() throws Exception {
        writeConfigs("sources: []\n", disabledPolling());

        SyncRuntime oldRuntime = SyncRuntimeAssembler.assemble(temp, null, null, null);
        SyncRuntime listenerRuntime = SyncRuntimeAssembler.assemble(temp, null, null, null, notice -> {
            throw new AssertionError("query-only mode 不应产生 GraphChange");
        });

        assertFalse(oldRuntime.isEnabled());
        assertFalse(listenerRuntime.isEnabled());
    }

    @Test
    void listenerInjectionReachesTheCreatedDefaultSyncService() {
        SourceRecord record = new SourceRecord("fixture", "OBJECT", "K1", Map.of(), Instant.now());
        SourceAdapter adapter = new SourceAdapter() {
            @Override public Iterable<SourceRecord> readAll(String objectName) { return List.of(record); }
            @Override public Optional<SourceRecord> readByKey(String objectName, String sourceKey) {
                return Optional.of(record);
            }
            @Override public Iterable<SourceRecord> scanChangedSince(String objectName, Instant since) {
                return List.of(record);
            }
        };
        List<GraphChangeNotice> notices = new ArrayList<>();
        DefaultSyncService sync = SyncRuntimeAssembler.syncService(
                Map.of("fixture", adapter), value -> new MappingResult(
                        List.of(new GraphEntity("U1", "urn:test:Entity", "K1", Map.of(), Map.of())), List.of()),
                new NoOpGraphStore(), notices::add);

        sync.handle(new ChangeEvent("E1", "fixture", "OBJECT", "K1",
                ChangeEvent.Operation.UPSERT, Instant.now()));

        assertEquals(1, notices.size());
        assertEquals(List.of("U1"), notices.get(0).getEntityUids());
    }

    @Test
    void planReadsOnlyFormalSourcesAndIgnoresLocalOrFixtureFiles() throws Exception {
        writeConfigs("sources: []\n", disabledPolling());
        Files.writeString(temp.resolve("config/sources.local.yaml"), "not: valid formal sources\n");
        Path fixture = Files.createDirectories(temp.resolve("fixtures/mapping"));
        Files.writeString(fixture.resolve("fixture_mapping.xlsx"), "not an xlsx");

        SyncRuntimeAssembler.AssemblyPlan plan = SyncRuntimeAssembler.plan(temp);

        assertTrue(plan.sources().getSources().isEmpty());
    }

    @Test
    void rejectsUnknownSourceAdapterBeforeRuntimeConstruction() throws Exception {
        writeConfigs("""
                sources:
                  - sourceId: unsupported-main
                    adapter: csv
                    objects: {}
                """, disabledPolling());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SyncRuntimeAssembler.assemble(temp, plan -> SyncRuntime.disabled()));

        assertTrue(failure.getMessage().contains("未知 SourceAdapter"));
    }

    @Test
    void rejectsPollingScopeThatDoesNotExistInConfiguredJdbcSource() throws Exception {
        writeConfigs("""
                sources:
                  - sourceId: jdbc-main
                    adapter: jdbc
                    objects:
                      airport-base:
                        table: airports
                        keyFields: [airport_code]
                        watermarkField: updated_at
                """, """
                sync:
                  initialFullImport: true
                  incremental: true
                  compensation: true
                  manualResync: true
                  eventCarriesAuthoritativeData: false
                  polling:
                    enabled: true
                    intervalSeconds: 30
                    lookbackSeconds: 5
                    scopes:
                      - sourceId: jdbc-main
                        sourceObject: missing-object
                        initialWatermark: '2026-08-23T00:00:00Z'
                """);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SyncRuntimeAssembler.assemble(temp, plan -> SyncRuntime.disabled()));

        assertTrue(failure.getMessage().contains("missing-object"));
        assertTrue(failure.getMessage().contains("不存在"));
    }

    private void writeConfigs(String sources, String sync) throws Exception {
        Path config = Files.createDirectories(temp.resolve("config"));
        Files.writeString(config.resolve("sources.yaml"), sources);
        Files.writeString(config.resolve("sync.yaml"), sync);
    }

    private static String disabledPolling() {
        return """
                sync:
                  initialFullImport: true
                  incremental: true
                  compensation: true
                  manualResync: true
                  eventCarriesAuthoritativeData: false
                  polling:
                    enabled: false
                    intervalSeconds: 30
                    lookbackSeconds: 5
                    scopes: []
                """;
    }

    private static final class NoOpGraphStore implements GraphStore {
        @Override public void initializeSchema() {}
        @Override public void upsertEntities(Collection<GraphEntity> entities) {}
        @Override public void upsertRelationships(Collection<GraphRelationship> relationships) {}
        @Override public void replaceProjection(SourceRef sourceRef, MappingResult currentProjection) {}
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
