import forceLayout from 'graphology-layout-force';
import { collectLayout } from 'graphology-layout/utils';
import forceAtlas2 from 'graphology-layout-forceatlas2';
import FA2Layout from 'graphology-layout-forceatlas2/worker';
import noverlap from 'graphology-layout-noverlap';
import Sigma from 'sigma';
import { EdgeArrowProgram, drawDiscNodeHover, drawDiscNodeLabel } from 'sigma/rendering';
import { NodeBorderProgram } from '@sigma/node-border';
import {
  EdgeCurvedArrowProgram,
  createDrawCurvedEdgeLabel,
  indexParallelEdgesIndex
} from '@sigma/edge-curve';
import { fitViewportToNodes } from '@sigma/utils';
import { SigmaD3Physics, sigmaLayoutRadius } from './sigma-d3-physics.js';
import { ViewerAdapter, toAdapterData } from '../core/viewer-adapter.js';
import { INTERACTION_ACTIONS, VIEWER_CONFIG } from '../config/viewer-config.js';
import {
  SIGMA_LAYOUT_POLICIES,
  applyParallelCurvatures,
  chooseSigmaLayout,
  createSigmaGraph,
  getSigmaReducers,
  prepareSigmaReducerState,
} from './sigma-graph.js';
import { createSigmaPerformance } from './sigma-performance.js';

function coordinateSummary(values) {
  if (!values.length) return { count: 0, min: null, p50: null, p90: null, max: null, maxMin: null };
  const sorted = [...values].sort((a, b) => a - b);
  const percentile = fraction => sorted[Math.max(0, Math.ceil(sorted.length * fraction) - 1)];
  const min = Math.min(...values);
  const max = Math.max(...values);
  return {
    count: values.length,
    min,
    p50: percentile(0.5),
    p90: percentile(0.9),
    max,
    maxMin: min === 0 ? null : max / min
  };
}

function distance(left, right) {
  return Math.hypot((right?.x ?? 0) - (left?.x ?? 0), (right?.y ?? 0) - (left?.y ?? 0));
}

function round(value) {
  return Number.isFinite(value) ? Math.round(value * 1_000_000) / 1_000_000 : null;
}

export class SigmaAdapter extends ViewerAdapter {
  renderer = null;
  graph = null;
  captor = null;
  move = null;
  up = null;
  dragging = null;
  snapshot = null;
  viewerConfig;
  callbacks;
  layoutPolicy = 'AUTO';
  layoutSupervisor = null;
  layoutReady = Promise.resolve();
  lastLayout = 'KEEP';
  layoutHistory = [];
  selectedNodeId = null;
  selectedEdgeId = null;
  hoveredNodeId = null;
  hoveredEdgeId = null;
  pathNodes = new Set();
  pathEdges = new Set();
  hiddenNodes = new Set();
  technicalErrors = [];
  lastExpansion = null;
  dragObservation = null;
  performance;
  reducerState = null;
  reducerFns = null;
  dragFrameHandle = null;
  pendingDrag = null;
  fa2Timer = null;
  fa2SuppressionOwned = false;
  suppressScheduledRefresh = false;
  physics;

  constructor(container, onSelect, viewerConfig = VIEWER_CONFIG, callbacks = {}) {
    super(container, onSelect);
    this.viewerConfig = structuredClone(viewerConfig);
    this.callbacks = callbacks;
    this.performance = callbacks.performance ?? createSigmaPerformance(Boolean(callbacks.debug));
    this.physics = new SigmaD3Physics({
      width: container.clientWidth || 1000,
      height: container.clientHeight || 700
    });
  }

