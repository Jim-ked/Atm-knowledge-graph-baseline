package org.atmkg.service.sync;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.atmkg.core.error.SyncException;
import org.atmkg.core.model.ChangeEvent;
import org.atmkg.core.model.MappingResult;
import org.atmkg.core.model.SourceRecord;
import org.atmkg.core.model.SourceRef;
import org.atmkg.core.model.SourceScope;
import org.atmkg.core.spi.GraphStore;
import org.atmkg.core.spi.MappingEngine;
import org.atmkg.core.spi.SourceAdapter;
import org.atmkg.core.spi.SyncService;

/**
 * Domain-neutral synchronization coordinator.
 * Change events identify records only; current authoritative facts are always re-read through SourceAdapter.
 */
public final class DefaultSyncService implements SyncService {
    private final Map<String, SourceAdapter> adapters;
    private final MappingEngine mappingEngine;
    private final GraphStore graphStore;
    private final Consumer<GraphChangeNotice> noticeListener;
    private final Set<String> processedEventIds = new HashSet<>();

    public DefaultSyncService(Map<String, SourceAdapter> adapters, MappingEngine mappingEngine, GraphStore graphStore) {
        this(adapters, mappingEngine, graphStore, notice -> {});
    }

    public DefaultSyncService(Map<String, SourceAdapter> adapters, MappingEngine mappingEngine, GraphStore graphStore,
                              Consumer<GraphChangeNotice> noticeListener) {
        Objects.requireNonNull(adapters, "adapters");
        this.adapters = Map.copyOf(new LinkedHashMap<>(adapters));
        this.mappingEngine = Objects.requireNonNull(mappingEngine, "mappingEngine");
        this.graphStore = Objects.requireNonNull(graphStore, "graphStore");
        this.noticeListener = Objects.requireNonNull(noticeListener, "noticeListener");
    }

    @Override
    public synchronized void handle(ChangeEvent event) {
        Objects.requireNonNull(event, "event");
        if (processedEventIds.contains(event.getEventId())) return;
        SourceRef ref = new SourceRef(event.getSourceId(), event.getObjectName(), event.getSourceKey());
        if (event.getOperation() == ChangeEvent.Operation.DELETE) {
            graphStore.deleteProjection(ref);
            noticeListener.accept(GraphChangeNotice.forDelete(ref, Instant.now()));
        } else {
            Optional<MappingResult> result = resyncProjection(
                    event.getSourceId(), event.getObjectName(), event.getSourceKey());
            result.ifPresent(mapped -> noticeListener.accept(
                    GraphChangeNotice.forUpsert(ref, mapped, Instant.now())));
        }
        processedEventIds.add(event.getEventId());
    }

    @Override
    public void fullSync(String sourceId, String objectName) {
        SourceAdapter adapter = adapter(sourceId);
        writeEntityEndpoints(adapter, sourceId, objectName);
        replaceCurrentRecords(adapter, sourceId, objectName);
    }

    @Override
    public void fullRebuild(Collection<SourceScope> scopes) {
        Objects.requireNonNull(scopes, "scopes");
        if (scopes.isEmpty()) throw new IllegalArgumentException("fullRebuild scopes 不能为空");
        graphStore.clearProject();

        // Pass 1 across every source object: create all entity endpoints first.
        for (SourceScope scope : scopes) {
            SourceAdapter adapter = adapter(scope.getSourceId());
            writeEntityEndpoints(adapter, scope.getSourceId(), scope.getObjectName());
        }
        // Pass 2: reconcile the authoritative projection of every current source record.
        for (SourceScope scope : scopes) {
            SourceAdapter adapter = adapter(scope.getSourceId());
            replaceCurrentRecords(adapter, scope.getSourceId(), scope.getObjectName());
        }
    }

    private void writeEntityEndpoints(SourceAdapter adapter, String sourceId, String objectName) {
        for (SourceRecord record : adapter.readAll(objectName)) {
            validateRecordScope(record, sourceId, objectName);
            MappingResult result = map(record);
            graphStore.upsertEntities(result.getEntities());
        }
    }

    private void replaceCurrentRecords(SourceAdapter adapter, String sourceId, String objectName) {
        for (SourceRecord record : adapter.readAll(objectName)) {
            validateRecordScope(record, sourceId, objectName);
            replace(record);
        }
    }

    @Override
    public void compensateSince(String sourceId, String objectName, Instant since) {
        Objects.requireNonNull(since, "since");
        SourceAdapter adapter = adapter(sourceId);
        for (SourceRecord record : adapter.scanChangedSince(objectName, since)) {
            validateRecordScope(record, sourceId, objectName);
            replace(record);
        }
    }

    @Override
    public void resync(String sourceId, String objectName, String sourceKey) {
        resyncProjection(sourceId, objectName, sourceKey);
    }

    private Optional<MappingResult> resyncProjection(String sourceId, String objectName, String sourceKey) {
        SourceAdapter adapter = adapter(sourceId);
        SourceRef ref = new SourceRef(sourceId, objectName, sourceKey);
        Optional<SourceRecord> current = adapter.readByKey(objectName, sourceKey);
        if (current.isEmpty()) {
            // The authoritative source no longer contains this key; the graph projection must not survive.
            graphStore.deleteProjection(ref);
            return Optional.empty();
        }
        validateRecordScope(current.get(), sourceId, objectName);
        if (!current.get().getSourceKey().equals(sourceKey)) {
            throw new SyncException("SourceAdapter 返回错误 sourceKey：expected=" + sourceKey
                    + ", actual=" + current.get().getSourceKey());
        }
        return Optional.of(replace(current.get()));
    }

    private MappingResult replace(SourceRecord record) {
        MappingResult result = map(record);
        graphStore.replaceProjection(SourceRef.from(record), result);
        return result;
    }

    private MappingResult map(SourceRecord record) {
        try {
            return mappingEngine.map(record);
        } catch (RuntimeException ex) {
            throw new SyncException("映射失败：" + record.getSourceId() + "/" + record.getObjectName()
                    + "/" + record.getSourceKey(), ex);
        }
    }

    private SourceAdapter adapter(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) throw new IllegalArgumentException("sourceId 不能为空");
        SourceAdapter adapter = adapters.get(sourceId);
        if (adapter == null) throw new SyncException("未注册 SourceAdapter：" + sourceId);
        return adapter;
    }

    private void validateRecordScope(SourceRecord record, String expectedSourceId, String expectedObjectName) {
        if (!record.getSourceId().equals(expectedSourceId) || !record.getObjectName().equals(expectedObjectName)) {
            throw new SyncException("SourceAdapter 返回越界记录：expected=" + expectedSourceId + "/" + expectedObjectName
                    + ", actual=" + record.getSourceId() + "/" + record.getObjectName());
        }
    }
}
