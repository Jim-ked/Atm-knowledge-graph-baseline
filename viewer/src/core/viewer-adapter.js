export function toAdapterData(snapshot) {
  return structuredClone({
    nodes: snapshot.nodes,
    relationships: snapshot.relationships,
    selectedNodeId: snapshot.state.selectedNodeId,
    expandedNodeIds: snapshot.state.expandedNodeIds,
    highlightedRelationshipIds: snapshot.state.highlightedRelationshipIds
  });
}

export class ViewerAdapter {
  static nextRuntimeId = 1;

  constructor(container, onSelect) {
    this.container = container;
    this.onSelect = onSelect;
    this.runtimeId = ViewerAdapter.nextRuntimeId++;
    this.runtimeKind = 'unknown';
    this.runtimeMounted = false;
    this.runtimeDestroyed = false;
    this.destroyCount = 0;
  }

  markRuntimeMounted(kind) {
    this.runtimeKind = kind;
    this.runtimeMounted = true;
    this.runtimeDestroyed = false;
  }

  markRuntimeDestroyed() {
    this.runtimeMounted = false;
    this.runtimeDestroyed = true;
    this.destroyCount += 1;
  }

  runtimeDiagnostics() {
    return {
      runtimeId: this.runtimeId,
      runtimeKind: this.runtimeKind,
      mounted: this.runtimeMounted,
      destroyed: this.runtimeDestroyed,
      destroyCount: this.destroyCount,
      containerChildCount: this.container?.childElementCount ?? 0,
      resources: {
        graphAlive: Boolean(this.graph && !this.graph.destroyed),
        cyAlive: Boolean(this.cy),
        rendererAlive: Boolean(this.renderer),
        layoutSupervisorAlive: Boolean(this.layoutSupervisor),
        fa2TimerActive: this.fa2Timer != null,
        drawFrameActive: this.drawFrameHandle != null,
        expansionTimerActive: this.expansionTimer != null,
        dragFrameActive: this.dragFrameHandle != null,
        physicsStopped: this.physics?.stopped ?? null
      }
    };
  }

  async render() { throw new Error('render must be implemented'); }
  async fit() {}
  async destroy() {}
}
