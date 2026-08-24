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
 * 开启/新增 polling 不改本类：在 {@code config/sources.yaml} 的 JDBC object 增加 watermarkField，
 * 再在 {@code config/sync.yaml} 增加 sourceId/sourceObject/initialWatermark scope。只有轮询调度、
 * watermark 推进或失败重试的通用机制变化才写 Java。
 *
 * <p>本类只发包含稳定 sourceKey 的 ChangeEvent，不能直接写 GraphStore。改成携带行数据会绕过权威回读。
 * 查询仍严格使用 {@code watermark > ?}，但传入“checkpoint - lookback”以允许安全重读；所有事件消费成功且
 * checkpoint 保存成功后，才推进到本轮最大 sourceTimestamp。timestamp polling 仍不能发现 hard DELETE。
 */
public final class JdbcPollingTriggerAdapter implements TriggerAdapter {
    private final Map<String, SourceAdapter> adapters;
    private final List<ScopeKey> scopes;
    private final Map<ScopeKey, Instant> watermarks;
    private final Duration lookback;
    private final PollingCheckpointStore checkpointStore;
    private final long intervalNanos;
    private ScheduledExecutorService executor;
    private Consumer<ChangeEvent> consumer;

    public JdbcPollingTriggerAdapter(Map<String, SourceAdapter> adapters,
                                     Collection<PollingScope> scopes,
                                     Duration interval,
                                     Duration lookback,
                                     PollingCheckpointStore checkpointStore) {
        Objects.requireNonNull(adapters, "adapters");
        Objects.requireNonNull(scopes, "scopes");
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(lookback, "lookback");
        Objects.requireNonNull(checkpointStore, "checkpointStore");
        if (adapters.isEmpty()) throw new IllegalArgumentException("adapters 不能为空");
        if (scopes.isEmpty()) throw new IllegalArgumentException("polling scopes 不能为空");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("polling interval 必须大于 0");
        }
        if (lookback.isNegative()) throw new IllegalArgumentException("polling lookback 不能小于 0");
        this.intervalNanos = interval.toNanos();
        this.lookback = lookback;
        this.checkpointStore = checkpointStore;
        this.adapters = Map.copyOf(new LinkedHashMap<>(adapters));

        List<ScopeKey> parsedScopes = new ArrayList<>();
        Map<ScopeKey, Instant> initialWatermarks = new LinkedHashMap<>();
        for (PollingScope scope : scopes) {
            Objects.requireNonNull(scope, "polling scope");
            ScopeKey key = new ScopeKey(scope.sourceId(), scope.objectName());
            if (!this.adapters.containsKey(key.sourceId())) {
                throw new IllegalArgumentException("polling scope 未注册 SourceAdapter：" + key.sourceId());
            }
            Instant watermark = checkpointStore.load(key.sourceId(), key.objectName())
                    .orElse(scope.initialWatermark());
            if (initialWatermarks.putIfAbsent(key, watermark) != null) {
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
        // 1. 持久化 checkpoint 已在构造时优先于 initialWatermark；本轮从回看起点扫描。
        Instant checkpoint = watermarks.get(scope);
        Instant since = checkpoint.minus(lookback);
        SourceAdapter adapter = adapters.get(scope.sourceId());
        List<Discovery> discoveries = new ArrayList<>();
        Instant latest = checkpoint;

        Iterator<SourceRecord> iterator = adapter.scanChangedSince(scope.objectName(), since).iterator();
        Throwable failure = null;
        try {
            while (iterator.hasNext()) {
                SourceRecord record = iterator.next();
                validateDiscoveryScope(record, scope);
                Instant timestamp = record.getSourceTimestamp();
                if (timestamp == null || !timestamp.isAfter(since)) {
                    throw new IllegalStateException("JDBC polling sourceTimestamp 必须晚于本轮扫描起点："
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

        // 2. ChangeEvent 只携带源记录身份，consumer 成功后才有资格推进水位。
        for (Discovery discovery : discoveries) {
            eventConsumer.accept(new ChangeEvent(eventId(scope, discovery), scope.sourceId(), scope.objectName(),
                    discovery.sourceKey(), ChangeEvent.Operation.UPSERT, discovery.timestamp()));
        }
        // 3. 先原子保存本轮最大源时间，再更新进程内水位；空扫描和纯回看重复都不推进。
        if (latest.isAfter(checkpoint)) {
            checkpointStore.save(scope.sourceId(), scope.objectName(), latest);
            watermarks.put(scope, latest);
        }
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
