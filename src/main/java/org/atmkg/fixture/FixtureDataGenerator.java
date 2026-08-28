package org.atmkg.fixture;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Deterministic structured test-data generator. It is intentionally domain-specific test support,
 * not a second runtime semantic core. Data shape follows the current project ontology and is meant
 * to pass through SourceAdapter + MappingEngine rather than being loaded as RDF.
 *
 * Every generated scale contains a base snapshot and a changed snapshot so that SyncService can
 * re-read actual changed authoritative records instead of testing against event metadata only.
 */
public final class FixtureDataGenerator {
    public void generate(Path outputDir, FixtureScale scale, long seed) {
        try {
            Files.createDirectories(outputDir);
            SplittableRandom random = new SplittableRandom(seed);
            List<String[]> airports = airports(scale, random);
            List<String[]> runways = runways(airports, random);
            List<String[]> runwayDirections = runwayDirections(runways);
            List<String[]> navigationAids = navigationAids(random);
            List<String[]> reportingPoints = reportingPoints(random);
            List<String[]> routes = routes(scale);
            List<String[]> scheduledRoutes = scheduledRoutes(scale);
            List<String[]> nodes = routeNodes(routes, scheduledRoutes, scale, random);
            List<String[]> segments = routeSegments(routes, scheduledRoutes, scale);
            List<String[]> airspaces = airspaces(scale, random);
            List<String[]> geometries = geometries(airspaces);
            List<String[]> boundaryPoints = boundaryPoints(geometries, random);
            List<String[]> controlAreas = controlAreas(scale);
            List<String[]> flightInformationRegions = flightInformationRegions(scale);

            writeSnapshot(outputDir, airports, runways, runwayDirections, navigationAids, reportingPoints,
                    routes, scheduledRoutes, nodes, segments, airspaces, geometries, boundaryPoints,
                    controlAreas, flightInformationRegions);

            Path changedDir = outputDir.resolve("changed");
            Files.createDirectories(changedDir);
            List<String[]> changedAirports = deepCopy(airports);
            List<String[]> changedRunways = deepCopy(runways);
            List<String[]> changedNodes = deepCopy(nodes);
            List<String[]> changedReportingPoints = deepCopy(reportingPoints);
            List<String[]> changedControlAreas = deepCopy(controlAreas);
            applyChanges(changedAirports, changedRunways, changedNodes, changedReportingPoints, changedControlAreas);
            writeSnapshot(changedDir, changedAirports, changedRunways, runwayDirections, navigationAids, changedReportingPoints,
                    routes, scheduledRoutes, changedNodes, segments, airspaces, geometries, boundaryPoints,
                    changedControlAreas, flightInformationRegions);
            writeChangeScenario(outputDir.resolve("changes.csv"), airports, runways, controlAreas);

            Files.writeString(outputDir.resolve("fixture-meta.txt"),
                    "seed=" + seed + System.lineSeparator()
                            + "scale=" + scale.name().toLowerCase() + System.lineSeparator()
                            + "snapshots=base,changed" + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("测试数据生成失败：" + outputDir, ex);
        }
    }

    private void writeSnapshot(Path dir, List<String[]> airports, List<String[]> runways,
                               List<String[]> runwayDirections, List<String[]> navigationAids,
                               List<String[]> reportingPoints, List<String[]> routes,
                               List<String[]> scheduledRoutes, List<String[]> nodes,
                               List<String[]> segments, List<String[]> airspaces,
                               List<String[]> geometries, List<String[]> boundaryPoints,
                               List<String[]> controlAreas, List<String[]> flightInformationRegions) throws IOException {
        writeCsv(dir.resolve("AIRPORT.csv"),
                new String[]{"airportCode","icaoCode","nameZh","countryRegion","latitude","longitude","elevation","airportType","airportGrade"}, airports);
        writeCsv(dir.resolve("RUNWAY.csv"),
                new String[]{"runwayCode","airportCode","length","width","entryLatitude","entryLongitude","exitLatitude","exitLongitude"}, runways);
        writeCsv(dir.resolve("RUNWAY_DIRECTION.csv"),
                new String[]{"directionKey","runwayCode","directionIdentifier"}, runwayDirections);
        writeCsv(dir.resolve("NAVIGATION_AID.csv"),
                new String[]{"navigationAidCode","nameZh","nameEn","callSign","latitude","longitude","navigationAidType","frequencyKHz"}, navigationAids);
        writeCsv(dir.resolve("REPORTING_POINT.csv"),
                new String[]{"reportingPointCode","reportingPointName","reportingPointType","reportingPointTypeName","latitude","longitude"}, reportingPoints);
        writeCsv(dir.resolve("ROUTE.csv"),
                new String[]{"routeCode","routeName","routeType","routeLowerLimit","routeUpperLimit"}, routes);
        writeCsv(dir.resolve("SCHEDULED_FLIGHT_ROUTE.csv"),
                new String[]{"scheduledRouteCode","routeName","routeType"}, scheduledRoutes);
        writeCsv(dir.resolve("ROUTE_NODE.csv"),
                new String[]{"nodeKey","routeCode","scheduledRouteCode","sequenceNumber","nodeCode","nodeName","nodeTypeCode","latitude","longitude","navigationAidCode","reportingPointCode"}, nodes);
        writeCsv(dir.resolve("ROUTE_SEGMENT.csv"),
                new String[]{"segmentKey","routeCode","scheduledRouteCode","fromNodeKey","toNodeKey","segmentDistance","magneticCourse","reverseMagneticCourse"}, segments);
        writeCsv(dir.resolve("AIRSPACE.csv"),
                new String[]{"airspaceCode","airspaceName","airspaceTypeCode","airspaceTypeName","airspaceLowerLimit","airspaceUpperLimit"}, airspaces);
        writeCsv(dir.resolve("AIRSPACE_GEOMETRY.csv"), new String[]{"geometryKey","airspaceCode"}, geometries);
        writeCsv(dir.resolve("BOUNDARY_POINT.csv"), new String[]{"boundaryPointKey","geometryKey","sequenceNumber","latitude","longitude","isEndRaw"}, boundaryPoints);
        writeCsv(dir.resolve("CONTROL_AREA.csv"), new String[]{"controlAreaCode","controlAreaName","callSign","lowerLimit","upperLimit"}, controlAreas);
        writeCsv(dir.resolve("FLIGHT_INFORMATION_REGION.csv"), new String[]{"flightInformationRegionCode","nameZh","upperLimit","lowerLimit"}, flightInformationRegions);
    }

    private void applyChanges(List<String[]> airports, List<String[]> runways, List<String[]> nodes,
                              List<String[]> reportingPoints, List<String[]> controlAreas) {
        if (!airports.isEmpty()) {
            String[] first = airports.get(0);
            first[2] = first[2] + "-已更新";
            first[6] = String.valueOf(Integer.parseInt(first[6]) + 50);
        }
        airports.add(new String[]{"Z999", "Z999", "新增模拟机场", "CN", "31.200000", "121.500000", "15", "TEST", "T1"});

        if (!runways.isEmpty() && airports.size() >= 2) {
            String[] firstRunway = runways.get(0);
            firstRunway[1] = airports.get(1)[0]; // explicit reference change, used to verify stale relationship removal
            firstRunway[2] = String.valueOf(Integer.parseInt(firstRunway[2]) + 100);
        }
        runways.add(new String[]{"Z999-01/19", "Z999", "2800", "45",
                "31.190000", "121.490000", "31.210000", "121.510000"});

        for (String[] node : nodes) {
            if ("R003:N005".equals(node[0])) {
                node[5] = ""; // optional property removal
            }
        }
        for (String[] reportingPoint : reportingPoints) {
            if ("RPT002".equals(reportingPoint[0])) reportingPoint[1] += "-补偿更新";
        }
        if (!controlAreas.isEmpty()) controlAreas.remove(controlAreas.size() - 1);
    }

    private List<String[]> deepCopy(List<String[]> rows) {
        List<String[]> out = new ArrayList<>(rows.size());
        for (String[] row : rows) out.add(row.clone());
        return out;
    }

    private List<String[]> airports(FixtureScale scale, SplittableRandom random) {
        List<String[]> rows = new ArrayList<>();
        for (int i = 1; i <= scale.airports; i++) {
            String code = String.format("Z%03d", i);
            double lat = 18 + random.nextDouble() * 30;
            double lon = 75 + random.nextDouble() * 55;
            rows.add(new String[]{code, code, "模拟机场" + i, "CN", f(lat), f(lon), String.valueOf(20 + random.nextInt(2800)), "TEST", "T" + ((i % 4) + 1)});
        }
        return rows;
    }

    private List<String[]> runways(List<String[]> airports, SplittableRandom random) {
        List<String[]> rows = new ArrayList<>();
        for (String[] airport : airports) {
            for (int r = 0; r < 2; r++) {
                String code = airport[0] + "-" + String.format("%02d/%02d", 1 + r * 9, 19 + r * 9);
                double lat = Double.parseDouble(airport[4]);
                double lon = Double.parseDouble(airport[5]);
                rows.add(new String[]{code, airport[0], String.valueOf(2200 + random.nextInt(1800)), String.valueOf(40 + random.nextInt(30)),
                        f(lat - 0.01), f(lon - 0.01), f(lat + 0.01), f(lon + 0.01)});
            }
        }
        return rows;
    }

    private List<String[]> runwayDirections(List<String[]> runways) {
        List<String[]> rows = new ArrayList<>();
        for (String[] runway : runways) {
            String[] parts = runway[0].split("-");
            String[] directions = parts.length == 2 ? parts[1].split("/") : new String[]{"01", "19"};
            for (String direction : directions) {
                rows.add(new String[]{runway[0] + ":" + direction, runway[0], direction});
            }
        }
        return rows;
    }

    private List<String[]> navigationAids(SplittableRandom random) {
        List<String[]> rows = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            rows.add(new String[]{"NAV" + String.format("%03d", i), "模拟导航台" + i, "NAV-A" + i,
                    "N" + String.format("%03d", i), f(22 + random.nextDouble() * 18),
                    f(90 + random.nextDouble() * 25), i % 2 == 0 ? "VOR/DME" : "DME", String.valueOf(11000 + i * 25)});
        }
        return rows;
    }

