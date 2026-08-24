package org.atmkg.service.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** 读取并严格校验 {@code config/sync.yaml} 中的进程级同步和 polling 策略。 */
public final class SyncRuntimeConfig {
    private static final Set<String> SYNC_FIELDS = Set.of(
            "initialFullImport", "incremental", "compensation", "manualResync",
            "eventCarriesAuthoritativeData", "polling");
    private static final Set<String> POLLING_FIELDS = Set.of(
            "enabled", "intervalSeconds", "lookbackSeconds", "scopes");
    private static final Set<String> SCOPE_FIELDS = Set.of("sourceId", "sourceObject", "initialWatermark");

    private final boolean initialFullImport;
    private final boolean incremental;
    private final boolean compensation;
    private final boolean manualResync;
    private final Duration pollingInterval;
    private final Duration pollingLookback;
    private final boolean pollingEnabled;
    private final List<PollingScope> pollingScopes;

    private SyncRuntimeConfig(boolean initialFullImport, boolean incremental, boolean compensation,
                              boolean manualResync, Duration pollingInterval, Duration pollingLookback,
                              boolean pollingEnabled,
                              List<PollingScope> pollingScopes) {
        this.initialFullImport = initialFullImport;
        this.incremental = incremental;
        this.compensation = compensation;
        this.manualResync = manualResync;
        this.pollingInterval = pollingInterval;
        this.pollingLookback = pollingLookback;
        this.pollingEnabled = pollingEnabled;
        this.pollingScopes = List.copyOf(pollingScopes);
    }

    public static SyncRuntimeConfig load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("同步配置文件不存在：" + file);
        }
        try {
            JsonNode sync = new YAMLMapper().readTree(file.toFile()).path("sync");
            if (!sync.isObject()) throw new IllegalArgumentException("sync.yaml 缺少 sync 对象");
            rejectUnknown(sync, SYNC_FIELDS, "sync");
            boolean initialFullImport = bool(sync, "initialFullImport");
            boolean incremental = bool(sync, "incremental");
            boolean compensation = bool(sync, "compensation");
            boolean manualResync = bool(sync, "manualResync");
            if (bool(sync, "eventCarriesAuthoritativeData")) {
                throw new IllegalArgumentException("eventCarriesAuthoritativeData 必须为 false；变化事件只携带源记录身份");
            }

            JsonNode polling = sync.get("polling");
            if (polling == null || !polling.isObject()) {
                throw new IllegalArgumentException("sync.polling 必须是对象");
            }
            rejectUnknown(polling, POLLING_FIELDS, "sync.polling");
            boolean pollingEnabled = bool(polling, "enabled");
            long intervalSeconds = positiveLong(polling, "intervalSeconds");
            long lookbackSeconds = nonNegativeLong(polling, "lookbackSeconds");
            List<PollingScope> scopes = scopes(polling.get("scopes"));
            if (pollingEnabled && !incremental) {
                throw new IllegalArgumentException("polling.enabled=true 要求 incremental=true");
            }
            if (pollingEnabled && scopes.isEmpty()) {
                throw new IllegalArgumentException("polling.enabled=true 时 scopes 不能为空");
            }
            return new SyncRuntimeConfig(initialFullImport, incremental, compensation, manualResync,
                    Duration.ofSeconds(intervalSeconds), Duration.ofSeconds(lookbackSeconds),
                    pollingEnabled, scopes);
        } catch (IOException ex) {
            throw new IllegalArgumentException("同步配置读取失败：" + file, ex);
        }
    }

    private static List<PollingScope> scopes(JsonNode node) {
        if (node == null || !node.isArray()) throw new IllegalArgumentException("sync.polling.scopes 必须是数组");
        List<PollingScope> scopes = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        for (JsonNode item : node) {
            if (!item.isObject()) throw new IllegalArgumentException("polling scope 必须是对象");
            rejectUnknown(item, SCOPE_FIELDS, "sync.polling.scopes[]");
            String sourceId = text(item, "sourceId");
            String sourceObject = text(item, "sourceObject");
            String rawWatermark = text(item, "initialWatermark");
            Instant initialWatermark;
            try {
                initialWatermark = Instant.parse(rawWatermark);
            } catch (DateTimeParseException ex) {
                throw new IllegalArgumentException("initialWatermark 必须是 ISO-8601 UTC 时间："
                        + sourceId + "/" + sourceObject, ex);
            }
            if (!identities.add(sourceId + "\u0000" + sourceObject)) {
                throw new IllegalArgumentException("重复 polling scope：" + sourceId + "/" + sourceObject);
            }
            scopes.add(new PollingScope(sourceId, sourceObject, initialWatermark));
        }
        return scopes;
    }

    private static void rejectUnknown(JsonNode node, Set<String> allowed, String location) {
        Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowed.contains(field)) throw new IllegalArgumentException("未知 " + location + " 配置项：" + field);
        }
    }

    private static boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) throw new IllegalArgumentException(field + " 必须是 boolean");
        return value.booleanValue();
    }

    private static long positiveLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw new IllegalArgumentException(field + " 必须是大于 0 的整数");
        }
        return value.longValue();
    }

    private static long nonNegativeLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw new IllegalArgumentException(field + " 必须是大于等于 0 的整数");
        }
        return value.longValue();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " 必须是非空字符串");
        }
        return value.textValue().trim();
    }

    public boolean isInitialFullImportEnabled() { return initialFullImport; }
    public boolean isIncrementalEnabled() { return incremental; }
    public boolean isCompensationEnabled() { return compensation; }
    public boolean isManualResyncEnabled() { return manualResync; }
    public Duration getPollingInterval() { return pollingInterval; }
    public Duration getPollingLookback() { return pollingLookback; }
    public boolean isPollingEnabled() { return pollingEnabled; }
    public List<PollingScope> getPollingScopes() { return pollingScopes; }

    public record PollingScope(String sourceId, String sourceObject, Instant initialWatermark) {}
}
