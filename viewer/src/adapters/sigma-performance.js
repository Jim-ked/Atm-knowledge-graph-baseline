const COUNTER_NAMES = [
  'graphModelMerge', 'graphModelReplace', 'graphModelApplyPatch',
  'layoutPolicy', 'd3Force', 'd3Ticks', 'physicsReconcile', 'force', 'forceIterations', 'noverlap', 'noverlapIterations',
  'fa2WorkerCreate', 'fa2WorkerStart', 'fa2WorkerStop', 'fa2WorkerKill',
  'rendererRefresh', 'rendererScheduleRefresh', 'rendererRefreshSuppressed', 'cameraOperations',
  'nodeReducer', 'edgeReducer', 'reducerPrepare',
  'mousemoveEvents', 'dragFrames', 'selectionNotifications'
];

function now() {
  return globalThis.performance?.now?.() ?? Date.now();
}

/**
 * Debug-only counters and User Timing measures for the Sigma diagnosis.
 * This deliberately has no GraphDTO or rendering semantics.
 */
export class SigmaPerformance {
  enabled;
  counters;
  measures = [];
  operations = [];
  #sequence = 0;

  constructor(enabled = false) {
    this.enabled = Boolean(enabled);
    this.reset();
  }

  reset() {
    this.counters = Object.fromEntries(COUNTER_NAMES.map(name => [name, 0]));
    this.measures = [];
    this.operations = [];
  }

  count(name, amount = 1) {
    if (!this.enabled) return;
    this.counters[name] = (this.counters[name] ?? 0) + amount;
  }

  measure(name, fn, detail = {}) {
    if (!this.enabled) return fn();
    const token = this.start(name, detail);
    try {
      const result = fn();
      if (result && typeof result.then === 'function') {
        return result.then(value => { token.end(); return value; }, error => { token.end({ error: true }); throw error; });
      }
      token.end();
      return result;
    } catch (error) {
      token.end({ error: true });
      throw error;
    }
  }

  start(name, detail = {}) {
    if (!this.enabled) return { end() {} };
    const id = ++this.#sequence;
    const markStart = `${name}:start:${id}`;
    const markEnd = `${name}:end:${id}`;
    globalThis.performance?.mark?.(markStart);
    const started = now();
    let ended = false;
    return {
      end: (extra = {}) => {
        if (ended) return;
        ended = true;
        const finished = now();
        globalThis.performance?.mark?.(markEnd);
        try { globalThis.performance?.measure?.(name, markStart, markEnd); } catch { /* duplicate/unsupported User Timing */ }
        this.measures.push({ name, duration: finished - started, startTime: started, ...detail, ...extra });
      }
    };
  }

  operation(name, fn, detail = {}) {
    if (!this.enabled) return fn();
    const started = now();
    const result = fn();
    const finish = (extra = {}) => this.operations.push({ name, duration: now() - started, ...detail, ...extra });
    if (result && typeof result.then === 'function') return result.then(value => { finish(); return value; }, error => { finish({ error: true }); throw error; });
    finish();
    return result;
  }

  snapshot() {
    return {
      enabled: this.enabled,
      counters: { ...this.counters },
      measures: this.measures.map(item => ({ ...item })),
      operations: this.operations.map(item => ({ ...item }))
    };
  }

  delta(before) {
    const current = this.snapshot();
    const counters = {};
    for (const [name, value] of Object.entries(current.counters)) counters[name] = value - (before?.counters?.[name] ?? 0);
    return { counters, measures: current.measures.slice(before?.measures?.length ?? 0), operations: current.operations.slice(before?.operations?.length ?? 0) };
  }
}

export function createSigmaPerformance(enabled = false) {
  return new SigmaPerformance(enabled);
}
