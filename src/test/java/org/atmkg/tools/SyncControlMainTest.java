package org.atmkg.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.atmkg.core.model.ChangeEvent;
import org.atmkg.core.model.SourceScope;
import org.atmkg.core.spi.SyncService;
import org.atmkg.infra.trigger.PollingCheckpointStore;
import org.atmkg.service.sync.SyncRuntimeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyncControlMainTest {
    private static final List<SyncControlMain.SourceEntry> ENTRIES = List.of(
            new SyncControlMain.SourceEntry("excel-main", "route-row", "excel", false, true),
            new SyncControlMain.SourceEntry("jdbc-main", "airport-base", "jdbc", true, true));
    @TempDir Path temp;

    @Test
    void emptyFormalSourcesShowClearMessageAndExitWithoutSyncService() throws Exception {
        String output = run("", null, List.of());

        assertTrue(output.contains("==== 知识图谱同步 ===="));
        assertTrue(output.contains("当前未配置正式数据源"));
    }

    @Test
    void fullRebuildRequiresExactExplicitConfirmation() throws Exception {
        RecordingSync sync = new RecordingSync();

        String cancelled = run("1\n不是确认词\n0\n", sync, ENTRIES);
        assertEquals(0, sync.fullRebuildCount);
        assertTrue(cancelled.contains("将清空当前项目图投影后重新构建"));
        assertTrue(cancelled.contains("已取消"));

        run("1\n确认重建\n0\n", sync, ENTRIES);
        assertEquals(1, sync.fullRebuildCount);
        assertEquals(List.of("excel-main/route-row", "jdbc-main/airport-base"), sync.rebuiltScopes);
    }

    @Test
    void menuSelectionCallsExistingSyncServiceOperations() throws Exception {
        RecordingSync sync = new RecordingSync();

        String output = run("""
                2
                1
                3
                2
                ZBAA
                4
                2
                2026-08-23T00:00:00Z
                5
                0
                """, sync, ENTRIES);

        assertEquals("excel-main/route-row", sync.fullSyncScope);
        assertEquals("jdbc-main/airport-base/ZBAA", sync.resyncRef);
        assertEquals("jdbc-main/airport-base@2026-08-23T00:00:00Z", sync.compensation);
        assertTrue(output.contains("polling 是否启用"));
        assertTrue(output.contains("polling 回看秒数：5"));
        assertTrue(output.contains("jdbc-main / airport-base / jdbc / watermark=是"));
    }

    @Test
    void configViewShowsPersistedCheckpointOrFirstRunInitialWatermark() throws Exception {
        SyncRuntimeConfig config = pollingConfig();
        PollingCheckpointStore checkpoints = checkpointStore();

        String firstRun = run("5\n0\n", new RecordingSync(), ENTRIES, config, checkpoints);

        assertTrue(firstRun.contains("jdbc-main / airport-base"));
        assertTrue(firstRun.contains("checkpoint：尚未生成"));
        assertTrue(firstRun.contains("initialWatermark：2026-08-23T00:00:00Z"));

        checkpoints.save("jdbc-main", "airport-base", Instant.parse("2026-08-24T10:20:30Z"));
        String resumed = run("5\n0\n", new RecordingSync(), ENTRIES, config, checkpoints);

        assertTrue(resumed.contains("checkpoint：2026-08-24T10:20:30Z"));
    }

    private String run(String input, SyncService sync,
                       List<SyncControlMain.SourceEntry> entries) throws Exception {
        return run(input, sync, entries, SyncRuntimeConfig.load(Path.of("config/sync.yaml")), checkpointStore());
    }

    private static String run(String input, SyncService sync, List<SyncControlMain.SourceEntry> entries,
                              SyncRuntimeConfig config, PollingCheckpointStore checkpoints) throws Exception {
        StringWriter text = new StringWriter();
        SyncControlMain.run(new BufferedReader(new StringReader(input)), new PrintWriter(text, true),
                sync, entries, config, checkpoints);
        return text.toString();
    }

    private SyncRuntimeConfig pollingConfig() throws Exception {
        Path file = temp.resolve("sync.yaml");
        Files.writeString(file, """
                sync:
                  initialFullImport: true
                  incremental: true
                  compensation: true
                  manualResync: true
                  eventCarriesAuthoritativeData: false
                  polling:
                    enabled: true
                    intervalSeconds: 10
                    lookbackSeconds: 5
                    scopes:
                      - sourceId: jdbc-main
                        sourceObject: airport-base
                        initialWatermark: '2026-08-23T00:00:00Z'
                """);
        return SyncRuntimeConfig.load(file);
    }

    private PollingCheckpointStore checkpointStore() {
        return new PollingCheckpointStore(temp.resolve("runtime/state/polling-checkpoints.json"));
    }

    private static final class RecordingSync implements SyncService {
        int fullRebuildCount;
        List<String> rebuiltScopes = new ArrayList<>();
        String fullSyncScope;
        String resyncRef;
        String compensation;

        @Override public void handle(ChangeEvent event) {}
        @Override public void fullSync(String sourceId, String objectName) {
            fullSyncScope = sourceId + "/" + objectName;
        }
        @Override public void fullRebuild(Collection<SourceScope> scopes) {
            fullRebuildCount++;
            rebuiltScopes = scopes.stream().map(scope -> scope.getSourceId() + "/" + scope.getObjectName()).toList();
        }
        @Override public void compensateSince(String sourceId, String objectName, Instant since) {
            compensation = sourceId + "/" + objectName + "@" + since;
        }
        @Override public void resync(String sourceId, String objectName, String sourceKey) {
            resyncRef = sourceId + "/" + objectName + "/" + sourceKey;
        }
    }
}