    private List<String[]> reportingPoints(SplittableRandom random) {
        List<String[]> rows = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            rows.add(new String[]{"RPT" + String.format("%03d", i), "模拟报告点" + i, i % 2 == 0 ? "TRANS" : "RPT",
                    i % 2 == 0 ? "管制移交点" : "非强制报告点", f(24 + random.nextDouble() * 15), f(92 + random.nextDouble() * 20)});
        }
        return rows;
    }

    private List<String[]> routes(FixtureScale scale) {
        List<String[]> rows = new ArrayList<>();
        for (int i = 1; i <= scale.routes; i++) {
            String code = "R" + String.format("%03d", i);
            rows.add(new String[]{code, code, "TEST", "3000", "12000"});
        }
        return rows;
    }

    private List<String[]> scheduledRoutes(FixtureScale scale) {
        List<String[]> rows = new ArrayList<>();
        int count = Math.max(2, Math.min(3, scale.routes));
        for (int i = 1; i <= count; i++) {
            String code = "SFR" + String.format("%03d", i);
            rows.add(new String[]{code, "模拟班机航线" + i, "SCHEDULED"});
        }
        return rows;
    }

    private List<String[]> routeNodes(List<String[]> routes, List<String[]> scheduledRoutes, FixtureScale scale, SplittableRandom random) {
        List<String[]> rows = new ArrayList<>();
        for (String[] route : routes) {
            for (int n = 1; n <= scale.nodesPerRoute; n++) {
                String nodeKey = route[0] + ":N" + String.format("%03d", n);
                String navigation = n == 1 ? "NAV001" : "";
                String reporting = n == 2 ? "RPT001" : "";
                rows.add(new String[]{nodeKey, route[0], "", String.valueOf(n), "P" + route[0].substring(1) + String.format("%02d", n),
                        "模拟节点" + n, "RPT", f(20 + random.nextDouble() * 25), f(80 + random.nextDouble() * 45), navigation, reporting});
            }
        }
        for (String[] route : scheduledRoutes) {
            int count = Math.max(3, Math.min(scale.nodesPerRoute, 4));
            for (int n = 1; n <= count; n++) {
                String nodeKey = route[0] + ":N" + String.format("%03d", n);
                rows.add(new String[]{nodeKey, "", route[0], String.valueOf(n), "S" + route[0].substring(3) + String.format("%02d", n),
                        "模拟班机节点" + n, "NAV", f(25 + random.nextDouble() * 20), f(85 + random.nextDouble() * 35), "NAV002", ""});
            }
        }
        return rows;
    }

    private List<String[]> routeSegments(List<String[]> routes, List<String[]> scheduledRoutes, FixtureScale scale) {
        List<String[]> rows = new ArrayList<>();
        for (String[] route : routes) {
            for (int n = 1; n < scale.nodesPerRoute; n++) {
                String from = route[0] + ":N" + String.format("%03d", n);
                String to = route[0] + ":N" + String.format("%03d", n + 1);
                String key = route[0] + ":S" + String.format("%03d", n);
                int course = (n * 37) % 360;
                rows.add(new String[]{key, route[0], "", from, to, String.valueOf(30 + n), String.valueOf(course), String.valueOf((course + 180) % 360)});
            }
        }
        for (String[] route : scheduledRoutes) {
            int count = Math.max(3, Math.min(scale.nodesPerRoute, 4));
            for (int n = 1; n < count; n++) {
                String from = route[0] + ":N" + String.format("%03d", n);
                String to = route[0] + ":N" + String.format("%03d", n + 1);
                String key = route[0] + ":S" + String.format("%03d", n);
                rows.add(new String[]{key, "", route[0], from, to, String.valueOf(40 + n), String.valueOf((n * 43) % 360), ""});
            }
        }
        return rows;
    }

    private List<String[]> airspaces(FixtureScale scale, SplittableRandom random) {
        List<String[]> rows = new ArrayList<>();
        for (int i = 1; i <= scale.airspaces; i++) {
            rows.add(new String[]{"AS" + String.format("%04d", i), "模拟空域" + i, String.valueOf(100 + (i % 5)), "TEST", "0", String.valueOf(6000 + random.nextInt(9000))});
        }
        return rows;
    }

    private List<String[]> geometries(List<String[]> airspaces) {
        List<String[]> rows = new ArrayList<>();
        for (String[] airspace : airspaces) rows.add(new String[]{airspace[0] + ":G001", airspace[0]});
        return rows;
    }

    private List<String[]> boundaryPoints(List<String[]> geometries, SplittableRandom random) {
        List<String[]> rows = new ArrayList<>();
        for (String[] geometry : geometries) {
            for (int i = 1; i <= 3; i++) {
                rows.add(new String[]{geometry[0] + ":P" + i, geometry[0], String.valueOf(i),
                        f(20 + random.nextDouble() * 20), f(80 + random.nextDouble() * 30), i == 3 ? "true" : "false"});
            }
        }
        return rows;
    }

    private List<String[]> controlAreas(FixtureScale scale) {
        List<String[]> rows = new ArrayList<>();
        for (int i = 1; i <= Math.max(2, Math.min(3, scale.airspaces)); i++) {
            String code = "CTA" + String.format("%03d", i);
            rows.add(new String[]{code, "模拟管制区" + i, "CALL" + i, "0", "12000"});
        }
        return rows;
    }

    private List<String[]> flightInformationRegions(FixtureScale scale) {
        List<String[]> rows = new ArrayList<>();
        for (int i = 1; i <= Math.max(2, Math.min(3, scale.airspaces)); i++) {
            String code = "FIR" + String.format("%03d", i);
            rows.add(new String[]{code, "模拟飞行情报区" + i, "FL999", "SFC"});
        }
        return rows;
    }

    private void writeChangeScenario(Path file, List<String[]> airports, List<String[]> runways,
                                     List<String[]> controlAreas) throws IOException {
        List<String[]> rows = new ArrayList<>();
        if (!airports.isEmpty()) rows.add(new String[]{"EVT-001","fixture","AIRPORT",airports.get(0)[0],"UPSERT","修改机场名称/标高"});
        if (!runways.isEmpty()) rows.add(new String[]{"EVT-002","fixture","RUNWAY",runways.get(0)[0],"UPSERT","修改跑道属性并改变所属机场引用"});
        rows.add(new String[]{"EVT-003","fixture","AIRPORT","Z999","UPSERT","新增机场"});
        rows.add(new String[]{"EVT-004","fixture","RUNWAY","Z999-01/19","UPSERT","新增跑道及所属机场关系"});
        rows.add(new String[]{"EVT-005","fixture","ROUTE_NODE","R003:N005","UPSERT","移除可选属性"});
        if (!controlAreas.isEmpty()) rows.add(new String[]{"EVT-006","fixture","CONTROL_AREA",controlAreas.get(controlAreas.size()-1)[0],"DELETE","删除无共享关系的管制区"});
        writeCsv(file, new String[]{"eventId","sourceId","objectName","sourceKey","operation","scenario"}, rows);
    }

    private void writeCsv(Path file, String[] headers, List<String[]> rows) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(csvLine(headers)); writer.newLine();
            for (String[] row : rows) { writer.write(csvLine(row)); writer.newLine(); }
        }
    }

    private String csvLine(String[] values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) out.append(',');
            String value = values[i] == null ? "" : values[i];
            if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0) {
                out.append('"').append(value.replace("\"", "\"\"")).append('"');
            } else out.append(value);
        }
        return out.toString();
    }

    private String f(double value) { return String.format(java.util.Locale.ROOT, "%.6f", value); }
}
