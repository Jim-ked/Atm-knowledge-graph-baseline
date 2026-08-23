package org.atmkg.service.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyncRuntimeConfigTest {
    @TempDir Path temp;

    @Test
    void loadsMinimalDisabledPollingConfiguration() throws Exception {
        Path file = write("""
                sync:
                  initialFullImport: true
                  incremental: true
                  compensation: true
                  manualResync: true
                  eventCarriesAuthoritativeData: false
                  polling:
                    enabled: false
                    intervalSeconds: 30
                    scopes: []
                """);

        SyncRuntimeConfig config = SyncRuntimeConfig.load(file);

        assertTrue(config.isInitialFullImportEnabled());
        assertTrue(config.isIncrementalEnabled());
        assertFalse(config.isPollingEnabled());
        assertEquals(30, config.getPollingInterval().toSeconds());
        assertTrue(config.getPollingScopes().isEmpty());
    }

    @Test
    void requiresExplicitInitialWatermarkForEveryPollingScope() throws Exception {
        Path file = write("""
                sync:
                  initialFullImport: true
                  incremental: true
                  compensation: true
                  manualResync: true
                  eventCarriesAuthoritativeData: false
                  polling:
                    enabled: true
                    intervalSeconds: 30
                    scopes:
                      - sourceId: jdbc-main
                        sourceObject: airport-base
                """);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SyncRuntimeConfig.load(file));

        assertTrue(failure.getMessage().contains("initialWatermark"));
    }

    @Test
    void parsesAnExplicitPollingScopeWithoutInventingCurrentTime() throws Exception {
        Path file = write("""
                sync:
                  initialFullImport: true
                  incremental: true
                  compensation: true
                  manualResync: true
                  eventCarriesAuthoritativeData: false
                  polling:
                    enabled: true
                    intervalSeconds: 15
                    scopes:
                      - sourceId: jdbc-main
                        sourceObject: airport-base
                        initialWatermark: '2026-08-23T00:00:00Z'
                """);

        SyncRuntimeConfig config = SyncRuntimeConfig.load(file);

        assertEquals(Instant.parse("2026-08-23T00:00:00Z"),
                config.getPollingScopes().get(0).initialWatermark());
    }

    private Path write(String yaml) throws Exception {
        Path file = temp.resolve("sync.yaml");
        Files.writeString(file, yaml);
        return file;
    }
}
