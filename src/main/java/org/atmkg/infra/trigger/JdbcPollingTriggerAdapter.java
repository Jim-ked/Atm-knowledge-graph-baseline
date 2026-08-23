package org.atmkg.infra.trigger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.atmkg.core.model.ChangeEvent;
import org.atmkg.core.model.SourceRecord;
import org.atmkg.core.spi.SourceAdapter;
import org.atmkg.core.spi.TriggerAdapter;

/**
 * Minimal JDBC watermark polling trigger.
 *
 * <p>Changed rows are discovery signals only. This adapter emits their stable source identity and
 * leaves the authoritative {@code readByKey -> mapping -> replaceProjection} path to SyncService.
 * Hard deletes are intentionally outside this timestamp-watermark boundary.
 */
public final class JdbcPollingTriggerAdapter implements TriggerAdapter {
    private final Map<String, SourceAdapter> adapters;
    private final List<ScopeKey> scopes;
    private final Map<ScopeKey, Instant> watermarks;
    private final long intervalNanos;
    private ScheduledExecutorService executor;
    private Consumer<ChangeEvent> consumer;

    public JdbcPollingTriggerAdapter(Map<String, SourceAdapter> adapters,
                                     Collection<PollingScope> scopes,
                                     Duration interval) {
        Objects.requireNonNull(adapters, "adapters");
        Objects.requireNonNull(scopes, "scopes");
        Objects.requireNonNull(interval, "interval");
        if (adapters.isEmpty()) throw new IllegalArgumentException("adapters 不能为空");
        if (scopes.isEmpty()) throw new IllegalArgumentException("polling scopes 不能为空");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("polling interval 必须大于 0");
        }
        this.intervalNanos = interval.toNanos();
        this.adapters = Map.copyOf(new LinkedHashMap<>(adapters));

        List<ScopeKey> parsedScopes = new ArrayList<>();
        Map<ScopeKey, Instant> initialWatermarks = new LinkedHashMap<>();
        for (PollingScope scope : scopes) {
            Objects.requireNonNull(scope, "polling scope");
            ScopeKey key = new ScopeKey(scope.sourceId(), scope.objectName());
            if (!this.adapters.containsKey(key.sourceId())) {
                throw new IllegalArgumentException("polling scope 未注册 SourceAdapter：" + key.sourceId());
            }
            if (initialWatermarks.putIfAbsent(key, scope.initialWatermark()) != null) {
                throw new IllegalArgumentException("重复 polling scope：" + key.display());
            }
            parsedScopes.add(key);
        }
        this.scopes = List.copyOf(parsedScopes);
        this.watermarks = initialWatermarks;
    }

    @Override
    public synchronized void start(Consumer<ChangeEvent> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        if (executor != null) throw new IllegalStateException("JDBC polling 已启动");
        this.consumer = consumer;
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "atmkg-jdbc-polling");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::runScheduledPoll, 0, intervalNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public synchronized void stop() {
        if (executor == null) return;
        executor.shutdownNow();
        executor = null;
        consumer = null;
    }

    synchronized void pollOnce(Consumer<ChangeEvent> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        RuntimeException firstFailure = null;
        for (ScopeKey scope : scopes) {
            try {
                pollScope(scope, consumer);
            } catch (RuntimeException ex) {
                if (firstFailure == null) firstFailure = ex;
                else firstFailure.addSuppressed(ex);
            }
        }
        if (firstFailure != null) throw firstFailure;
    }

    synchronized Instant watermark(String sourceId, String objectName) {
        ScopeKey scope = new ScopeKey(sourceId, objectName);
        Instant value = watermarks.get(scope);
        if (value == null) throw new IllegalArgumentException("未配置 polling scope：" + scope.display());
        return value;
    }

    private void pollScope(ScopeKey scope, Consumer<ChangeEvent> eventConsumer) {
        Instant since = watermarks.get(scope);
        SourceAdapter adapter = adapters.get(scope.sourceId());
        List<Discovery> discoveries = new ArrayList<>();
        Instant latest = since;

        Iterator<SourceRecord> iterator = adapter.scanChangedSince(scope.objectName(), since).iterator();
        Throwable failure = null;
        try {
            while (iterator.hasNext()) {
                SourceRecord record = iterator.next();
                validateDiscoveryScope(record, scope);
                Instant timestamp = record.getSourceTimestamp();
                if (timestamp == null || !timestamp.isAfter(since)) {
                    throw new IllegalStateException("JDBC polling sourceTimestamp 必须晚于当前 watermark："
                            + scope.display() + "/" + record.getSourceKey());
                }
                discoveries.add(new Discovery(record.getSourceKey(), timestamp));
                if (timestamp.isAfter(latest)) latest = timestamp;
            }
        } catch (RuntimeException | Error ex) {
            failure = ex;
            throw ex;
        } finally {
            closeIterator(iterator, failure);
        }

        for (Discovery discovery : discoveries) {
            eventConsumer.accept(new ChangeEvent(eventId(scope, discovery), scope.sourceId(), scope.objectName(),
                    discovery.sourceKey(), ChangeEvent.Operation.UPSERT, discovery.timestamp()));
        }
        if (!discoveries.isEmpty()) watermarks.put(scope, latest);
    }

    private static void closeIterator(Iterator<?> iterator, Throwable failure) {
        if (!(iterator instanceof AutoCloseable closeable)) return;
        try {
            closeable.close();
        } catch (Exception closeFailure) {
            if (failure != null) failure.addSuppressed(closeFailure);
            else throw new IllegalStateException("JDBC polling iterator 关闭失败", closeFailure);
        }
    }

    private void runScheduledPoll() {
        Consumer<ChangeEvent> activeConsumer;
        synchronized (this) {
            activeConsumer = consumer;
        }
        if (activeConsumer == null) return;
        try {
            pollOnce(activeConsumer);
        } catch (RuntimeException ex) {
            System.err.println("JDBC polling 本轮失败，watermark 未推进：" + ex.getMessage());
        }
    }

    private static void validateDiscoveryScope(SourceRecord record, ScopeKey expected) {
        if (record == null) throw new IllegalStateException("JDBC polling 返回空 SourceRecord");
        if (!expected.sourceId().equals(record.getSourceId())
                || !expected.objectName().equals(record.getObjectName())) {
            throw new IllegalStateException("JDBC polling 返回越界记录：expected=" + expected.display()
                    + ", actual=" + record.getSourceId() + "/" + record.getObjectName());
        }
    }

    private static String eventId(ScopeKey scope, Discovery discovery) {
        return "jdbc-poll:" + component(scope.sourceId()) + component(scope.objectName())
                + component(discovery.sourceKey()) + discovery.timestamp();
    }

    private static String component(String value) {
        return value.length() + ":" + value + ":";
    }

    public record PollingScope(String sourceId, String objectName, Instant initialWatermark) {
        public PollingScope {
            sourceId = requireText(sourceId, "sourceId");
            objectName = requireText(objectName, "objectName");
            Objects.requireNonNull(initialWatermark, "initialWatermark");
        }
    }

    private record ScopeKey(String sourceId, String objectName) {
        private ScopeKey {
            sourceId = requireText(sourceId, "sourceId");
            objectName = requireText(objectName, "objectName");
        }

        private String display() {
            return sourceId + "/" + objectName;
        }
    }

    private record Discovery(String sourceKey, Instant timestamp) {
        private Discovery {
            sourceKey = requireText(sourceKey, "sourceKey");
            Objects.requireNonNull(timestamp, "timestamp");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        return value;
    }
}
