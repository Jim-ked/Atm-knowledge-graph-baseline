package org.atmkg.tools.review;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.neo4j.driver.Record;
import org.neo4j.driver.Value;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Path;
import org.neo4j.driver.types.Relationship;

final class ReviewResultSummary {
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, Relationship> relationships = new LinkedHashMap<>();
    private final List<Map<String, Object>> scalarRows = new ArrayList<>();

    void accept(Record record) {
        Map<String, Object> scalars = new LinkedHashMap<>();
        for (String key : record.keys()) {
            Value value = record.get(key);
            Object object = value.isNull() ? null : value.asObject();
            if (object != null && !collectGraph(object)) scalars.put(key, object);
        }
        acceptScalarRow(scalars);
    }

    void acceptScalarRow(Map<String, Object> row) {
        if (row != null && !row.isEmpty()) scalarRows.add(new LinkedHashMap<>(row));
    }

    void print(PrintWriter output) {
        if (!nodes.isEmpty() || !relationships.isEmpty()) {
            output.println("图结果: 节点数=" + nodes.size() + ", 关系数=" + relationships.size());
            for (Node node : nodes.values()) output.println("  节点 " + nodeSummary(node));
            for (Relationship relationship : relationships.values()) output.println("  关系 " + relationshipSummary(relationship));
        }
        if (!scalarRows.isEmpty()) {
            output.println("数据结果:");
            if (scalarRows.size() == 1) {
                printFields(output, scalarRows.get(0), "  ");
            } else {
                for (int i = 0; i < scalarRows.size(); i++) {
                    output.println("  第 " + (i + 1) + " 行:");
                    printFields(output, scalarRows.get(i), "    ");
                }
            }
        }
        if (nodes.isEmpty() && relationships.isEmpty() && scalarRows.isEmpty()) output.println("  无结果");
    }

    private void printFields(PrintWriter output, Map<String, Object> row, String indent) {
        row.forEach((key, value) -> output.println(indent + key + "=" + value));
    }

    private boolean collectGraph(Object value) {
        if (value instanceof Node node) {
            nodes.putIfAbsent(entityKey(node.asMap(), node.elementId()), node);
            return true;
        }
        if (value instanceof Relationship relationship) {
            relationships.putIfAbsent(entityKey(relationship.asMap(), relationship.elementId()), relationship);
            return true;
        }
        if (value instanceof Path path) {
            path.nodes().forEach(node -> nodes.putIfAbsent(entityKey(node.asMap(), node.elementId()), node));
            path.relationships().forEach(relationship -> relationships.putIfAbsent(
                    entityKey(relationship.asMap(), relationship.elementId()), relationship));
            return true;
        }
        if (value instanceof Iterable<?> iterable) {
            boolean graph = false;
            for (Object item : iterable) graph |= collectGraph(item);
            return graph;
        }
        if (value instanceof Map<?, ?> map) {
            boolean graph = false;
            for (Object item : map.values()) graph |= collectGraph(item);
            return graph;
        }
        return false;
    }

    private String nodeSummary(Node node) {
        Map<String, Object> properties = node.asMap();
        return "labels=" + labels(node) + ", source_key=" + text(properties.get("kg_source_key"))
                + ", caption=" + text(properties.get("kg_caption")) + ", kg_uid=" + text(properties.get("kg_uid"));
    }

    private String relationshipSummary(Relationship relationship) {
        Map<String, Object> properties = relationship.asMap();
        return "type=" + relationship.type() + ", predicate=" + text(properties.get("kg_predicate_iri"))
                + ", kg_uid=" + text(properties.get("kg_uid"));
    }

    private String labels(Node node) {
        List<String> values = new ArrayList<>();
        node.labels().forEach(values::add);
        return values.toString();
    }

    private String entityKey(Map<String, Object> properties, String fallback) {
        Object uid = properties.get("kg_uid");
        return uid == null ? fallback : String.valueOf(uid);
    }

    private String text(Object value) { return value == null ? "-" : String.valueOf(value); }
}
