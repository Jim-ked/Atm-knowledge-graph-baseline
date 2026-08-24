package org.atmkg.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
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
}
