package org.atmkg.infra.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PollingCheckpointStoreTest {
    @TempDir Path temp;

    @Test
    void savesHumanReadableJsonAndPreservesIndependentScopes() throws Exception {
        Path file = temp.resolve("runtime/state/polling-checkpoints.json");
        PollingCheckpointStore store = new PollingCheckpointStore(file);
        Instant airport = Instant.parse("2026-08-24T10:20:30Z");
        Instant route = Instant.parse("2026-08-24T10:21:45Z");

        store.save("jdbc-main", "airport-base", airport);
        store.save("jdbc-main", "route-row", route);

        PollingCheckpointStore restarted = new PollingCheckpointStore(file);
        assertEquals(airport, restarted.load("jdbc-main", "airport-base").orElseThrow());
        assertEquals(route, restarted.load("jdbc-main", "route-row").orElseThrow());
        String json = Files.readString(file);
        assertTrue(json.contains("\"jdbc-main/airport-base\""));
        assertTrue(json.contains("\"watermark\" : \"2026-08-24T10:20:30Z\""));
        try (Stream<Path> files = Files.list(file.getParent())) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void corruptJsonFailsClearlyInsteadOfFallingBackSilently() throws Exception {
        Path file = temp.resolve("polling-checkpoints.json");
        Files.writeString(file, "{not-json");
        PollingCheckpointStore store = new PollingCheckpointStore(file);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> store.load("jdbc-main", "airport-base"));

        assertTrue(failure.getMessage().contains("polling checkpoint无法读取"));
        assertTrue(failure.getMessage().contains(file.toString()));
        assertEquals("{not-json", Files.readString(file));
    }
}
