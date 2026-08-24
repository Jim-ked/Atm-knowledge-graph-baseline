package org.atmkg.service.sync;

import java.util.Objects;
import org.atmkg.core.spi.SyncService;
import org.atmkg.core.spi.TriggerAdapter;

/**
 * 只在 polling 启停、重复 start/close 或失败清理有缺陷时改这里。调整 interval/scope 去
 * {@code config/sync.yaml}，新增表去 {@code config/sources.yaml}，人工同步动作由 SyncControlMain 负责。
 *
 * <p>本类只把 TriggerAdapter 事件交给 {@code syncService::handle}，并保证 close 时 stop。不要在这里实现
 * initial full rebuild；checkpoint 由 JdbcPollingTriggerAdapter/PollingCheckpointStore 负责。服务退出后
 * polling 仍运行时，先查 KgServiceMain 的 close 顺序、本类 pollingStarted，再查 TriggerAdapter.stop。
 */
public final class SyncRuntime implements AutoCloseable {
    private final SyncService syncService;
    private final TriggerAdapter pollingTrigger;
    private boolean started;
    private boolean pollingStarted;
    private boolean closed;

    private SyncRuntime(SyncService syncService, TriggerAdapter pollingTrigger) {
        this.syncService = syncService;
        this.pollingTrigger = pollingTrigger;
    }

    public static SyncRuntime disabled() {
        return new SyncRuntime(null, null);
    }

    public static SyncRuntime enabled(SyncService syncService) {
        return new SyncRuntime(Objects.requireNonNull(syncService, "syncService"), null);
    }

    public static SyncRuntime enabled(SyncService syncService, TriggerAdapter pollingTrigger) {
        return new SyncRuntime(Objects.requireNonNull(syncService, "syncService"),
                Objects.requireNonNull(pollingTrigger, "pollingTrigger"));
    }

    public synchronized void start() {
        if (closed) throw new IllegalStateException("同步运行时已关闭");
        if (started) return;
        if (pollingTrigger != null) {
            try {
                pollingTrigger.start(syncService::handle);
                pollingStarted = true;
            } catch (RuntimeException ex) {
                pollingTrigger.stop();
                throw ex;
            }
        }
        started = true;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        if (pollingStarted) pollingTrigger.stop();
        pollingStarted = false;
        started = false;
        closed = true;
    }

    public boolean isEnabled() { return syncService != null; }
    public boolean isPollingEnabled() { return pollingTrigger != null; }
}
