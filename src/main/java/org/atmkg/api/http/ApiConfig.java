package org.atmkg.api.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Set;

/** 读取并严格校验 {@code config/api.yaml}；不承载查询语义或 Viewer 配置。 */
public final class ApiConfig {
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "host", "port", "basePath", "schemaVersion", "maxDepth", "maxRequestBytes",
            "maxResultNodes", "maxResultRelationships");

    private final String host;
    private final int port;
    private final String basePath;
    private final String schemaVersion;
    private final int maxDepth;
    private final int maxRequestBytes;
    private final int maxResultNodes;
    private final int maxResultRelationships;

    public ApiConfig(String host, int port, String basePath, String schemaVersion, int maxDepth,
                     int maxRequestBytes, int maxResultNodes, int maxResultRelationships) {
        this.host = requireText(host, "host");
        if (port < 0 || port > 65535) throw new IllegalArgumentException("port 必须在 0..65535 范围内");
        this.port = port;
        this.basePath = normalizeBasePath(basePath);
        this.schemaVersion = requireText(schemaVersion, "schemaVersion");
        this.maxDepth = requirePositive(maxDepth, "maxDepth");
        this.maxRequestBytes = requirePositive(maxRequestBytes, "maxRequestBytes");
        this.maxResultNodes = requirePositive(maxResultNodes, "maxResultNodes");
        this.maxResultRelationships = requirePositive(maxResultRelationships, "maxResultRelationships");
    }

    public static ApiConfig load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("API 配置文件不存在：" + file);
        }
        try {
            JsonNode api = new YAMLMapper().readTree(file.toFile()).path("api");
            if (!api.isObject()) throw new IllegalArgumentException("api.yaml 缺少 api 对象");
            rejectUnknownFields(api);
            String host = environmentOr("ATMKG_API_HOST", text(api, "host"));
            int port = environmentIntOr("ATMKG_API_PORT", integer(api, "port"));
            return new ApiConfig(host, port, text(api, "basePath"), text(api, "schemaVersion"),
                    integer(api, "maxDepth"), integer(api, "maxRequestBytes"),
                    integer(api, "maxResultNodes"), integer(api, "maxResultRelationships"));
        } catch (IOException ex) {
            throw new IllegalArgumentException("API 配置读取失败：" + file, ex);
        }
    }

    private static void rejectUnknownFields(JsonNode api) {
        Iterator<String> fields = api.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!ALLOWED_FIELDS.contains(field)) throw new IllegalArgumentException("未知 API 配置项：" + field);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw new IllegalArgumentException(field + " 必须是字符串");
        return value.textValue();
    }

    private static int integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(field + " 必须是整数");
        }
        return value.intValue();
    }

    private static String environmentOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int environmentIntOr(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(name + " 必须是整数", ex);
        }
    }

    private static String normalizeBasePath(String value) {
        String path = requireText(value, "basePath");
        if (!path.startsWith("/")) throw new IllegalArgumentException("basePath 必须以 / 开头");
        while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        return value.trim();
    }

    private static int requirePositive(int value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " 必须大于 0");
        return value;
    }

    public String getHost() { return host; }
    public int getPort() { return port; }
    public String getBasePath() { return basePath; }
    public String getSchemaVersion() { return schemaVersion; }
    public int getMaxDepth() { return maxDepth; }
    public int getMaxRequestBytes() { return maxRequestBytes; }
    public int getMaxResultNodes() { return maxResultNodes; }
    public int getMaxResultRelationships() { return maxResultRelationships; }
}
