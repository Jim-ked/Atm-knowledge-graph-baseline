package org.atmkg.infra.source.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.atmkg.core.model.SourceRecord;
import org.atmkg.infra.source.compose.RecordCompositionSpec.RecordMode;
import org.junit.jupiter.api.Test;

class RecordComposerTest {
    private static final String SOURCE_ID = "source";
    private static final String OBJECT = "rows";

    private final RecordComposer composer = new RecordComposer();

    @Test
    void rowPreservesFieldsTimestampAndEscapedSourceKey() {
        Instant timestamp = Instant.parse("2026-08-29T00:00:00Z");
        RawRecord raw = new RawRecord(Map.of("partA", "R|1", "partB", "A\\2", "name", "alpha"),
                timestamp, "file.xlsx / Sheet1 / row=2");

        SourceRecord record = list(composer.compose(SOURCE_ID, OBJECT, List.of(raw),
                spec(RecordMode.ROW, List.of("partA", "partB"), List.of(), ""))).get(0);

        assertEquals("R\\|1|A\\\\2", record.getSourceKey());
        assertEquals("alpha", record.getFields().get("name"));
        assertEquals("R\\|1|A\\\\2", record.getFields().get("__sourceKey"));
        assertEquals(timestamp, record.getSourceTimestamp());
        assertFalse(record.getFields().containsKey("location"));
    }

    @Test
    void groupFirstSelectsLowestOrderedRowFromEachGroup() {
        List<RawRecord> rows = List.of(
                raw("row=2", "group", "A", "id", "A2", "order", "2"),
                raw("row=3", "group", "B", "id", "B1", "order", "1"),
                raw("row=4", "group", "A", "id", "A1", "order", "1"));

        List<SourceRecord> records = list(composer.compose(SOURCE_ID, OBJECT, rows,
                spec(RecordMode.GROUP_FIRST, List.of("id"), List.of("group"), "order")));

        assertEquals(List.of("A1", "B1"), records.stream().map(SourceRecord::getSourceKey).toList());
    }

