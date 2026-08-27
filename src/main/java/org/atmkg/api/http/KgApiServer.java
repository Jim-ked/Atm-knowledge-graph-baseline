package org.atmkg.api.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.atmkg.core.error.EntityLookupException;
import org.atmkg.core.error.QueryExecutionException;
import org.atmkg.core.error.ReadOnlyCypherException;
import org.atmkg.core.model.CypherResultDTO;
import org.atmkg.core.model.GraphDTO;
import org.atmkg.core.model.OntologySchema;
import org.atmkg.core.model.OntologyTerm;
import org.atmkg.core.model.QuerySpec;
import org.atmkg.core.spi.EntityLookupService;
import org.atmkg.core.spi.QueryService;
import org.atmkg.core.spi.ReadOnlyCypherService;

/**
 * 轻量 HTTP 入口，只负责请求校验、调用 QueryService 和返回 JSON，不实现图查询或业务语义。
 * 接口路径和结果上限改 {@code config/api.yaml}；API 正确但页面异常时应检查 Viewer。
 */
public final class KgApiServer implements AutoCloseable {
    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";

    private final ApiConfig config;
    private final QueryService queryService;
    private final EntityLookupService entityLookupService;
    private final ReadOnlyCypherService cypherExecutor;
    private final OntologySchema schema;
    private final BooleanSupplier neo4jHealth;
    private final HttpServer server;
    private boolean started;

    public KgApiServer(ApiConfig config, QueryService queryService, OntologySchema schema,
                       BooleanSupplier neo4jHealth) {
        this(config, queryService, null, null, schema, neo4jHealth);
    }

    public KgApiServer(ApiConfig config, QueryService queryService, ReadOnlyCypherService cypherExecutor,
                       OntologySchema schema, BooleanSupplier neo4jHealth) {
        this(config, queryService, null, cypherExecutor, schema, neo4jHealth);
    }

    public KgApiServer(ApiConfig config, QueryService queryService, EntityLookupService entityLookupService,
                       ReadOnlyCypherService cypherExecutor, OntologySchema schema,
                       BooleanSupplier neo4jHealth) {
        this.config = Objects.requireNonNull(config, "config");
        this.queryService = Objects.requireNonNull(queryService, "queryService");
        this.entityLookupService = entityLookupService;
        this.cypherExecutor = cypherExecutor;
        this.schema = Objects.requireNonNull(schema, "schema");
        this.neo4jHealth = Objects.requireNonNull(neo4jHealth, "neo4jHealth");
        try {
            server = HttpServer.create(new InetSocketAddress(config.getHost(), config.getPort()), 0);
            server.createContext(config.getBasePath(), this::handle);
        } catch (IOException ex) {
            throw new IllegalStateException("HTTP 服务绑定失败", ex);
        }
    }

    public synchronized void start() {
        if (started) return;
        server.start();
        started = true;
    }

    /** 挂载开发用静态文件，但不让 API 或 GraphDTO 依赖具体 Viewer。 */
    public synchronized void mountStatic(String urlPrefix, Path directory) {
        if (started) throw new IllegalStateException("静态资源必须在服务启动前挂载");
        Objects.requireNonNull(urlPrefix, "urlPrefix");
        Objects.requireNonNull(directory, "directory");
        String prefix = urlPrefix.endsWith("/")
                ? urlPrefix.substring(0, urlPrefix.length() - 1) : urlPrefix;
        if (!prefix.startsWith("/") || prefix.length() < 2 || prefix.contains("..")) {
            throw new IllegalArgumentException("静态资源 URL prefix 无效");
        }
        Path root = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("静态资源目录不存在：" + root);
        server.createContext(prefix, exchange -> handleStatic(exchange, prefix, root));
    }

    public InetSocketAddress getAddress() { return server.getAddress(); }

    @Override
    public synchronized void close() {
        if (!started) return;
        server.stop(0);
        started = false;
    }

