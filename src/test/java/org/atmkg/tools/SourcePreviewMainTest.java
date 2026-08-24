package org.atmkg.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.atmkg.core.model.SourceRecord;
import org.atmkg.core.spi.SourceAdapter;
import org.atmkg.infra.source.config.ConfiguredSource;
import org.atmkg.infra.source.config.SourceConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourcePreviewMainTest {
    @TempDir Path temp;

    @Test
    void limitConsumesOnlyRequestedRecordsAndClosesIterator() {
        CloseTrackingAdapter adapter = new CloseTrackingAdapter(100);

        List<SourceRecord> records = SourcePreviewMain.readLimited(adapter.readAll("rows"), 5);

        assertEquals(5, records.size());
        assertEquals(5, adapter.consumed);
        assertTrue(adapter.closed);
    }

    @Test
    void unifiedPreviewAcceptsExcelAndJdbcConfiguredSources() throws Exception {
        CloseTrackingAdapter excel = new CloseTrackingAdapter(2);
        CloseTrackingAdapter jdbc = new CloseTrackingAdapter(2);

        List<SourceRecord> excelRecords = SourcePreviewMain.preview(
                source("excel-main", "excel", "files: '*.xlsx'"), "rows", excel, 1);
        List<SourceRecord> jdbcRecords = SourcePreviewMain.preview(
                source("jdbc-main", "jdbc", "table: ATM.ROUTE"), "rows", jdbc, 1);

        assertEquals(1, excelRecords.size());
        assertEquals(1, jdbcRecords.size());
        assertTrue(excel.closed);
        assertTrue(jdbc.closed);
    }

    private ConfiguredSource source(String sourceId, String adapter, String locator) throws Exception {
        Path config = temp.resolve(sourceId + ".yaml");
        Files.writeString(config, """
                sources:
                  - sourceId: %s
                    adapter: %s
                    objects:
                      rows:
                        %s
                        keyFields: [ID]
                """.formatted(sourceId, adapter, locator.replace("\n", "\n        ")));
        return SourceConfig.load(config).requireSource(sourceId);
    }

    private static final class CloseTrackingAdapter implements SourceAdapter {
        private final int count;
        private int consumed;
        private boolean closed;

        private CloseTrackingAdapter(int count) { this.count = count; }

        @Override
        public Iterable<SourceRecord> readAll(String objectName) {
            return () -> new CloseTrackingIterator(objectName);
        }

        @Override public Optional<SourceRecord> readByKey(String objectName, String sourceKey) {
            return Optional.empty();
        }

        @Override public Iterable<SourceRecord> scanChangedSince(String objectName, Instant since) {
            return List.of();
        }

        private final class CloseTrackingIterator implements Iterator<SourceRecord>, AutoCloseable {
            private final String objectName;
            private int index;

            private CloseTrackingIterator(String objectName) { this.objectName = objectName; }

            @Override public boolean hasNext() { return index < count; }

            @Override
            public SourceRecord next() {
                index++;
                consumed++;
                return new SourceRecord("source", objectName, String.valueOf(index),
                        Map.of("ID", index), null);
            }

            @Override public void close() { closed = true; }
        }
    }
}
