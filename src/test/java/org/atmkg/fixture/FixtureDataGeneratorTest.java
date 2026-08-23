package org.atmkg.fixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FixtureDataGeneratorTest {
    @TempDir Path temp;

    @Test
    void sameSeedProducesSameStructuredDataAndCanBeReadAsSourceRecords() throws Exception {
        Path a = temp.resolve("a"), b = temp.resolve("b");
        FixtureDataGenerator generator = new FixtureDataGenerator();
        generator.generate(a, FixtureScale.SMALL, 20260821L);
        generator.generate(b, FixtureScale.SMALL, 20260821L);
        assertEquals(Files.readString(a.resolve("AIRPORT.csv")), Files.readString(b.resolve("AIRPORT.csv")));

        CsvFixtureSourceAdapter adapter = new CsvFixtureSourceAdapter("fixture", a, Map.of("AIRPORT", "airportCode"));
        assertTrue(adapter.readAll("AIRPORT").iterator().hasNext());
    }

    @Test
    void changedSnapshotCoversPhase3ReconciliationAndMissedEventCompensation() throws Exception {
        Path output = temp.resolve("phase3");
        new FixtureDataGenerator().generate(output, FixtureScale.SMALL, 20260821L);

        String changedRunways = Files.readString(output.resolve("changed/RUNWAY.csv"));
        String changedNodes = Files.readString(output.resolve("changed/ROUTE_NODE.csv"));
        String changedReportingPoints = Files.readString(output.resolve("changed/REPORTING_POINT.csv"));
        String changedControlAreas = Files.readString(output.resolve("changed/CONTROL_AREA.csv"));
        String events = Files.readString(output.resolve("changes.csv"));

        assertTrue(changedRunways.contains("Z999-01/19,Z999,"));
        assertTrue(changedNodes.contains("R003:N005,R003,,5,P00305,,RPT,"));
        assertTrue(changedReportingPoints.contains("RPT002,模拟报告点2-补偿更新,"));
        assertFalse(changedControlAreas.contains("CTA003,"));
        assertTrue(events.contains(",ROUTE_NODE,R003:N005,UPSERT,"));
        assertTrue(events.contains(",CONTROL_AREA,CTA003,DELETE,"));
        assertFalse(events.contains(",REPORTING_POINT,RPT002,"));
    }
}
