package org.atmkg.api.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.atmkg.core.model.GraphDTO;
import org.atmkg.core.model.GraphNodeDTO;
import org.atmkg.core.model.GraphRelationshipDTO;
import org.junit.jupiter.api.Test;

class GraphDtoJsonTest {
    @Test
    void serializesGraphDtoWithStablePublicIdsAndFieldOrder() throws Exception {
        GraphDTO graph = new GraphDTO("1",
                List.of(new GraphNodeDTO("node-1", List.of("Airport", "AviationBaseObject"),
                        "Airport", "Z001", Map.of("urn:z", "last", "urn:a", new BigDecimal("12.50")))),
                List.of(new GraphRelationshipDTO("rel-1", "node-1", "node-2", "HAS_RUNWAY", Map.of())),
                Map.of("complete", true, "nodeCount", 1));

        String json = ApiJson.writeGraph(graph);

        assertEquals("{\"schemaVersion\":\"1\",\"nodes\":[{\"id\":\"node-1\",\"labels\":[\"Airport\",\"AviationBaseObject\"],\"kind\":\"Airport\",\"caption\":\"Z001\",\"properties\":{\"urn:a\":12.50,\"urn:z\":\"last\"}}],\"relationships\":[{\"id\":\"rel-1\",\"source\":\"node-1\",\"target\":\"node-2\",\"type\":\"HAS_RUNWAY\",\"properties\":{}}],\"meta\":{\"complete\":true,\"nodeCount\":1}}", json);
        assertFalse(json.contains("elementId"));
    }
}
