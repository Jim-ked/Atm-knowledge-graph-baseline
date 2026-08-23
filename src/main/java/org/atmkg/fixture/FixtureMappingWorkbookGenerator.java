package org.atmkg.fixture;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Generates the development-only fixture mapping workbook.  The formal mapping workbook is
 * deliberately not touched; this class only describes the small structured CSV fixture.
 */
public final class FixtureMappingWorkbookGenerator {
    private static final String NS = "urn:atm-knowledge-graph:";

    private FixtureMappingWorkbookGenerator() {}

    public static void main(String[] args) {
        Path output = args.length == 0 ? Path.of("fixtures/mapping/fixture_mapping.xlsx") : Path.of(args[0]);
        generate(output);
        System.out.println("fixture mapping workbook generated: " + output.toAbsolutePath());
    }

    public static void generate(Path output) {
        try {
            Files.createDirectories(output.toAbsolutePath().normalize().getParent());
            try (Workbook workbook = new XSSFWorkbook()) {
                writeEntities(workbook.createSheet("实体映射"));
                writeProperties(workbook.createSheet("属性映射"));
                writeRelationships(workbook.createSheet("关系映射"));
                try (OutputStream stream = Files.newOutputStream(output)) {
                    workbook.write(stream);
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("fixture mapping workbook 写入失败：" + output, ex);
        }
    }

    private static void writeEntities(Sheet sheet) {
        row(sheet, 0, "实体类", "权威数据源 sourceId", "源记录类型/表/Sheet", "业务主键", "UID规则");
        String[][] rows = {
                {"Airport", "fixture", "AIRPORT", "airportCode"},
                {"Runway", "fixture", "RUNWAY", "runwayCode"},
                {"RunwayDirection", "fixture", "RUNWAY_DIRECTION", "directionKey"},
                {"NavigationAid", "fixture", "NAVIGATION_AID", "navigationAidCode"},
                {"ReportingPoint", "fixture", "REPORTING_POINT", "reportingPointCode"},
                {"Route", "fixture", "ROUTE", "routeCode"},
                {"ScheduledFlightRoute", "fixture", "SCHEDULED_FLIGHT_ROUTE", "scheduledRouteCode"},
                {"RouteNode", "fixture", "ROUTE_NODE", "nodeKey"},
                {"RouteSegment", "fixture", "ROUTE_SEGMENT", "segmentKey"},
                {"Airspace", "fixture", "AIRSPACE", "airspaceCode"},
                {"AirspaceGeometry", "fixture", "AIRSPACE_GEOMETRY", "geometryKey"},
                {"BoundaryPoint", "fixture", "BOUNDARY_POINT", "boundaryPointKey"},
                {"ControlArea", "fixture", "CONTROL_AREA", "controlAreaCode"},
                {"FlightInformationRegion", "fixture", "FLIGHT_INFORMATION_REGION", "flightInformationRegionCode"}
        };
        for (int i = 0; i < rows.length; i++) row(sheet, i + 1, rows[i][0], rows[i][1], rows[i][2], rows[i][3], "class-local-business-key");
    }

    private static void writeProperties(Sheet sheet) {
        row(sheet, 0, "实体类", "本体属性", "中文含义", "sourceId", "源对象", "源字段/路径", "必要转换", "是否必填");
        List<String[]> rows = new ArrayList<>();
        add(rows, "Airport", "airportCode", "机场代码", "AIRPORT", "airportCode", "airportCode", "", "true");
        add(rows, "Airport", "icaoCode", "ICAO 四字码", "AIRPORT", "icaoCode", "icaoCode", "", "false");
        add(rows, "Airport", "nameZh", "中文名称", "AIRPORT", "nameZh", "nameZh", "", "false");
        add(rows, "Airport", "countryRegion", "国家地区", "AIRPORT", "countryRegion", "countryRegion", "", "false");
        add(rows, "Airport", "latitude", "纬度", "AIRPORT", "latitude", "latitude", "decimal", "false");
        add(rows, "Airport", "longitude", "经度", "AIRPORT", "longitude", "longitude", "decimal", "false");
        add(rows, "Airport", "elevation", "标高", "AIRPORT", "elevation", "elevation", "decimal", "false");
        add(rows, "Airport", "airportType", "机场类型", "AIRPORT", "airportType", "airportType", "", "false");
        add(rows, "Airport", "airportGrade", "机场等级", "AIRPORT", "airportGrade", "airportGrade", "", "false");
        add(rows, "Runway", "runwayCode", "跑道代码", "RUNWAY", "runwayCode", "runwayCode", "", "true");
        add(rows, "Runway", "length", "长度", "RUNWAY", "length", "length", "decimal", "false");
        add(rows, "Runway", "width", "宽度", "RUNWAY", "width", "width", "decimal", "false");
        add(rows, "Runway", "entryLatitude", "入口纬度", "RUNWAY", "entryLatitude", "entryLatitude", "", "false");
        add(rows, "Runway", "entryLongitude", "入口经度", "RUNWAY", "entryLongitude", "entryLongitude", "", "false");
        add(rows, "Runway", "exitLatitude", "出口纬度", "RUNWAY", "exitLatitude", "exitLatitude", "", "false");
        add(rows, "Runway", "exitLongitude", "出口经度", "RUNWAY", "exitLongitude", "exitLongitude", "", "false");
        add(rows, "RunwayDirection", "directionIdentifier", "方向标识", "RUNWAY_DIRECTION", "directionIdentifier", "directionIdentifier", "", "false");
        add(rows, "NavigationAid", "navigationAidCode", "导航台代码", "NAVIGATION_AID", "navigationAidCode", "navigationAidCode", "", "true");
        add(rows, "NavigationAid", "nameZh", "中文名称", "NAVIGATION_AID", "nameZh", "nameZh", "", "false");
        add(rows, "NavigationAid", "nameEn", "英文名称", "NAVIGATION_AID", "nameEn", "nameEn", "", "false");
        add(rows, "NavigationAid", "callSign", "呼号", "NAVIGATION_AID", "callSign", "callSign", "", "false");
        add(rows, "NavigationAid", "latitude", "纬度", "NAVIGATION_AID", "latitude", "latitude", "decimal", "false");
        add(rows, "NavigationAid", "longitude", "经度", "NAVIGATION_AID", "longitude", "longitude", "decimal", "false");
        add(rows, "NavigationAid", "navigationAidType", "导航台类型", "NAVIGATION_AID", "navigationAidType", "navigationAidType", "", "false");
        add(rows, "NavigationAid", "frequencyKHz", "频率", "NAVIGATION_AID", "frequencyKHz", "frequencyKHz", "decimal", "false");
        add(rows, "ReportingPoint", "reportingPointCode", "报告点代码", "REPORTING_POINT", "reportingPointCode", "reportingPointCode", "", "true");
        add(rows, "ReportingPoint", "reportingPointName", "报告点名称", "REPORTING_POINT", "reportingPointName", "reportingPointName", "", "false");
        add(rows, "ReportingPoint", "reportingPointType", "报告点类型", "REPORTING_POINT", "reportingPointType", "reportingPointType", "", "false");
        add(rows, "ReportingPoint", "reportingPointTypeName", "报告点类型名称", "REPORTING_POINT", "reportingPointTypeName", "reportingPointTypeName", "", "false");
        add(rows, "ReportingPoint", "latitude", "纬度", "REPORTING_POINT", "latitude", "latitude", "decimal", "false");
        add(rows, "ReportingPoint", "longitude", "经度", "REPORTING_POINT", "longitude", "longitude", "decimal", "false");
        add(rows, "Route", "routeCode", "路线代码", "ROUTE", "routeCode", "routeCode", "", "true");
        add(rows, "Route", "routeName", "路线名称", "ROUTE", "routeName", "routeName", "", "false");
        add(rows, "Route", "routeType", "路线类型", "ROUTE", "routeType", "routeType", "", "false");
        add(rows, "Route", "routeLowerLimit", "航路下限", "ROUTE", "routeLowerLimit", "routeLowerLimit", "", "false");
        add(rows, "Route", "routeUpperLimit", "航路上限", "ROUTE", "routeUpperLimit", "routeUpperLimit", "", "false");
        add(rows, "ScheduledFlightRoute", "routeCode", "路线代码", "SCHEDULED_FLIGHT_ROUTE", "routeCode", "scheduledRouteCode", "", "true");
        add(rows, "ScheduledFlightRoute", "routeName", "路线名称", "SCHEDULED_FLIGHT_ROUTE", "routeName", "routeName", "", "false");
        add(rows, "ScheduledFlightRoute", "routeType", "路线类型", "SCHEDULED_FLIGHT_ROUTE", "routeType", "routeType", "", "false");
        add(rows, "RouteNode", "nodeCode", "节点代码", "ROUTE_NODE", "nodeCode", "nodeCode", "", "false");
        add(rows, "RouteNode", "nodeName", "节点名称", "ROUTE_NODE", "nodeName", "nodeName", "", "false");
        add(rows, "RouteNode", "nodeTypeCode", "节点类型", "ROUTE_NODE", "nodeTypeCode", "nodeTypeCode", "", "false");
        add(rows, "RouteNode", "sequenceNumber", "序号", "ROUTE_NODE", "sequenceNumber", "sequenceNumber", "integer", "true");
        add(rows, "RouteNode", "latitude", "纬度", "ROUTE_NODE", "latitude", "latitude", "decimal", "false");
        add(rows, "RouteNode", "longitude", "经度", "ROUTE_NODE", "longitude", "longitude", "decimal", "false");
        add(rows, "RouteSegment", "segmentDistance", "航段距离", "ROUTE_SEGMENT", "segmentDistance", "segmentDistance", "decimal", "false");
        add(rows, "RouteSegment", "magneticCourse", "磁航向", "ROUTE_SEGMENT", "magneticCourse", "magneticCourse", "decimal", "false");
        add(rows, "RouteSegment", "reverseMagneticCourse", "反向磁航向", "ROUTE_SEGMENT", "reverseMagneticCourse", "reverseMagneticCourse", "decimal", "false");
        add(rows, "Airspace", "airspaceCode", "空域代码", "AIRSPACE", "airspaceCode", "airspaceCode", "", "true");
        add(rows, "Airspace", "airspaceName", "空域名称", "AIRSPACE", "airspaceName", "airspaceName", "", "false");
        add(rows, "Airspace", "airspaceTypeCode", "空域类型", "AIRSPACE", "airspaceTypeCode", "airspaceTypeCode", "", "false");
        add(rows, "Airspace", "airspaceTypeName", "空域类型名称", "AIRSPACE", "airspaceTypeName", "airspaceTypeName", "", "false");
        add(rows, "Airspace", "airspaceLowerLimit", "空域下限", "AIRSPACE", "airspaceLowerLimit", "airspaceLowerLimit", "", "false");
        add(rows, "Airspace", "airspaceUpperLimit", "空域上限", "AIRSPACE", "airspaceUpperLimit", "airspaceUpperLimit", "", "false");
        add(rows, "BoundaryPoint", "sequenceNumber", "序号", "BOUNDARY_POINT", "sequenceNumber", "sequenceNumber", "integer", "true");
        add(rows, "BoundaryPoint", "latitude", "纬度", "BOUNDARY_POINT", "latitude", "latitude", "decimal", "false");
        add(rows, "BoundaryPoint", "longitude", "经度", "BOUNDARY_POINT", "longitude", "longitude", "decimal", "false");
        add(rows, "BoundaryPoint", "isEndRaw", "是否终点", "BOUNDARY_POINT", "isEndRaw", "isEndRaw", "boolean", "false");
        add(rows, "ControlArea", "controlAreaCode", "管制区代码", "CONTROL_AREA", "controlAreaCode", "controlAreaCode", "", "true");
        add(rows, "ControlArea", "controlAreaName", "管制区名称", "CONTROL_AREA", "controlAreaName", "controlAreaName", "", "false");
        add(rows, "ControlArea", "callSign", "呼号", "CONTROL_AREA", "callSign", "callSign", "", "false");
        add(rows, "ControlArea", "controlAreaLowerLimit", "下限", "CONTROL_AREA", "controlAreaLowerLimit", "lowerLimit", "", "false");
        add(rows, "ControlArea", "controlAreaUpperLimit", "上限", "CONTROL_AREA", "controlAreaUpperLimit", "upperLimit", "", "false");
        add(rows, "FlightInformationRegion", "flightInformationRegionCode", "飞行情报区代码", "FLIGHT_INFORMATION_REGION", "flightInformationRegionCode", "flightInformationRegionCode", "", "true");
        add(rows, "FlightInformationRegion", "nameZh", "名称", "FLIGHT_INFORMATION_REGION", "nameZh", "nameZh", "", "false");
        add(rows, "FlightInformationRegion", "firUpperLimit", "上限", "FLIGHT_INFORMATION_REGION", "firUpperLimit", "upperLimit", "", "false");
        add(rows, "FlightInformationRegion", "firLowerLimit", "下限", "FLIGHT_INFORMATION_REGION", "firLowerLimit", "lowerLimit", "", "false");
        for (int i = 0; i < rows.size(); i++) row(sheet, i + 1, rows.get(i));
    }

    private static void writeRelationships(Sheet sheet) {
        row(sheet, 0, "关系类型", "起点类", "终点类", "sourceId", "起点定位字段", "终点定位字段", "说明");
        String[][] rows = {
                {"hasRunway", "Airport", "Runway", "fixture", "airportCode", "runwayCode", "RUNWAY record"},
                {"hasDirection", "Runway", "RunwayDirection", "fixture", "runwayCode", "directionKey", "RUNWAY_DIRECTION record"},
                {"hasNode", "Route", "RouteNode", "fixture", "routeCode", "nodeKey", "ordinary route node"},
                {"hasNode", "ScheduledFlightRoute", "RouteNode", "fixture", "scheduledRouteCode", "nodeKey", "scheduled route node"},
                {"hasSegment", "Route", "RouteSegment", "fixture", "routeCode", "segmentKey", "ordinary route segment"},
                {"hasSegment", "ScheduledFlightRoute", "RouteSegment", "fixture", "scheduledRouteCode", "segmentKey", "scheduled route segment"},
                {"nextNode", "RouteNode", "RouteNode", "fixture", "nodeKey", "nextNodeKey", "blank on terminal node"},
                {"fromNode", "RouteSegment", "RouteNode", "fixture", "segmentKey", "fromNodeKey", "segment start"},
                {"toNode", "RouteSegment", "RouteNode", "fixture", "segmentKey", "toNodeKey", "segment end"},
                {"refersTo", "RouteNode", "NavigationAid", "fixture", "nodeKey", "navigationAidCode", "mutually exclusive locator"},
                {"refersTo", "RouteNode", "ReportingPoint", "fixture", "nodeKey", "reportingPointCode", "mutually exclusive locator"},
                {"hasGeometry", "Airspace", "AirspaceGeometry", "fixture", "airspaceCode", "geometryKey", "geometry record"},
                {"hasBoundaryPoint", "AirspaceGeometry", "BoundaryPoint", "fixture", "geometryKey", "boundaryPointKey", "boundary point record"}
        };
        for (int i = 0; i < rows.length; i++) row(sheet, i + 1, rows[i]);
    }

    private static void add(List<String[]> rows, String className, String propertyName, String label,
                            String sourceObject, String mappedPropertyName, String sourcePath,
                            String transform, String required) {
        if (!propertyName.equals(mappedPropertyName)) {
            throw new IllegalArgumentException("fixture 属性映射列不一致：" + propertyName + " / " + mappedPropertyName);
        }
        rows.add(new String[]{className, propertyName, label, "fixture", sourceObject,
                sourcePath, transform, required});
    }

    private static void row(Sheet sheet, int index, String... values) {
        Row row = sheet.createRow(index);
        for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
    }
}
