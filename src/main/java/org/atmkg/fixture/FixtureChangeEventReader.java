package org.atmkg.fixture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.atmkg.core.model.ChangeEvent;

/** Development-only reader for deterministic fixture change events. */
public final class FixtureChangeEventReader {
    public List<ChangeEvent> read(Path file) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<ChangeEvent> events = new ArrayList<>();
            for (int i = 1; i < lines.size(); i++) {
                if (lines.get(i).isBlank()) continue;
                String[] values = lines.get(i).split(",", 6);
                if (values.length < 5) throw new IllegalStateException("changes.csv 行格式错误：" + lines.get(i));
                events.add(new ChangeEvent(values[0], values[1], values[2], values[3],
                        ChangeEvent.Operation.valueOf(values[4]), Instant.parse("2026-08-21T00:00:00Z").plusSeconds(i)));
            }
            return events;
        } catch (IOException ex) {
            throw new IllegalStateException("fixture change events 读取失败：" + file, ex);
        }
    }
}