    private void handle(HttpExchange exchange) {
        try {
            route(exchange);
        } catch (ApiFailure failure) {
            sendError(exchange, failure.status, failure.code, failure.getMessage(), failure.details);
        } catch (EntityLookupException failure) {
            sendError(exchange, failure.getStatus(), failure.getCode(), failure.getMessage(), failure.getDetails());
        } catch (ReadOnlyCypherException failure) {
            sendError(exchange, failure.getStatus(), failure.getCode(), failure.getMessage(), failure.getDetails());
        } catch (QueryExecutionException failure) {
            sendError(exchange, 500, "QUERY_FAILED", "图查询执行失败", Map.of());
        } catch (RuntimeException failure) {
            sendError(exchange, 500, "QUERY_FAILED", "服务处理请求失败", Map.of());
        } finally {
            exchange.close();
        }
    }

    private void handleStatic(HttpExchange exchange, String prefix, Path root) {
        try {
            String method = exchange.getRequestMethod();
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                staticError(exchange, 405);
                return;
            }
            String rawPath = exchange.getRequestURI().getRawPath();
            if (rawPath.equals(prefix)) {
                exchange.getResponseHeaders().set("Location", prefix + "/");
                exchange.sendResponseHeaders(308, -1);
                return;
            }
            if (!rawPath.startsWith(prefix + "/")) {
                staticError(exchange, 404);
                return;
            }
            String relative;
            try {
                relative = URLDecoder.decode(rawPath.substring(prefix.length() + 1).replace("+", "%2B"),
                        StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ex) {
                staticError(exchange, 404);
                return;
            }
            if (relative.isEmpty()) relative = "index.html";
            if (relative.indexOf('\0') >= 0 || relative.indexOf('\\') >= 0) {
                staticError(exchange, 404);
                return;
            }
            Path file = root.resolve(relative).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                staticError(exchange, 404);
                return;
            }
            try {
                if (!file.toRealPath().startsWith(root.toRealPath())) {
                    staticError(exchange, 404);
                    return;
                }
                byte[] body = Files.readAllBytes(file);
                exchange.getResponseHeaders().set("Content-Type", contentType(file));
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                exchange.getResponseHeaders().set("Content-Security-Policy",
                        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'");
                if ("HEAD".equals(method)) exchange.sendResponseHeaders(200, -1);
                else {
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            } catch (IOException ex) {
                staticError(exchange, 404);
            }
        } catch (IOException ignored) {
            // Client disconnected while a development resource was being served.
        } finally {
            exchange.close();
        }
    }

    private void staticError(HttpExchange exchange, int status) throws IOException {
        byte[] body = "Not Found".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, body.length);
        if (!"HEAD".equals(exchange.getRequestMethod())) exchange.getResponseBody().write(body);
    }

    private String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    private void route(HttpExchange exchange) {
        String method = exchange.getRequestMethod();
        String rawPath = exchange.getRequestURI().getRawPath();
        String base = config.getBasePath();
        if (exchange.getRequestURI().getRawQuery() != null) invalid("当前接口不接受 query parameters");

        if (rawPath.equals(base + "/health")) {
            requireMethod(exchange, "GET");
            health(exchange);
            return;
        }
        if (rawPath.equals(base + "/schema")) {
            requireMethod(exchange, "GET");
            schema(exchange);
            return;
        }
        if (rawPath.equals(base + "/entities/lookup")) {
            requireMethod(exchange, "POST");
            executeEntityLookup(exchange, readJson(exchange));
            return;
        }
        if (rawPath.startsWith(base + "/entities/")) {
            requireMethod(exchange, "GET");
            String rawUid = rawPath.substring((base + "/entities/").length());
            if (rawUid.isEmpty() || rawUid.contains("/")) invalid("uid 路径参数无效");
            entity(exchange, decodePathSegment(rawUid));
            return;
        }
        if (rawPath.equals(base + "/graph/one-hop")) {
            requireMethod(exchange, "POST");
            executeGraph(exchange, oneHop(readJson(exchange)), true);
            return;
        }
        if (rawPath.equals(base + "/graph/k-hop")) {
            requireMethod(exchange, "POST");
            executeGraph(exchange, kHop(readJson(exchange)), true);
            return;
        }
        if (rawPath.equals(base + "/graph/path")) {
            requireMethod(exchange, "POST");
            executeGraph(exchange, path(readJson(exchange)), false);
            return;
        }
        if (rawPath.equals(base + "/graph/query")) {
            requireMethod(exchange, "POST");
            QuerySpec spec = unified(readJson(exchange));
            executeGraph(exchange, spec, spec.getType() != QuerySpec.Type.PATH);
            return;
        }
        if (rawPath.equals(base + "/graph/named")) {
            requireMethod(exchange, "POST");
            executeGraph(exchange, named(readJson(exchange)), false);
            return;
        }
        if (rawPath.equals(base + "/graph/cypher")) {
            requireMethod(exchange, "POST");
            executeCypher(exchange, readCypher(readJson(exchange)));
            return;
        }
        throw new ApiFailure(404, "INVALID_REQUEST", "接口不存在", Map.of());
    }