  async render(snapshot) {
    await this.destroy();
    const data = toAdapterData(snapshot);
    this.snapshot = data;
    this.selectedNodeId = data.selectedNodeId;
    this.selectedEdgeId = null;
    this.pathEdges = new Set(data.highlightedRelationshipIds);
    this.pathNodes = this.#pathNodes(data.relationships);
    this.performance.measure('sigma.graph.reconcile', () => {
      this.graph = createSigmaGraph(data, {
        width: this.container.clientWidth || 1000,
        height: this.container.clientHeight || 700,
        colorConfig: this.viewerConfig.color,
        nodeSize: this.viewerConfig.node.defaultSize / 3,
        edgeSize: this.viewerConfig.edge.width,
        edgeLabelMode: this.viewerConfig.edge.labelMode
      });
      indexParallelEdgesIndex(this.graph);
      applyParallelCurvatures(this.graph);
    }, { operation: 'render' });
    this.#prepareReducers();
    this.#applyLayout(this.layoutPolicy, { context: 'FULL_QUERY' });
    this.renderer = new Sigma(this.graph, this.container, this.#settings());
    this.#instrumentRenderer();
    this.#bindEvents();
    await this.layoutReady;
    this.#refreshRenderer();
    this.markRuntimeMounted('sigma');
  }

  async applySelection(nodeId) {
    if (!this.#setSelection(nodeId, null)) return;
    this.#scheduleReducerRefresh();
  }

  async applyRelationshipSelection(edgeId) {
    if (edgeId == null) {
      if (!this.#setSelection(this.selectedNodeId, null, { preserveNodeOnClear: true })) return;
    } else if (!this.#setSelection(null, edgeId)) return;
    this.#scheduleReducerRefresh();
  }

  setLabelModes(nodeMode, edgeMode) {
    this.viewerConfig.node.labelMode = nodeMode;
    this.viewerConfig.edge.labelMode = edgeMode;
    this.#prepareReducers();
    this.#scheduleReducerRefresh();
    return structuredClone(this.viewerConfig);
  }

  setLayoutPolicy(policy) {
    if (!SIGMA_LAYOUT_POLICIES.includes(policy)) throw new Error(`unknown Sigma layout policy: ${policy}`);
    this.layoutPolicy = policy;
    return this.#applyLayout(policy, { context: policy === 'AUTO' ? 'USER_POSITIONED' : 'FULL_QUERY', preferKeep: policy === 'AUTO' });
  }

  rebalance() {
    return this.#applyLayout('AUTO', { preferKeep: false });
  }

  pin(nodeId) {
    if (!this.graph?.hasNode(nodeId)) return false;
    this.graph.setNodeAttribute(nodeId, 'fixed', true);
    const attributes = this.graph.getNodeAttributes(nodeId);
    this.physics.pin(nodeId, attributes.x, attributes.y);
    return true;
  }

  unpin(nodeId) {
    if (!this.graph?.hasNode(nodeId)) return false;
    this.graph.setNodeAttribute(nodeId, 'fixed', false);
    this.physics.unpin(nodeId);
    return true;
  }

  async hide(nodeId) {
    if (!this.graph?.hasNode(nodeId)) return false;
    this.hiddenNodes.add(nodeId);
    this.#prepareReducers();
    this.#scheduleReducerRefresh();
    return true;
  }

  async fit() {
    if (this.renderer && this.graph?.order) {
      this.performance.count('cameraOperations');
      await this.performance.measure('sigma.camera.fit', () => fitViewportToNodes(this.renderer, this.graph.nodes(), { animate: true }));
    }
  }

  async fitSelectedNeighborhood() {
    if (!this.renderer || !this.selectedNodeId) return;
    this.performance.count('cameraOperations');
    await this.performance.measure('sigma.camera.fit', () => fitViewportToNodes(this.renderer, [this.selectedNodeId, ...this.graph.neighbors(this.selectedNodeId)], { animate: true }));
  }

  async addGraphDto(graphDto, anchorId) {
    if (!this.graph || !this.renderer) throw new Error('Sigma graph is not ready');
    const before = this.graph.nodes().map(id => [id, {
      x: this.graph.getNodeAttribute(id, 'x'), y: this.graph.getNodeAttribute(id, 'y')
    }]);
    const nodes = new Map(this.snapshot.nodes.map(node => [node.id, node]));
    const edges = new Map(this.snapshot.relationships.map(edge => [edge.id, edge]));
    for (const node of graphDto.nodes ?? []) nodes.set(node.id, node);
    for (const edge of graphDto.relationships ?? []) edges.set(edge.id, edge);
    const incomingNodes = (graphDto.nodes ?? []).filter(node => !this.graph.hasNode(node.id));
    const incomingEdges = (graphDto.relationships ?? []).filter(edge => !this.graph.hasEdge(edge.id));
    const anchor = this.graph.getNodeAttributes(anchorId);
    const positions = new Map(before);
    const anchorWasFixed = this.graph.getNodeAttribute(anchorId, 'fixed') === true;
    this.suppressScheduledRefresh = true;
    try {
      this.performance.measure('sigma.graph.reconcile', () => {
        const rebuilt = createSigmaGraph({ schemaVersion: this.snapshot.schemaVersion,
          nodes: [...nodes.values()], relationships: [...edges.values()], meta: this.snapshot.meta }, {
          positions, colorConfig: this.viewerConfig.color,
          nodeSize: this.viewerConfig.node.defaultSize / 3, edgeSize: this.viewerConfig.edge.width,
          edgeLabelMode: this.viewerConfig.edge.labelMode
        });
        for (const node of incomingNodes) this.graph.addNode(node.id, rebuilt.getNodeAttributes(node.id));
        for (const edge of incomingEdges) this.graph.addDirectedEdgeWithKey(
          edge.id, edge.source, edge.target, rebuilt.getEdgeAttributes(edge.id));
        indexParallelEdgesIndex(this.graph);
        applyParallelCurvatures(this.graph);
      }, { operation: 'expand' });
      this.snapshot = { ...this.snapshot, nodes: [...nodes.values()], relationships: [...edges.values()] };
      this.graph.setNodeAttribute(anchorId, 'fixed', true);
      this.physics.pin(anchorId, anchor.x, anchor.y);
      this.#prepareReducers();
      this.#applyLayout('D3_TOPOLOGY_FORCE', { context: 'INCREMENTAL_EXPAND', anchorId });
      await this.layoutReady;
      this.graph.setNodeAttribute(anchorId, 'fixed', anchorWasFixed);
      if (!anchorWasFixed) this.physics.unpin(anchorId);
      this.physics.writeGraphPositions(this.graph);
      this.#prepareReducers();
    } finally {
      this.suppressScheduledRefresh = false;
    }
    this.#refreshRenderer();
    const moved = before.map(([id, point]) => Math.hypot(
      this.graph.getNodeAttribute(id, 'x') - point.x, this.graph.getNodeAttribute(id, 'y') - point.y));
    this.lastExpansion = {
      addedNodes: incomingNodes.length, addedEdges: incomingEdges.length,
      oldCoordinatesPreserved: before.every(([id]) => this.graph.hasNode(id)),
      meanOldDisplacement: moved.length ? moved.reduce((sum, value) => sum + value, 0) / moved.length : 0,
      maxOldDisplacement: moved.length ? Math.max(...moved) : 0,
      anchorId,
      anchorDisplacement: Math.hypot(
        this.graph.getNodeAttribute(anchorId, 'x') - anchor.x,
        this.graph.getNodeAttribute(anchorId, 'y') - anchor.y
      ),
      graphNodes: this.graph.order, graphEdges: this.graph.size
    };
    return this.lastExpansion;
  }

  async removeToSnapshot(snapshot) {
    if (!this.graph || !this.renderer) throw new Error('Sigma graph is not ready');
    const beforeNodes = this.graph.order;
    const beforeEdges = this.graph.size;
    const nodeIds = new Set(snapshot.nodes.map(node => node.id));
    const edgeIds = new Set(snapshot.relationships.map(edge => edge.id));
    this.suppressScheduledRefresh = true;
    try {
      this.graph.edges().filter(id => !edgeIds.has(id)).forEach(id => this.graph.dropEdge(id));
      this.graph.nodes().filter(id => !nodeIds.has(id)).forEach(id => this.graph.dropNode(id));
      indexParallelEdgesIndex(this.graph); applyParallelCurvatures(this.graph);
      this.snapshot = toAdapterData(snapshot);
      this.#prepareReducers();
    } finally {
      this.suppressScheduledRefresh = false;
    }
    this.#refreshRenderer();
    return { removedNodes: beforeNodes - this.graph.order, removedEdges: beforeEdges - this.graph.size };
  }

  diagnostics() {
    return {
      engine: 'sigma-v3', graphNodes: this.graph?.order ?? 0, graphEdges: this.graph?.size ?? 0,
      layoutPolicy: this.layoutPolicy, lastLayout: this.lastLayout, layoutHistory: [...this.layoutHistory],
      fa2WorkerActive: Boolean(this.layoutSupervisor?.isRunning?.()),
      fa2WorkerStarted: this.layoutHistory.includes('FORCE_ATLAS2_WORKER'),
      noverlapApplied: this.layoutHistory.includes('NOVERLAP'),
      plugins: { nodeBorder: true, edgeCurve: true, sigmaUtils: true },
      enableEdgeEvents: this.renderer?.getSetting('enableEdgeEvents') ?? true,
      selectedNodeId: this.selectedNodeId, selectedEdgeId: this.selectedEdgeId,
      dragObservation: this.dragObservation, lastExpansion: this.lastExpansion,
      physics: this.physics?.diagnostics(this.graph),
      performance: this.performance.snapshot(),
      technicalErrors: [...this.technicalErrors], viewerConfig: structuredClone(this.viewerConfig),
      graphologyLayoutCoordinates: this.graph ? collectLayout(this.graph) : {}
      , runtime: this.runtimeDiagnostics()
    };
  }

  coordinateDiagnostics() {
    if (!this.graph || !this.renderer) return null;
    const physicsById = new Map((this.physics.coordinateSnapshot?.() ?? []).map(item => [item.id, item]));
    const graphologyById = new Map();
    const displayById = new Map();
    const viewportById = new Map();
    const displayViewportById = new Map();
    const nodes = this.graph.nodes().map(id => {
      const attributes = this.graph.getNodeAttributes(id);
      const display = this.renderer.getNodeDisplayData(id);
      const viewport = this.renderer.graphToViewport({ x: attributes.x, y: attributes.y });
      const displayViewport = display
        ? this.renderer.framedGraphToViewport({ x: display.x, y: display.y }) : null;
      const graphology = { x: attributes.x, y: attributes.y };
      graphologyById.set(id, graphology);
      if (display) displayById.set(id, { x: display.x, y: display.y });
      viewportById.set(id, viewport);
      if (displayViewport) displayViewportById.set(id, displayViewport);
      const physics = physicsById.get(id);
      return {
        id,
        physicsX: round(physics?.x), physicsY: round(physics?.y),
        graphologyX: round(graphology.x), graphologyY: round(graphology.y),
        displayX: round(display?.x), displayY: round(display?.y),
        viewportX: round(viewport.x), viewportY: round(viewport.y),
        displayViewportX: round(displayViewport?.x), displayViewportY: round(displayViewport?.y),
        fixed: Boolean(physics?.fixed ?? attributes.fixed === true),
        fx: round(physics?.fx), fy: round(physics?.fy),
        displayAvailable: Boolean(display)
      };
    });
    const layerLengths = { physics: [], graphology: [], display: [], viewport: [], displayViewport: [] };
    this.graph.forEachEdge((edge, attributes, source, target) => {
      const physicsSource = physicsById.get(source);
      const physicsTarget = physicsById.get(target);
      if (physicsSource && physicsTarget) layerLengths.physics.push(distance(physicsSource, physicsTarget));
      const graphologySource = graphologyById.get(source);
      const graphologyTarget = graphologyById.get(target);
      if (graphologySource && graphologyTarget) layerLengths.graphology.push(distance(graphologySource, graphologyTarget));
      const displaySource = displayById.get(source);
      const displayTarget = displayById.get(target);
      if (displaySource && displayTarget) layerLengths.display.push(distance(displaySource, displayTarget));
      const viewportSource = viewportById.get(source);
      const viewportTarget = viewportById.get(target);
      if (viewportSource && viewportTarget) layerLengths.viewport.push(distance(viewportSource, viewportTarget));
      const displayViewportSource = displayViewportById.get(source);
      const displayViewportTarget = displayViewportById.get(target);
      if (displayViewportSource && displayViewportTarget) layerLengths.displayViewport.push(distance(displayViewportSource, displayViewportTarget));
    });
    const settings = this.renderer.getSettings();
    return {
      nodes,
      edgeRatios: Object.fromEntries(Object.entries(layerLengths).map(([layer, values]) => [layer, coordinateSummary(values)])),
      state: {
        autoRescale: settings.autoRescale,
        autoCenter: settings.autoCenter,
        customBBox: this.renderer.getCustomBBox?.() ?? null,
        camera: this.renderer.getCamera().getState(),
        graphDimensions: this.renderer.getGraphDimensions(),
        bbox: this.renderer.getBBox(),
        graphToViewportRatio: this.renderer.getGraphToViewportRatio(),
        fixedNodeCount: nodes.filter(node => node.fixed).length
      }
    };
  }

  async destroy() {
    if (this.dragFrameHandle != null) cancelAnimationFrame(this.dragFrameHandle);
    this.dragFrameHandle = null; this.pendingDrag = null; this.dragging = null;
    if (this.fa2Timer != null) clearTimeout(this.fa2Timer);
    this.fa2Timer = null;
    this.fa2SuppressionOwned = false;
    this.suppressScheduledRefresh = false;
    if (this.layoutSupervisor) {
      this.performance.count('fa2WorkerKill');
      this.layoutSupervisor.kill?.();
    }
    this.layoutSupervisor = null;
    if (this.captor && this.move) this.captor.off('mousemovebody', this.move);
    if (this.captor && this.up) this.captor.off('mouseup', this.up);
    this.renderer?.kill(); this.renderer = null; this.graph = null; this.captor = null;
    this.physics?.reset?.();
    this.reducerState = null; this.reducerFns = null;
    this.container.replaceChildren();
    this.markRuntimeDestroyed();
  }

  #settings() {
    return {
      renderEdgeLabels: true, enableEdgeEvents: true, zIndex: true,
      labelFont: 'Inter, Segoe UI, Microsoft YaHei, sans-serif', labelSize: this.viewerConfig.node.fontSize + 2,
      labelWeight: String(this.viewerConfig.node.fontWeight), edgeLabelFont: 'Inter, Segoe UI, Microsoft YaHei, sans-serif',
      edgeLabelSize: this.viewerConfig.edge.fontSize, edgeLabelWeight: '400', labelDensity: 0.12,
      labelGridCellSize: 100, labelRenderedSizeThreshold: 5, defaultNodeType: 'border', defaultEdgeType: 'arrow',
      nodeProgramClasses: { border: NodeBorderProgram }, nodeHoverProgramClasses: { border: NodeBorderProgram },
      edgeProgramClasses: { arrow: EdgeArrowProgram, 'curved-arrow': EdgeCurvedArrowProgram },
      defaultDrawNodeLabel: (context, data, settings) => this.#drawNodeLabel(context, data, settings),
      defaultDrawNodeHover: drawDiscNodeHover,
      defaultDrawEdgeLabel: createDrawCurvedEdgeLabel({ curvatureAttribute: 'curvature', defaultCurvature: 0.25, keepLabelUpright: true }),
      nodeReducer: (node, data) => {
        this.performance.count('nodeReducer');
        return this.#reducers().nodeReducer(node, data);
      },
      edgeReducer: (edge, data) => {
        this.performance.count('edgeReducer');
        return this.#reducers().edgeReducer(edge, data);
      }
    };
  }

  #reducers() {
    if (!this.reducerFns) this.#prepareReducers();
    return this.reducerFns;
  }

  #prepareReducers() {
    if (!this.graph) return;
    this.performance.count('reducerPrepare');
    this.performance.measure('sigma.reducer.prepareState', () => {
      const next = prepareSigmaReducerState(this.graph, {
        selectedNodeId: this.selectedNodeId, hoveredNodeId: this.hoveredNodeId,
        selectedEdgeId: this.selectedEdgeId, hoveredEdgeId: this.hoveredEdgeId,
        pathNodes: this.pathNodes, pathEdges: this.pathEdges,
        hiddenNodes: this.hiddenNodes,
        nodeLabelMode: this.viewerConfig.node.labelMode, edgeLabelMode: this.viewerConfig.edge.labelMode
      });
      if (!this.reducerState) this.reducerState = next;
      else Object.assign(this.reducerState, next);
      if (!this.reducerFns) this.reducerFns = getSigmaReducers(this.graph, { state: this.reducerState });
    }, { nodes: this.graph.order, edges: this.graph.size });
  }

  #scheduleReducerRefresh(options) {
    if (!this.renderer) return;
    if (typeof this.renderer.scheduleRefresh === 'function') return this.renderer.scheduleRefresh(options);
    return this.#refreshRenderer(options);
  }