    @Test
    void groupFirstRejectsDuplicateLeadingOrderValue() {
        List<RawRecord> rows = List.of(
                raw("row=2", "group", "A", "id", "A1", "order", "1"),
                raw("row=3", "group", "A", "id", "A2", "order", "1"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> list(composer.compose(SOURCE_ID, OBJECT, rows,
                        spec(RecordMode.GROUP_FIRST, List.of("id"), List.of("group"), "order"))));

        assertTrue(error.getMessage().contains("GROUP_FIRST"));
        assertTrue(error.getMessage().contains("order=1"));
        assertTrue(error.getMessage().contains("row=2"));
    }

    @Test
    void adjacentNextProducesNMinusOneLogicalRecords() {
        List<RawRecord> rows = List.of(
                raw("row=2", "group", "A", "id", "A1", "order", "1"),
                raw("row=3", "group", "A", "id", "A2", "order", "2"),
                raw("row=4", "group", "A", "id", "A3", "order", "3"));

        List<SourceRecord> records = list(composer.compose(SOURCE_ID, OBJECT, rows,
                spec(RecordMode.ADJACENT_NEXT, List.of("id"), List.of("group"), "order")));

        assertEquals(2, records.size());
        assertEquals(List.of("A1", "A2"), records.stream().map(SourceRecord::getSourceKey).toList());
    }

    @Test
    void adjacentNextKeepsTopLevelCurrentFieldsAndNestedRows() {
        List<RawRecord> rows = List.of(
                raw("row=2", "id", "A1", "order", "1", "point", "P1"),
                raw("row=3", "id", "A2", "order", "2", "point", "P2"));

        SourceRecord record = list(composer.compose(SOURCE_ID, OBJECT, rows,
                spec(RecordMode.ADJACENT_NEXT, List.of("id"), List.of(), "order"))).get(0);

        assertEquals("P1", record.getFields().get("point"));
        assertEquals("P1", nested(record, "current").get("point"));
        assertEquals("P2", nested(record, "next").get("point"));
    }

    @Test
    void adjacentNextAddsIndependentCurrentAndNextSourceKeys() {
        List<RawRecord> rows = List.of(
                raw("row=2", "id", "A1", "order", "1"),
                raw("row=3", "id", "A2", "order", "2"));

        SourceRecord record = list(composer.compose(SOURCE_ID, OBJECT, rows,
                spec(RecordMode.ADJACENT_NEXT, List.of("id"), List.of(), "order"))).get(0);

        assertEquals("A1", record.getFields().get("__sourceKey"));
        assertEquals("A1", nested(record, "current").get("__sourceKey"));
        assertEquals("A2", nested(record, "next").get("__sourceKey"));
    }

    @Test
    void adjacentNextRejectsDuplicateOrderValue() {
        List<RawRecord> rows = List.of(
                raw("row=2", "id", "A1", "order", "1"),
                raw("row=3", "id", "A2", "order", "1"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> list(composer.compose(SOURCE_ID, OBJECT, rows,
                        spec(RecordMode.ADJACENT_NEXT, List.of("id"), List.of(), "order"))));

        assertTrue(error.getMessage().contains("ADJACENT_NEXT"));
        assertTrue(error.getMessage().contains("order=1"));
    }

    @Test
    void adjacentNextTreatsEmptyGroupByAsOneGroup() {
        List<RawRecord> rows = List.of(
                raw("row=2", "id", "A1", "order", "1"),
                raw("row=3", "id", "B1", "order", "2"));

        List<SourceRecord> records = list(composer.compose(SOURCE_ID, OBJECT, rows,
                spec(RecordMode.ADJACENT_NEXT, List.of("id"), List.of(), "order")));

        assertEquals(1, records.size());
        assertEquals("B1", nested(records.get(0), "next").get("id"));
    }

    @Test
    void numericOrderValuesSortNumerically() {
        List<RawRecord> rows = List.of(
                raw("row=2", "id", "A10", "order", "10"),
                raw("row=3", "id", "A2", "order", "2"));

        SourceRecord record = list(composer.compose(SOURCE_ID, OBJECT, rows,
                spec(RecordMode.ADJACENT_NEXT, List.of("id"), List.of(), "order"))).get(0);

        assertEquals("A2", record.getSourceKey());
        assertEquals("A10", nested(record, "next").get("id"));
    }

    @Test
    void nonNumericOrderValuesSortAsStrings() {
        List<RawRecord> rows = List.of(
                raw("row=2", "id", "B", "order", "bravo"),
                raw("row=3", "id", "A", "order", "alpha"));

        SourceRecord record = list(composer.compose(SOURCE_ID, OBJECT, rows,
                spec(RecordMode.ADJACENT_NEXT, List.of("id"), List.of(), "order"))).get(0);

        assertEquals("A", record.getSourceKey());
        assertEquals("B", nested(record, "next").get("id"));
    }

    @Test
    void rowRequiresKeyFields() {
        assertThrows(IllegalArgumentException.class,
                () -> spec(RecordMode.ROW, List.of(), List.of(), ""));
    }

    @Test
    void groupFirstRequiresGroupByAndOrderBy() {
        assertThrows(IllegalArgumentException.class,
                () -> spec(RecordMode.GROUP_FIRST, List.of("id"), List.of(), "order"));
        assertThrows(IllegalArgumentException.class,
                () -> spec(RecordMode.GROUP_FIRST, List.of("id"), List.of("group"), ""));
    }

    @Test
    void adjacentNextAllowsEmptyGroupByButRequiresOrderBy() {
        RecordCompositionSpec valid = spec(
                RecordMode.ADJACENT_NEXT, List.of("id"), List.of(), "order");
        assertTrue(valid.groupBy().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> spec(RecordMode.ADJACENT_NEXT, List.of("id"), List.of(), ""));
    }

    private static RecordCompositionSpec spec(RecordMode mode, List<String> keys,
                                              List<String> groupBy, String orderBy) {
        return new RecordCompositionSpec(mode, keys, groupBy, orderBy);
    }

    private static RawRecord raw(String location, Object... values) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            fields.put(String.valueOf(values[index]), values[index + 1]);
        }
        return new RawRecord(fields, null, location);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(SourceRecord record, String name) {
        return (Map<String, Object>) record.getFields().get(name);
    }

    private static List<SourceRecord> list(Iterable<SourceRecord> iterable) {
        List<SourceRecord> out = new ArrayList<>();
        iterable.forEach(out::add);
        return out;
    }
}