    private void health(HttpExchange exchange) {
        boolean available;
        try {
            available = neo4jHealth.getAsBoolean();
        } catch (RuntimeException ex) {
            available = false;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", available ? "UP" : "DEGRADED");
        body.put("neo4j", available ? "UP" : "DOWN");
        sendJson(exchange, available ? 200 : 503, body);
    }

    private void schema(HttpExchange exchange) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", config.getSchemaVersion());
        body.put("classes", schema.getClasses().keySet().stream().sorted().toList());
        body.put("datatypeProperties", schema.getDatatypeProperties().keySet().stream().sorted().toList());
        body.put("objectProperties", schema.getObjectProperties().keySet().stream().sorted().toList());
        body.put("classLabels", labels(schema.getClasses()));
        body.put("datatypePropertyLabels", labels(schema.getDatatypeProperties()));
        body.put("objectPropertyLabels", labels(schema.getObjectProperties()));
        sendJson(exchange, 200, body);
    }

    private Map<String, String> labels(Map<String, OntologyTerm> terms) {
        Map<String, String> labels = new LinkedHashMap<>();
        terms.keySet().stream().sorted().forEach(iri -> {
            String label = terms.get(iri).getLabel();
            labels.put(iri, label == null || label.isBlank() ? localName(iri) : label);
        });
        return labels;
    }

    private String localName(String iri) {
        int index = Math.max(iri.lastIndexOf('#'), Math.max(iri.lastIndexOf('/'), iri.lastIndexOf(':')));
        return index >= 0 && index + 1 < iri.length() ? iri.substring(index + 1) : iri;
    }

    private void entity(HttpExchange exchange, String uid) {
        QuerySpec spec = new QuerySpec(QuerySpec.Type.ENTITY, requireText(uid, "uid"), null, null,
                Set.of(), Set.of(), QuerySpec.Direction.BOTH, null, Map.of());
        executeGraph(exchange, spec, true);
    }

