package org.atmkg.service.sync;

import java.util.Objects;
import org.atmkg.core.spi.SyncService;
import org.atmkg.core.spi.TriggerAdapter;

/** Owns the optional synchronization lifecycle without changing the Core synchronization contracts. */
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
