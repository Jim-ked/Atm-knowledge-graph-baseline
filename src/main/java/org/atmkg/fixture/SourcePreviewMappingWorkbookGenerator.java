package org.atmkg.fixture;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.atmkg.infra.mapping.MappingWorkbookFormat;

/** Generates the development-only mapping workbook for source-preview XLSX fixtures. */
public final class SourcePreviewMappingWorkbookGenerator {
    private SourcePreviewMappingWorkbookGenerator() {}

    public static void main(String[] args) {
        Path output = args.length == 0
                ? Path.of("fixtures/mapping/source_preview_mapping.xlsx") : Path.of(args[0]);
        generate(output);
        System.out.println("source-preview mapping workbook generated: " + output.toAbsolutePath().normalize());
    }

    public static void generate(Path output) {
        try {
            Files.createDirectories(output.toAbsolutePath().normalize().getParent());
            try (Workbook workbook = new XSSFWorkbook()) {
                MappingWorkbookFormat.createFormalSheets(workbook);
                Sheet entities = workbook.getSheet(MappingWorkbookFormat.ENTITY_SHEET);
                Sheet properties = workbook.getSheet(MappingWorkbookFormat.PROPERTY_SHEET);
                Sheet relationships = workbook.getSheet(MappingWorkbookFormat.RELATIONSHIP_SHEET);
                writeRouteMappings(entities, properties, relationships);
                writeAirspaceMappings(entities, properties, relationships);
                writeScheduledRouteMappings(entities, properties, relationships);
                MappingWorkbookFormat.applyEditingFeatures(workbook);
                try (OutputStream stream = Files.newOutputStream(output)) {
                    workbook.write(stream);
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("source-preview mapping workbook 写入失败：" + output, ex);
        }
    }

    private static void writeRouteMappings(Sheet e, Sheet p, Sheet r) {
        entity(e, "Route", "preview-route-parent", "route-parent", "航路代码");
        property(p, "Route", "routeCode", "preview-route-parent", "route-parent", "航路代码", "", true);
        property(p, "Route", "routeUpperLimit", "preview-route-parent", "route-parent", "上限", "", false);
        property(p, "Route", "routeLowerLimit", "preview-route-parent", "route-parent", "下限", "", false);

        entity(e, "Route", "preview-route-node", "route-node", "航路代码");
        entity(e, "RouteNode", "preview-route-node", "route-node", "__sourceKey");
        property(p, "Route", "routeCode", "preview-route-node", "route-node", "航路代码", "", true);
        nodeProperties(p, "preview-route-node", "route-node", "");
        relation(r, "hasNode", "Route", "RouteNode", "preview-route-node", "route-node", "航路代码", "__sourceKey");

        entity(e, "Route", "preview-route-segment", "route-segment", "current.航路代码");
        entity(e, "RouteNode", "preview-route-segment", "route-segment", "current.__sourceKey");
        entity(e, "RouteSegment", "preview-route-segment", "route-segment", "current.__sourceKey");
        property(p, "Route", "routeCode", "preview-route-segment", "route-segment", "current.航路代码", "", true);
        nodeProperties(p, "preview-route-segment", "route-segment", "current.");
        property(p, "RouteSegment", "magneticCourse", "preview-route-segment", "route-segment", "current.磁航向", "decimal", false);
        property(p, "RouteSegment", "reverseMagneticCourse", "preview-route-segment", "route-segment", "current.反向磁航向", "decimal", false);
        property(p, "RouteSegment", "segmentDistance", "preview-route-segment", "route-segment", "current.航段距离", "decimal", false);
        property(p, "RouteSegment", "requiredNavigationPerformance", "preview-route-segment", "route-segment", "current.RNP", "", false);
        segmentRelations(r, "Route", "preview-route-segment", "route-segment", "current.航路代码");
    }

    private static void writeAirspaceMappings(Sheet e, Sheet p, Sheet r) {
        entity(e, "Airspace", "preview-airspace-main", "airspace-main", "空域代码");
        entity(e, "AirspaceGeometry", "preview-airspace-main", "airspace-main", "空域代码");
        property(p, "Airspace", "airspaceCode", "preview-airspace-main", "airspace-main", "空域代码", "", true);
        property(p, "Airspace", "airspaceName", "preview-airspace-main", "airspace-main", "空域名称", "", false);
        property(p, "Airspace", "airspaceTypeCode", "preview-airspace-main", "airspace-main", "类型码", "", false);
        property(p, "Airspace", "airspaceUpperLimit", "preview-airspace-main", "airspace-main", "上限", "", false);
        property(p, "Airspace", "airspaceLowerLimit", "preview-airspace-main", "airspace-main", "下限", "", false);
        relation(r, "hasGeometry", "Airspace", "AirspaceGeometry", "preview-airspace-main", "airspace-main", "空域代码", "空域代码");

        entity(e, "AirspaceGeometry", "preview-airspace-boundary", "airspace-boundary", "空域代码");
        entity(e, "BoundaryPoint", "preview-airspace-boundary", "airspace-boundary", "__sourceKey");
        property(p, "BoundaryPoint", "sequenceNumber", "preview-airspace-boundary", "airspace-boundary", "序号", "integer", true);
        property(p, "BoundaryPoint", "longitude", "preview-airspace-boundary", "airspace-boundary", "经度", "decimal", false);
        property(p, "BoundaryPoint", "latitude", "preview-airspace-boundary", "airspace-boundary", "纬度", "decimal", false);
        property(p, "BoundaryPoint", "isEndRaw", "preview-airspace-boundary", "airspace-boundary", "是否闭合", "boolean", false);
        relation(r, "hasBoundaryPoint", "AirspaceGeometry", "BoundaryPoint", "preview-airspace-boundary", "airspace-boundary", "空域代码", "__sourceKey");
    }

    private static void writeScheduledRouteMappings(Sheet e, Sheet p, Sheet r) {
        entity(e, "ScheduledFlightRoute", "preview-scheduled-parent", "scheduled-route-parent", "航线代码");
        property(p, "ScheduledFlightRoute", "routeCode", "preview-scheduled-parent", "scheduled-route-parent", "航线代码", "", true);

        entity(e, "ScheduledFlightRoute", "preview-scheduled-node", "scheduled-route-node", "航线代码");
        entity(e, "RouteNode", "preview-scheduled-node", "scheduled-route-node", "__sourceKey");
        property(p, "ScheduledFlightRoute", "routeCode", "preview-scheduled-node", "scheduled-route-node", "航线代码", "", true);
        scheduledNodeProperties(p, "preview-scheduled-node", "scheduled-route-node", "");
        relation(r, "hasNode", "ScheduledFlightRoute", "RouteNode", "preview-scheduled-node", "scheduled-route-node", "航线代码", "__sourceKey");

        entity(e, "ScheduledFlightRoute", "preview-scheduled-segment", "scheduled-route-segment", "current.航线代码");
        entity(e, "RouteNode", "preview-scheduled-segment", "scheduled-route-segment", "current.__sourceKey");
        entity(e, "RouteSegment", "preview-scheduled-segment", "scheduled-route-segment", "current.__sourceKey");
        property(p, "ScheduledFlightRoute", "routeCode", "preview-scheduled-segment", "scheduled-route-segment", "current.航线代码", "", true);
        scheduledNodeProperties(p, "preview-scheduled-segment", "scheduled-route-segment", "current.");
        property(p, "RouteSegment", "magneticCourse", "preview-scheduled-segment", "scheduled-route-segment", "current.航向", "decimal", false);
        segmentRelations(r, "ScheduledFlightRoute", "preview-scheduled-segment", "scheduled-route-segment", "current.航线代码");
    }

    private static void nodeProperties(Sheet p, String sourceId, String object, String prefix) {
        property(p, "RouteNode", "nodeCode", sourceId, object, prefix + "节点代码", "", false);
        property(p, "RouteNode", "nodeName", sourceId, object, prefix + "节点名称", "", false);
        property(p, "RouteNode", "nodeTypeCode", sourceId, object, prefix + "节点类型", "", false);
        property(p, "RouteNode", "sequenceNumber", sourceId, object, prefix + "序号", "integer", true);
        property(p, "RouteNode", "longitude", sourceId, object, prefix + "经度", "decimal", false);
        property(p, "RouteNode", "latitude", sourceId, object, prefix + "纬度", "decimal", false);
    }

    private static void scheduledNodeProperties(Sheet p, String sourceId, String object, String prefix) {
        property(p, "RouteNode", "nodeCode", sourceId, object, prefix + "点代码", "", false);
        property(p, "RouteNode", "nodeName", sourceId, object, prefix + "点名称", "", false);
        property(p, "RouteNode", "nodeTypeCode", sourceId, object, prefix + "点类型", "", false);
        property(p, "RouteNode", "sequenceNumber", sourceId, object, prefix + "序号", "integer", true);
        property(p, "RouteNode", "longitude", sourceId, object, prefix + "经度", "decimal", false);
        property(p, "RouteNode", "latitude", sourceId, object, prefix + "纬度", "decimal", false);
    }

    private static void segmentRelations(Sheet r, String routeClass, String sourceId,
                                         String sourceObject, String routeLocator) {
        relation(r, "hasSegment", routeClass, "RouteSegment", sourceId, sourceObject,
                routeLocator, "current.__sourceKey");
        relation(r, "nextNode", "RouteNode", "RouteNode", sourceId, sourceObject,
                "current.__sourceKey", "next.__sourceKey");
        relation(r, "fromNode", "RouteSegment", "RouteNode", sourceId, sourceObject,
                "current.__sourceKey", "current.__sourceKey");
        relation(r, "toNode", "RouteSegment", "RouteNode", sourceId, sourceObject,
                "current.__sourceKey", "next.__sourceKey");
    }

    private static void entity(Sheet sheet, String type, String sourceId, String object, String key) {
        row(sheet, sourceId, object, type, key);
    }

    private static void property(Sheet sheet, String type, String property, String sourceId,
                                 String object, String path, String transform, boolean required) {
        row(sheet, sourceId, object, type, path, property, transform, required ? "是" : "否");
    }

    private static void relation(Sheet sheet, String predicate, String from, String to,
                                 String sourceId, String sourceObject, String fromPath, String toPath) {
        row(sheet, sourceId, sourceObject, predicate, from, fromPath, to, toPath, "source-preview fixture");
    }

    private static void row(Sheet sheet, String... values) {
        int index = sheet.getPhysicalNumberOfRows() == 0 ? 0 : sheet.getLastRowNum() + 1;
        Row row = sheet.createRow(index);
        for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
    }
}