    private void executeEntityLookup(HttpExchange exchange, JsonNode request) {
        if (entityLookupService == null) throw new QueryExecutionException("实体定位服务未装配");
        requireObject(request);
        rejectUnknown(request, Set.of("key", "classIri"));
        String key = requiredText(request, "key");
        String classIri = optionalText(request, "classIri");
        GraphDTO graph = entityLookupService.lookup(key, classIri);
        try {
            sendBytes(exchange, 200, ApiJson.writeGraph(graph).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalStateException("GraphDTO JSON 写出失败", ex);
        }
    }

    private void executeGraph(HttpExchange exchange, QuerySpec spec, boolean entityRequired) {
        GraphDTO graph = queryService.query(spec);
        if (entityRequired && graph.getNodes().isEmpty()) {
            throw new ApiFailure(404, "ENTITY_NOT_FOUND", "未找到指定实体", Map.of());
        }
        if (graph.getNodes().size() > config.getMaxResultNodes()
                || graph.getRelationships().size() > config.getMaxResultRelationships()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("nodeCount", graph.getNodes().size());
            details.put("relationshipCount", graph.getRelationships().size());
            throw new ApiFailure(413, "RESULT_TOO_LARGE", "完整查询结果超过服务配置上限", details);
        }
        try {
            sendBytes(exchange, 200, ApiJson.writeGraph(graph).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalStateException("GraphDTO JSON 写出失败", ex);
        }
    }

    private void executeCypher(HttpExchange exchange, String cypher) {
        if (cypherExecutor == null) {
            throw new QueryExecutionException("Viewer Cypher 执行器未装配");
        }
        CypherResultDTO result = cypherExecutor.execute(cypher);
        GraphDTO graph = result.getGraph();
        if (graph.getNodes().size() > config.getMaxResultNodes()
                || graph.getRelationships().size() > config.getMaxResultRelationships()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("nodeCount", graph.getNodes().size());
            details.put("relationshipCount", graph.getRelationships().size());
            throw new ReadOnlyCypherException(413, "RESULT_TOO_LARGE", "完整查询结果超过服务配置上限", details);
        }
        try {
            sendBytes(exchange, 200, ApiJson.writeCypher(result).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalStateException("CypherResultDTO JSON 写出失败", ex);
        }
    }

    private String readCypher(JsonNode request) {
        requireObject(request);
        rejectUnknown(request, Set.of("cypher"));
        JsonNode value = request.get("cypher");
        if (value == null || value.isNull() || !value.isTextual()) invalid("cypher 必须是字符串");
        String cypher = value.textValue().trim();
        if (cypher.isEmpty()) invalid("cypher 不能为空");
        if (cypher.length() > config.getMaxRequestBytes()) invalid("cypher 过长");
        return cypher;
    }

    private QuerySpec oneHop(JsonNode request) {
        requireObject(request);
        rejectUnknown(request, Set.of("uid"));
        return new QuerySpec(QuerySpec.Type.NEIGHBORS, requiredText(request, "uid"), null, 1,
                Set.of(), Set.of(), QuerySpec.Direction.BOTH, null, Map.of());
    }

    private QuerySpec kHop(JsonNode request) {
        requireObject(request);
        rejectUnknown(request, Set.of("uid", "depth", "relationshipTypes", "classFilters", "direction"));
        return new QuerySpec(QuerySpec.Type.K_HOP, requiredText(request, "uid"), null,
                requiredDepth(request, "depth"), stringSet(request, "relationshipTypes"),
                stringSet(request, "classFilters"), direction(request, QuerySpec.Direction.BOTH), null, Map.of());
    }

    private QuerySpec path(JsonNode request) {
        requireObject(request);
        rejectUnknown(request, Set.of("fromUid", "toUid", "maxDepth", "direction"));
        return new QuerySpec(QuerySpec.Type.PATH, requiredText(request, "fromUid"),
                requiredText(request, "toUid"), requiredDepth(request, "maxDepth"),
                Set.of(), Set.of(), direction(request, QuerySpec.Direction.OUTGOING), null, Map.of());
    }

    private QuerySpec named(JsonNode request) {
        requireObject(request);
        rejectUnknown(request, Set.of("queryId", "startUid"));
        return new QuerySpec(QuerySpec.Type.NAMED, requiredText(request, "startUid"), null, null,
                Set.of(), Set.of(), QuerySpec.Direction.BOTH, requiredText(request, "queryId"), Map.of());
    }

    private QuerySpec unified(JsonNode request) {
        requireObject(request);
        rejectUnknown(request, Set.of(
                "type", "startUid", "targetUid", "depth", "relationshipTypes", "classFilters", "direction"));
        QuerySpec.Type type;
        try {
            type = QuerySpec.Type.valueOf(requiredText(request, "type"));
        } catch (IllegalArgumentException ex) {
            invalid("type 必须是 ENTITY、NEIGHBORS、K_HOP 或 PATH");
            return null;
        }
        if (type == QuerySpec.Type.NAMED) invalid("NAMED 查询请使用 /graph/named");
        String startUid = requiredText(request, "startUid");
        String targetUid = type == QuerySpec.Type.PATH ? requiredText(request, "targetUid") : optionalText(request, "targetUid");
        Integer depth = type == QuerySpec.Type.K_HOP || type == QuerySpec.Type.PATH
                ? requiredDepth(request, "depth") : optionalDepth(request, "depth");
        QuerySpec.Direction defaultDirection = type == QuerySpec.Type.PATH
                ? QuerySpec.Direction.OUTGOING : QuerySpec.Direction.BOTH;
        return new QuerySpec(type, startUid, targetUid, depth,
                stringSet(request, "relationshipTypes"), stringSet(request, "classFilters"),
                direction(request, defaultDirection), null, Map.of());
    }

    private JsonNode readJson(HttpExchange exchange) {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
            invalid("POST 请求必须使用 application/json");
        }
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLength != null) {
            try {
                if (Long.parseLong(contentLength) > config.getMaxRequestBytes()) invalid("请求体超过大小上限");
            } catch (NumberFormatException ex) {
                invalid("Content-Length 无效");
            }
        }
        try {
            byte[] body = exchange.getRequestBody().readNBytes(config.getMaxRequestBytes() + 1);
            if (body.length > config.getMaxRequestBytes()) invalid("请求体超过大小上限");
            if (body.length == 0) invalid("请求体不能为空");
            return ApiJson.read(body);
        } catch (IOException ex) {
            invalid("请求体不是合法 JSON");
            return null;
        }
    }

    private void requireObject(JsonNode request) {
        if (request == null || !request.isObject()) invalid("请求体必须是 JSON object");
    }

    private void rejectUnknown(JsonNode request, Set<String> allowed) {
        request.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) invalid("未知请求字段：" + field);
        });
    }

    private String requiredText(JsonNode request, String field) {
        String value = optionalText(request, field);
        if (value == null) invalid(field + " 不能为空");
        return value;
    }

    private String optionalText(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) invalid(field + " 必须是字符串");
        return requireText(value.textValue(), field);
    }

    private Integer requiredDepth(JsonNode request, String field) {
        Integer value = optionalDepth(request, field);
        if (value == null) invalidDepth(field + " 不能为空");
        return value;
    }

    private Integer optionalDepth(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) invalidDepth(field + " 必须是整数");
        int depth = value.intValue();
        if (depth < 1 || depth > config.getMaxDepth()) {
            invalidDepth(field + " 必须在 1.." + config.getMaxDepth() + " 范围内");
        }
        return depth;
    }

    private Set<String> stringSet(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull()) return Set.of();
        if (!value.isArray() || value.size() > 256) invalid(field + " 必须是至多 256 项的字符串数组");
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) invalid(field + " 必须是字符串数组");
            values.add(requireText(item.textValue(), field));
        }
        return values;
    }

    private QuerySpec.Direction direction(JsonNode request, QuerySpec.Direction fallback) {
        String value = optionalText(request, "direction");
        if (value == null) return fallback;
        try {
            return QuerySpec.Direction.valueOf(value);
        } catch (IllegalArgumentException ex) {
            invalid("direction 必须是 OUTGOING、INCOMING 或 BOTH");
            return fallback;
        }
    }

    private String decodePathSegment(String raw) {
        try {
            return URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            invalid("uid 路径编码无效");
            return null;
        }
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) invalid(field + " 不能为空");
        String text = value.trim();
        if (text.length() > 4096) invalid(field + " 过长");
        return text;
    }

    private void requireMethod(HttpExchange exchange, String expected) {
        if (!expected.equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", expected);
            invalid("HTTP method 不允许");
        }
    }

    private void invalid(String message) {
        throw new ApiFailure(400, "INVALID_REQUEST", message, Map.of());
    }

    private void invalidDepth(String message) {
        throw new ApiFailure(400, "INVALID_DEPTH", message, Map.of());
    }

    private void sendError(HttpExchange exchange, int status, String code, String message, Map<String, Object> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("details", details);
        sendJson(exchange, status, body);
    }

    private void sendJson(HttpExchange exchange, int status, Object body) {
        try {
            sendBytes(exchange, status, ApiJson.write(body));
        } catch (IOException ex) {
            throw new IllegalStateException("JSON 响应写出失败", ex);
        }
    }

    private void sendBytes(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", JSON_CONTENT_TYPE);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static final class ApiFailure extends RuntimeException {
        final int status;
        final String code;
        final Map<String, Object> details;

        ApiFailure(int status, String code, String message, Map<String, Object> details) {
            super(message);
            this.status = status;
            this.code = code;
            this.details = details;
        }
    }
}