  #setSelection(nodeId, edgeId, { preserveNodeOnClear = false } = {}) {
    const nextNode = edgeId == null && preserveNodeOnClear ? this.selectedNodeId : nodeId;
    if (this.selectedNodeId === nextNode && this.selectedEdgeId === edgeId) return false;
    this.selectedNodeId = nextNode;
    this.selectedEdgeId = edgeId;
    this.#prepareReducers();
    return true;
  }

  #applyHover(nodeId, edgeId) {
    if (this.hoveredNodeId === nodeId && this.hoveredEdgeId === edgeId) return false;
    this.hoveredNodeId = nodeId;
    this.hoveredEdgeId = edgeId;
    this.#prepareReducers();
    return true;
  }

  #refreshReducers() { this.#scheduleReducerRefresh(); }

  #refreshRenderer(options) {
    if (!this.renderer) return;
    return this.renderer.refresh(options);
  }

  #instrumentRenderer() {
    if (!this.renderer) return;
    const renderer = this.renderer;
    const refresh = renderer.refresh.bind(renderer);
    renderer.refresh = options => {
      if (this.suppressScheduledRefresh && options?.schedule) {
        this.performance.count('rendererRefreshSuppressed');
        return renderer;
      }
      this.performance.count('rendererRefresh');
      if (options?.schedule) this.performance.count('rendererScheduleRefresh');
      return this.performance.measure('sigma.renderer.refresh', () => refresh(options));
    };
  }

  #drawNodeLabel(context, data, settings) {
    if (!data.label) return;
    if (this.viewerConfig.node.labelMode === 'INSIDE') {
      context.fillStyle = '#ffffff'; context.font = `${settings.labelWeight} ${settings.labelSize}px ${settings.labelFont}`;
      context.textAlign = 'center'; context.textBaseline = 'middle'; context.fillText(data.label, data.x, data.y); return;
    }
    drawDiscNodeLabel(context, data, settings);
  }

  #pathNodes(relationships) {
    const result = new Set();
    for (const edge of relationships) if (this.pathEdges.has(edge.id)) { result.add(edge.source); result.add(edge.target); }
    return result;
  }

  #applyLayout(policy, { maxIterations = 50, preferKeep = false, context = 'FULL_QUERY', anchorId = null } = {}) {
    if (!this.graph) return this.layoutReady;
    if (this.fa2Timer != null) {
      clearTimeout(this.fa2Timer);
      this.fa2Timer = null;
      if (this.fa2SuppressionOwned) {
        this.fa2SuppressionOwned = false;
        this.suppressScheduledRefresh = false;
      }
    }
    if (this.layoutSupervisor) {
      this.performance.count('fa2WorkerKill');
      this.layoutSupervisor.kill?.();
      this.layoutSupervisor = null;
    }
    this.performance.count('layoutPolicy');
    const effective = chooseSigmaLayout(policy, this.graph, { context, preferKeep }); this.lastLayout = effective; this.layoutHistory.push(effective);
    if (effective === 'KEEP') { this.layoutReady = Promise.resolve(); return this.layoutReady; }
    if (effective === 'D3_TOPOLOGY_FORCE') {
      const ownsSuppression = Boolean(this.renderer && !this.suppressScheduledRefresh);
      if (ownsSuppression) this.suppressScheduledRefresh = true;
      try {
        this.performance.count('d3Force');
        this.performance.measure('sigma.layout.d3', () => {
          this.performance.count('physicsReconcile');
          this.physics.reconcile(this.graph, {
            mode: context === 'INCREMENTAL_EXPAND' ? 'INCREMENTAL_EXPAND' : 'FULL_QUERY', anchorId
          });
          const ticks = this.physics.settle(null, context === 'INCREMENTAL_EXPAND' ? 0.3 : 1);
          this.performance.count('d3Ticks', ticks);
          this.physics.writeGraphPositions(this.graph);
        }, { context });
      } finally {
        if (ownsSuppression) { this.suppressScheduledRefresh = false; this.#refreshRenderer(); }
      }
      this.layoutReady = Promise.resolve(); return this.layoutReady;
    }
    if (effective === 'GRAPHOLOGY_FORCE' || effective === 'FORCE') {
      const ownsSuppression = Boolean(this.renderer && !this.suppressScheduledRefresh);
      if (ownsSuppression) this.suppressScheduledRefresh = true;
      try {
      this.performance.count('force');
      this.performance.count('forceIterations', maxIterations);
      this.performance.measure('sigma.layout.force', () => forceLayout.assign(this.graph, {
        maxIterations, isNodeFixed: 'fixed', settings: { gravity: 0.0001, inertia: 0.6 }
      }), { maxIterations });
        this.#applyNoverlap();
      } finally {
        if (ownsSuppression) {
          this.suppressScheduledRefresh = false;
          this.#refreshRenderer();
        }
      }
      this.layoutReady = Promise.resolve(); return this.layoutReady;
    }
    if (effective === 'NOVERLAP') {
      const ownsSuppression = Boolean(this.renderer && !this.suppressScheduledRefresh);
      if (ownsSuppression) this.suppressScheduledRefresh = true;
      try { this.#applyNoverlap(); }
      finally {
        if (ownsSuppression) { this.suppressScheduledRefresh = false; this.#refreshRenderer(); }
      }
      this.layoutReady = Promise.resolve(); return this.layoutReady;
    }
    this.layoutHistory.push('FORCE_ATLAS2_WORKER');
    this.performance.count('fa2WorkerCreate');
    this.layoutSupervisor = new FA2Layout(this.graph, { settings: { ...forceAtlas2.inferSettings(this.graph), barnesHutOptimize: this.graph.order > 500 } });
    this.performance.count('fa2WorkerStart');
    const fa2Measure = this.performance.start('sigma.layout.fa2', { worker: true });
    const ownsSuppression = Boolean(this.renderer && !this.suppressScheduledRefresh);
    if (ownsSuppression) { this.suppressScheduledRefresh = true; this.fa2SuppressionOwned = true; }
    this.layoutSupervisor.start();
    this.layoutReady = new Promise(resolve => { this.fa2Timer = setTimeout(() => {
      this.fa2Timer = null;
      if (this.layoutSupervisor) {
        this.performance.count('fa2WorkerStop');
        this.layoutSupervisor.stop?.();
      }
      fa2Measure.end(); this.#applyNoverlap();
      if (ownsSuppression) { this.fa2SuppressionOwned = false; this.suppressScheduledRefresh = false; this.#refreshRenderer(); }
      resolve();
    }, 450); });
    return this.layoutReady;
  }

  #applyNoverlap() {
    this.performance.count('noverlap');
    this.performance.count('noverlapIterations', 80);
    this.performance.measure('sigma.layout.noverlap', () => noverlap.assign(this.graph, { maxIterations: 80,
      inputReducer: (key, attributes) => ({ x: attributes.x, y: attributes.y, size: sigmaLayoutRadius(attributes.size) }),
      settings: { margin: 4, ratio: 1, speed: 3 } }), { maxIterations: 80 });
    this.layoutHistory.push('NOVERLAP');
  }

  #bindEvents() {
    this.renderer.on('clickNode', ({ node }) => {
      const changed = this.#setSelection(node, null);
      this.performance.count('selectionNotifications');
      this.callbacks.onRelationshipSelect?.(null);
      this.onSelect?.(node);
      if (changed) this.#scheduleReducerRefresh();
    });
    this.renderer.on('clickEdge', ({ edge }) => {
      const changed = this.#setSelection(null, edge);
      this.performance.count('selectionNotifications');
      this.callbacks.onRelationshipSelect?.(edge);
      if (changed) this.#scheduleReducerRefresh();
    });
    this.renderer.on('enterNode', ({ node }) => { if (this.#applyHover(node, this.hoveredEdgeId)) this.#scheduleReducerRefresh(); });
    this.renderer.on('leaveNode', ({ node }) => {
      if (this.hoveredNodeId === node && this.#applyHover(null, this.hoveredEdgeId)) this.#scheduleReducerRefresh();
    });
    this.renderer.on('enterEdge', ({ edge }) => { if (this.#applyHover(this.hoveredNodeId, edge)) this.#scheduleReducerRefresh(); });
    this.renderer.on('leaveEdge', ({ edge }) => {
      if (this.hoveredEdgeId === edge && this.#applyHover(this.hoveredNodeId, null)) this.#scheduleReducerRefresh();
    });
    this.renderer.on('rightClickEdge', ({ edge }) => this.callbacks.onRelationshipSelect?.(edge));
    this.renderer.on('clickStage', () => {
      const changed = this.#setSelection(null, null);
      this.performance.count('selectionNotifications');
      this.callbacks.onRelationshipSelect?.(null); this.onSelect?.(null);
      if (changed) this.#scheduleReducerRefresh();
    });
    this.renderer.on('downNode', ({ node, event }) => {
      if (this.viewerConfig.interaction.drag !== INTERACTION_ACTIONS.MOVE_WITH_FORCE) return;
      this.dragging = node; this.selectedNodeId = node;
      const attributes = this.graph.getNodeAttributes(node);
      this.physics.pin(node, attributes.x, attributes.y);
      this.performance.count('cameraOperations');
      this.renderer.getCamera().disable();
      event.preventSigmaDefault(); event.original.preventDefault();
      this.dragObservation = { id: node, before: { x: this.graph.getNodeAttribute(node, 'x'), y: this.graph.getNodeAttribute(node, 'y') } };
    });
    this.#enableDragging();
  }

  #enableDragging() {
    this.move = event => {
      if (!this.dragging) return;
      this.performance.count('mousemoveEvents');
      const point = this.renderer.viewportToGraph({ x: event.x, y: event.y });
      this.pendingDrag = { id: this.dragging, x: point.x, y: point.y };
      event.preventSigmaDefault();
      if (this.dragFrameHandle == null) {
        this.dragFrameHandle = requestAnimationFrame(() => {
          this.dragFrameHandle = null;
          this.#flushDragFrame();
        });
      }
    };
    this.up = () => {
      if (!this.dragging) return;
      if (this.dragFrameHandle != null) {
        cancelAnimationFrame(this.dragFrameHandle);
        this.dragFrameHandle = null;
      }
      this.#flushDragFrame();
      const id = this.dragging; const after = this.graph.getNodeAttributes(id);
      this.dragObservation.after = { x: after.x, y: after.y };
      this.dragObservation.pinnedAfterDrag = this.viewerConfig.interaction.dragEnd === INTERACTION_ACTIONS.PIN;
      if (this.viewerConfig.interaction.dragEnd === INTERACTION_ACTIONS.PIN) {
        this.graph.setNodeAttribute(id, 'fixed', true);
        this.physics.pin(id, after.x, after.y);
      } else {
        this.graph.setNodeAttribute(id, 'fixed', false);
        this.physics.unpin(id);
      }
      this.dragging = null;
      this.performance.count('cameraOperations');
      this.renderer.getCamera().enable();
    };
    this.captor = this.renderer.getMouseCaptor(); this.captor.on('mousemovebody', this.move); this.captor.on('mouseup', this.up);
  }

  #flushDragFrame() {
    const pending = this.pendingDrag;
    if (!pending || !this.graph?.hasNode(pending.id)) return;
    this.pendingDrag = null;
    const frame = this.performance.start('sigma.drag.frame', { node: pending.id });
    this.physics.updatePosition(pending.id, pending.x, pending.y, { fixed: true });
    this.graph.updateNode(pending.id, attributes => ({ ...attributes, x: pending.x, y: pending.y }));
    this.performance.count('dragFrames');
    frame.end();
  }
}
