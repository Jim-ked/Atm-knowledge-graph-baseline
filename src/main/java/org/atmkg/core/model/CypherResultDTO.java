package org.atmkg.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One read-only Cypher execution represented as table rows plus a graph projection. */
public final class CypherResultDTO {
    private final String schemaVersion;
    private final List<String> columns;
    private final List<Map<String, Object>> rows;
    private final GraphDTO graph;
    private final Map<String, Object> meta;

    public CypherResultDTO(String schemaVersion, List<String> columns, List<Map<String, Object>> rows,
                           GraphDTO graph, Map<String, Object> meta) {
        this.schemaVersion = Objects.requireNonNull(schemaVersion, "schemaVersion");
        this.columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        Objects.requireNonNull(rows, "rows");
        List<Map<String, Object>> copiedRows = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            copiedRows.add(Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(row, "row"))));
        }
        this.rows = List.copyOf(copiedRows);
        this.graph = Objects.requireNonNull(graph, "graph");
        this.meta = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(meta, "meta")));
    }

    public String getSchemaVersion() { return schemaVersion; }
    public List<String> getColumns() { return columns; }
    public List<Map<String, Object>> getRows() { return rows; }
    public GraphDTO getGraph() { return graph; }
    public Map<String, Object> getMeta() { return meta; }
}
