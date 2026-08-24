package org.atmkg.api.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.atmkg.core.model.GraphDTO;
import org.atmkg.core.model.GraphNodeDTO;
import org.atmkg.core.model.GraphRelationshipDTO;

/** API 的稳定 JSON 转换边界，留在 Core 之外以保持 GraphDTO 不依赖具体 HTTP/JSON 框架。 */
final class ApiJson {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private ApiJson() {}

    static JsonNode read(byte[] input) throws IOException {
        return MAPPER.readTree(input);
    }

    static String writeGraph(GraphDTO graph) throws JsonProcessingException {
        return MAPPER.writeValueAsString(graphMap(graph));
    }

    static byte[] write(Object value) throws JsonProcessingException {
        return MAPPER.writeValueAsBytes(value);
    }

    private static Map<String, Object> graphMap(GraphDTO graph) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", graph.getSchemaVersion());
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (GraphNodeDTO node : graph.getNodes()) nodes.add(nodeMap(node));
        out.put("nodes", nodes);
        List<Map<String, Object>> relationships = new ArrayList<>();
        for (GraphRelationshipDTO relationship : graph.getRelationships()) relationships.add(relationshipMap(relationship));
        out.put("relationships", relationships);
        out.put("meta", new TreeMap<>(graph.getMeta()));
        return out;
    }

    private static Map<String, Object> nodeMap(GraphNodeDTO node) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", node.getId());
        out.put("labels", node.getLabels());
        out.put("kind", node.getKind());
        out.put("caption", node.getCaption());
        out.put("properties", new TreeMap<>(node.getProperties()));
        return out;
    }

    private static Map<String, Object> relationshipMap(GraphRelationshipDTO relationship) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", relationship.getId());
        out.put("source", relationship.getSource());
        out.put("target", relationship.getTarget());
        out.put("type", relationship.getType());
        out.put("properties", new TreeMap<>(relationship.getProperties()));
        return out;
    }
}
