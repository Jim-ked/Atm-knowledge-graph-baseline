package org.atmkg.infra.trigger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 保存 JDBC polling 的本地技术水位。这里只保存 scope 与 watermark，不保存连接信息或业务记录。
 * 文件损坏必须显式失败，避免静默退回 initialWatermark 后产生范围不明的重扫。
 */
public final class PollingCheckpointStore {
    public static final String DEFAULT_RELATIVE_PATH = "runtime/state/polling-checkpoints.json";

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    public PollingCheckpointStore(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
    }

    public synchronized Optional<Instant> load(String sourceId, String objectName) {
        return Optional.ofNullable(readAll().get(scope(sourceId, objectName)));
    }

    public synchronized void save(String sourceId, String objectName, Instant watermark) {
        Objects.requireNonNull(watermark, "watermark");
        Map<String, Instant> checkpoints = readAll();
        checkpoints.put(scope(sourceId, objectName), watermark);

        Path parent = file.getParent();
        Path temporary = null;
        try {
            if (parent != null) Files.createDirectories(parent);
            Path tempDirectory = parent == null ? Path.of(".").toAbsolutePath().normalize() : parent;
            temporary = Files.createTempFile(tempDirectory, file.getFileName().toString() + ".", ".tmp");
            try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                mapper.writerWithDefaultPrettyPrinter().writeValue(writer, json(checkpoints));
            }
            replace(temporary, file);
            temporary = null;
        } catch (IOException ex) {
            throw new IllegalStateException("polling checkpoint无法保存：" + file, ex);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // 保留原始保存异常；残留临时文件不应掩盖真正失败原因。
                }
            }
        }
    }

    public Path getFile() {
        return file;
    }

    private Map<String, Instant> readAll() {
        if (!Files.exists(file)) return new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonNode root = mapper.readTree(reader);
            if (root == null || !root.isObject()) throw new IllegalArgumentException("根节点必须是对象");
            Map<String, Instant> checkpoints = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode entry = field.getValue();
                JsonNode watermark = entry == null || !entry.isObject() ? null : entry.get("watermark");
                if (watermark == null || !watermark.isTextual() || watermark.textValue().isBlank()) {
                    throw new IllegalArgumentException("scope缺少watermark：" + field.getKey());
                }
                checkpoints.put(field.getKey(), Instant.parse(watermark.textValue()));
            }
            return checkpoints;
        } catch (IOException | DateTimeParseException | IllegalArgumentException ex) {
            throw new IllegalStateException("polling checkpoint无法读取：" + file, ex);
        }
    }

    private ObjectNode json(Map<String, Instant> checkpoints) {
        ObjectNode root = mapper.createObjectNode();
        for (Map.Entry<String, Instant> checkpoint : new TreeMap<>(checkpoints).entrySet()) {
            root.putObject(checkpoint.getKey()).put("watermark", checkpoint.getValue().toString());
        }
        return root;
    }

    private static void replace(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String scope(String sourceId, String objectName) {
        return requireText(sourceId, "sourceId") + "/" + requireText(objectName, "objectName");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        return value;
    }
}
