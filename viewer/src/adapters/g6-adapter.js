import { CanvasEvent, EdgeEvent, Graph, NodeEvent } from '@antv/g6';
import { ViewerAdapter, toAdapterData } from '../core/viewer-adapter.js';
import { buildGraphElementStates } from '../core/graph-selection.js';
import {
  G6_VISUAL_PRESETS,
  createG6PocOptions,
  normalizeG6PocConfig,
  shortCaption
} from './g6-poc-config.js';
import {
  G6_EDGE_GEOMETRY,
  annotateRelationshipGeometry,
  relationshipCaptionForLength,
  uniqueShortCaptions
} from './g6-visual-geometry.js';
import {
  BROWSER_REFERENCE_FORCE_CONFIG,
  PersistentForceSimulation,
  RESTART_PRESETS
} from './g6-persistent-simulation.js';
import {
  INTERACTION_ACTIONS,
  VIEWER_CONFIG,
  resolveEdgeLabel,
  resolveNodeLabel,
  stableNodeColor,
  withLabelModes
} from '../config/viewer-config.js';

const MOTION_FIELDS = ['x', 'y', 'vx', 'vy', 'fx', 'fy'];

function withDegrees(data) {
  const degree = new Map(data.nodes.map(node => [node.id, 0]));
  for (const edge of data.relationships) {
    degree.set(edge.source, (degree.get(edge.source) ?? 0) + 1);
    degree.set(edge.target, (degree.get(edge.target) ?? 0) + 1);
  }
  return data.nodes.map(node => ({ ...node, degree: degree.get(node.id) ?? 0 }));
}

function displacement(before, after) {
  const distances = [];
  for (const [id, point] of before) {
    const next = after.get(id);
    if (next) distances.push(Math.hypot(next[0] - point[0], next[1] - point[1]));
  }
  return {
    mean: distances.length ? distances.reduce((sum, value) => sum + value, 0) / distances.length : 0,
    max: distances.length ? Math.max(...distances) : 0,
    distances
  };
}

const motionOf = node => Object.fromEntries(MOTION_FIELDS.map(field => [field, node[field]]));
const sameMotion = (left, right) => MOTION_FIELDS.every(field => Object.is(left[field], right[field]));
const rounded = value => Math.round(value * 10) / 10;

export class G6Adapter extends ViewerAdapter {
  graph = null;
  config;
  viewerConfig;
  callbacks;
  physics;
  selection = { current: null, pathNodes: new Set() };
  selectedRelationshipId = null;
  highlightedRelationships = new Set();
  displayCaptions = new Map();
  lastExpansion = null;
  dragObservation = null;
  restartPreset = 'browser-like';
  forcePreset = 'current';
  technicalErrors = [];
  drawRunning = false;
  drawDirty = false;
  drawPromise = Promise.resolve();
  drawFrameHandle = null;
  expansionTimer = null;
  expansionEndListener = null;

  constructor(container, onSelect, config = {}, viewerConfig = VIEWER_CONFIG, callbacks = {}) {
    super(container, onSelect);
    this.config = normalizeG6PocConfig(config);
    this.viewerConfig = structuredClone(viewerConfig);
    this.callbacks = callbacks;
    this.physics = new PersistentForceSimulation({
      width: container.clientWidth || 1000,
      height: container.clientHeight || 700,
      ...this.config
    }, () => this.#schedulePositionDraw());
  }

  async render(snapshot) {
    if (this.graph && !this.graph.destroyed) this.graph.destroy();
    this.container.replaceChildren();
    const data = toAdapterData(snapshot);
    this.selection.current = data.selectedNodeId;
    this.highlightedRelationships = new Set(data.highlightedRelationshipIds);
    this.#updatePathNodes(data.relationships);
    this.physics.reconcile(data.nodes, data.relationships);
    this.physics.settle(300);
    const poc = createG6PocOptions(this.config, this.selection);
    this.graph = new Graph({
      container: this.container,
      data: this.#toGraphData(data),
      autoFit: false,
      animation: false,
      behaviors: poc.behaviors,
      transforms: poc.transforms,
      node: this.#nodeOptions(),
      edge: this.#edgeOptions()
    });
    this.#bindEvents();
    await this.graph.render();
    await this.graph.fitView({ when: 'always', direction: 'both' });
    await this.applySelection(data.selectedNodeId);
    this.markRuntimeMounted('g6');
  }

  async applySelection(nodeId) {
    if (!this.graph) return;
    this.selection.current = nodeId;
    const nodes = this.graph.getNodeData();
    const edges = this.graph.getEdgeData();
    this.#updatePathNodes(edges.map(edge => edge.data));
    const states = buildGraphElementStates({
      nodes,
      relationships: edges,
      selectedNodeId: nodeId,
      selectedRelationshipId: this.selectedRelationshipId,
      highlightedRelationshipIds: this.highlightedRelationships
    });
    await this.graph.setElementState(states, false);
    await this.#refreshLabels();
    const labelBehavior = createG6PocOptions(this.config, this.selection).behaviors
      .find(behavior => behavior.key === 'g6-poc-labels');
    this.graph.updateBehavior(labelBehavior);
  }

  async applyRelationshipSelection(relationshipId) {
    if (!this.graph) return;
    this.selectedRelationshipId = relationshipId;
    this.selection.current = null;
    await this.applySelection(null);
  }

  async setLabelModes(nodeMode, edgeMode) {
    this.viewerConfig = withLabelModes(this.viewerConfig, nodeMode, edgeMode);
    if (!this.graph) return this.viewerConfig;
    this.graph.setNode(this.#nodeOptions());
    this.graph.setEdge(this.#edgeOptions());
    await this.#refreshLabels();
    await this.graph.draw();
    return structuredClone(this.viewerConfig);
  }

  setFixed(fixed) {
    this.config = normalizeG6PocConfig({ ...this.config, fixed });
    this.viewerConfig.interaction.dragEnd = fixed
      ? INTERACTION_ACTIONS.PIN : INTERACTION_ACTIONS.RELEASE;
  }

  async setVisualPreset(preset) {
    const visual = G6_VISUAL_PRESETS[preset];
    if (!visual) throw new Error(`unknown visual preset: ${preset}`);
    this.config = normalizeG6PocConfig({ ...this.config, ...visual, visualPreset: preset });
    this.viewerConfig.node.defaultSize = this.config.visualNodeSize;
    this.viewerConfig.node.borderWidth = this.config.nodeStrokeWidth;
    this.viewerConfig.node.fontSize = this.config.nodeFontSize;
    this.viewerConfig.node.fontWeight = this.config.nodeFontWeight;
    this.viewerConfig.edge.width = this.config.edgeLineWidth;
    this.viewerConfig.edge.fontSize = this.config.edgeFontSize;
    if (!this.graph) return;
    const graphNodes = this.graph.getNodeData();
    this.displayCaptions = uniqueShortCaptions(
      graphNodes.map(node => node.data), this.config.nodeCaptionLimit
    );
    this.graph.setNode(this.#nodeOptions());
    this.graph.setEdge(this.#edgeOptions());
    this.graph.updateNodeData(graphNodes.map(node => ({
      id: node.id,
      data: { ...node.data, displayCaption: this.displayCaptions.get(node.id) },
      style: { size: this.viewerConfig.node.defaultSize }
    })));
    await this.graph.draw();
    await this.applySelection(this.selection.current);
  }

  setRestartPreset(preset) {
    if (!(preset in RESTART_PRESETS)) throw new Error(`unknown restart preset: ${preset}`);
    this.restartPreset = preset;
  }

  setForcePreset(preset) {
    if (preset === 'browser-reference') {
      const radius = this.config.nodeSize / 2;
      this.physics.configure({
        ...BROWSER_REFERENCE_FORCE_CONFIG,
        nodeSize: this.config.nodeSize,
        linkDistance: radius + radius + BROWSER_REFERENCE_FORCE_CONFIG.linkGap,
        linkStrength: this.config.linkStrength,
        collideStrength: this.config.collideStrength,
        collideIterations: this.config.collideIterations
      });
    } else if (preset === 'current') {
      this.physics.configure({ ...this.config, velocityDecay: 0.35, alphaDecay: 0.045 });
    } else throw new Error(`unknown force preset: ${preset}`);
    this.forcePreset = preset;
  }

  configureForces(config) {
    this.config = normalizeG6PocConfig({ ...this.config, ...config });
    this.forcePreset = 'current';
    this.physics.configure({ ...this.config, velocityDecay: 0.35, alphaDecay: 0.045 });
    this.physics.restart(this.restartPreset);
  }

  pin(nodeId) {
    const node = this.physics.getNode(nodeId);
    if (!node) return false;
    this.physics.pin(nodeId, node.x, node.y);
    return true;
  }

  unpin(nodeId) {
    if (!nodeId || !this.physics.getNode(nodeId)) return false;
    this.physics.unpin(nodeId);
    this.physics.restart('gentle');
    return true;
  }

  rebalance() {
    return this.physics.restart('browser-like');
  }

  async hide(nodeId) {
    if (!this.graph?.hasNode(nodeId)) return false;
    const edges = this.graph.getRelatedEdgesData(nodeId).map(edge => edge.id);
    await this.graph.hideElement([nodeId, ...edges], false);
    return true;
  }

  async removeToSnapshot(snapshot) {
    if (!this.graph) throw new Error('G6 graph is not ready');
    const data = toAdapterData(snapshot);
    const nodeIds = new Set(data.nodes.map(node => node.id));
    const edgeIds = new Set(data.relationships.map(edge => edge.id));
    const removedNodes = this.graph.getNodeData().map(node => node.id).filter(id => !nodeIds.has(id));
    const removedEdges = this.graph.getEdgeData().map(edge => edge.id).filter(id => !edgeIds.has(id));
    this.physics.reconcile(data.nodes, data.relationships);
    this.graph.removeData({ nodes: removedNodes, edges: removedEdges });
    const geometry = new Map(annotateRelationshipGeometry(data.relationships).map(edge => [edge.id, edge]));
    this.graph.updateEdgeData(this.graph.getEdgeData().map(edge => ({ id: edge.id, data: geometry.get(edge.id) })));
    await this.graph.draw();
    await this.applySelection(data.selectedNodeId);
    this.physics.restart('gentle');
    return { removedNodes: removedNodes.length, removedEdges: removedEdges.length };
  }

  async addGeometryDemo() {
    if (!this.graph) throw new Error('G6 graph is not ready');
    const nodeIds = this.graph.getNodeData().map(node => node.id);
    if (nodeIds.length < 2) throw new Error('关系几何示例至少需要两个节点');
    const demoIds = ['g6-demo-parallel-1', 'g6-demo-parallel-2', 'g6-demo-reverse', 'g6-demo-loop'];
    if (demoIds.some(id => this.graph.hasEdge(id))) return { addedEdges: 0, edgeIds: demoIds };
    const [source, target] = nodeIds;
    const demo = annotateRelationshipGeometry([
      { id: demoIds[0], source, target, type: 'DEMO_PARALLEL_A', properties: {} },
      { id: demoIds[1], source, target, type: 'DEMO_PARALLEL_B', properties: {} },
      { id: demoIds[2], source: target, target: source, type: 'DEMO_REVERSE', properties: {} },
      { id: demoIds[3], source, target: source, type: 'DEMO_SELF_LOOP', properties: {} }
    ]);
    this.graph.addData({ edges: demo.map(edge => ({
      id: edge.id, source: edge.source, target: edge.target, data: edge
    })) });
    await this.graph.draw();
    await this.applySelection(this.selection.current);
    return { addedEdges: demo.length, edgeIds: demoIds };
  }

  async addGraphDto(graphDto, anchorId) {
    if (!this.graph) throw new Error('G6 graph is not ready');
    const existing = this.graph.getData();
    const existingNodeIds = new Set(existing.nodes.map(node => node.id));
    const existingEdgeIds = new Set(existing.edges.map(edge => edge.id));
    const anchorNode = this.physics.getNode(anchorId);
    if (!anchorNode) throw new Error(`unknown expansion anchor: ${anchorId}`);
    this.physics.pin(anchorId, anchorNode.x, anchorNode.y);
    const anchorBefore = [anchorNode.x, anchorNode.y];
    const before = this.physics.snapshotPositions([...existingNodeIds]);
    const oldReferences = new Map([...existingNodeIds].map(id => [id, this.physics.getNode(id)]));
    const oldMotion = new Map([...oldReferences].map(([id, node]) => [id, motionOf(node)]));
    const incoming = toAdapterData({
      ...graphDto,
      state: { selectedNodeId: anchorId, expandedNodeIds: [], highlightedRelationshipIds: [] }
    });
    const newNodeData = withDegrees(incoming).filter(node => !existingNodeIds.has(node.id));
    const newEdgeData = incoming.relationships.filter(edge => !existingEdgeIds.has(edge.id));
    if (newNodeData.length === 0 && newEdgeData.length === 0) {
      this.lastExpansion = {
        addedNodes: 0, addedEdges: 0, meanOldDisplacement: 0, maxOldDisplacement: 0,
        oldIdentityPreserved: true, oldStateReset: false,
        renderedRelationships: existing.edges.length, physicsLinks: this.physics.physicsLinks.length,
        anchorDisplacement: 0, anchorPinned: true, restartPreset: 'none'
      };
      return this.lastExpansion;
    }

    const allNodes = [...existing.nodes.map(node => node.data), ...newNodeData];
    this.displayCaptions = uniqueShortCaptions(allNodes, this.config.nodeCaptionLimit);
    const allRelationships = annotateRelationshipGeometry([
      ...existing.edges.map(edge => edge.data), ...newEdgeData
    ]);
    const reconcile = this.physics.reconcile(allNodes, allRelationships, { anchorId });
    const oldIdentityPreserved = [...oldReferences].every(([id, reference]) => this.physics.getNode(id) === reference);
    const oldStateReset = [...oldMotion].some(([id, motion]) => !sameMotion(motion, motionOf(this.physics.getNode(id))));
    this.graph.updateNodeData(existing.nodes.map(node => ({
      id: node.id,
      data: { ...node.data, displayCaption: this.displayCaptions.get(node.id) }
    })));
    const geometryById = new Map(allRelationships.map(edge => [edge.id, edge]));
    this.graph.updateEdgeData(existing.edges.map(edge => ({
      id: edge.id,
      data: geometryById.get(edge.id)
    })));
    const nodes = newNodeData.map(node => this.#toGraphNode({
      ...node, displayCaption: this.displayCaptions.get(node.id)
    }));
    const edges = newEdgeData.map(edge => {
      const geometry = geometryById.get(edge.id);
      return { id: edge.id, source: edge.source, target: edge.target, data: geometry };
    });

    this.graph.addData({ nodes, edges });
    await this.graph.draw();
    await this.applySelection(anchorId);
    const expansionRestartPreset = nodes.length <= 6 ? 'gentle' : this.restartPreset;
    this.physics.restart(expansionRestartPreset);
    await this.#waitForSimulationEnd();
    await this.#flushPositionDraw();

    const moved = displacement(before, this.physics.snapshotPositions([...existingNodeIds]));
    const anchorAfter = this.physics.getNode(anchorId);
    const anchorDisplacement = Math.hypot(anchorAfter.x - anchorBefore[0], anchorAfter.y - anchorBefore[1]);
    this.lastExpansion = {
      addedNodes: nodes.length,
      addedEdges: edges.length,
      reusedNodes: reconcile.reusedNodes,
      oldIdentityPreserved,
      oldStateReset,
      meanOldDisplacement: rounded(moved.mean),
      maxOldDisplacement: rounded(moved.max),
      stableOldRatio: moved.distances.length
        ? moved.distances.filter(value => value <= this.config.linkDistance / 2).length / moved.distances.length : 1,
      renderedRelationships: this.graph.getEdgeData().length,
      physicsLinks: this.physics.physicsLinks.length,
      anchorDisplacement: rounded(anchorDisplacement),
      anchorPinned: anchorAfter.fx != null && anchorAfter.fy != null,
      restartPreset: expansionRestartPreset,
      restartAlpha: RESTART_PRESETS[expansionRestartPreset]
    };
    return this.lastExpansion;
  }

  diagnostics() {
    return {
      layout: 'persistent-d3-force',
      simulationCreationCount: this.physics.creationCount,
      simulationNodeCount: this.physics.activeNodes.length,
      physicsLinkCount: this.physics.physicsLinks.length,
      simulationStopped: this.physics.stopped,
      restartPreset: this.restartPreset,
      restartAlpha: RESTART_PRESETS[this.restartPreset],
      forcePreset: this.forcePreset,
      forceConfig: { ...this.physics.config },
      viewerConfig: structuredClone(this.viewerConfig),
      behaviors: this.graph?.getBehaviors(),
      transforms: this.graph?.getOptions().transforms,
      config: { ...this.config }, selection: this.selection.current,
      lastExpansion: this.lastExpansion, dragObservation: this.dragObservation,
      technicalErrors: [...this.technicalErrors]
      , runtime: this.runtimeDiagnostics()
    };
  }

  async fit() { await this.graph?.fitView({ when: 'always', direction: 'both' }); }

  async destroy() {
    if (this.drawFrameHandle != null) cancelAnimationFrame(this.drawFrameHandle);
    this.drawFrameHandle = null;
    this.drawRunning = false;
    this.drawDirty = false;
    if (this.expansionTimer != null) clearTimeout(this.expansionTimer);
    this.expansionTimer = null;
    if (this.expansionEndListener) this.physics.simulation.on('end.expansion', null);
    this.expansionEndListener = null;
    this.physics.stop();
    if (this.graph && !this.graph.destroyed) this.graph.destroy();
    this.graph = null;
    this.container.replaceChildren();
    this.markRuntimeDestroyed();
  }

  #nodeOptions() {
    const nodeConfig = this.viewerConfig.node;
    return {
      style: datum => {
        const label = resolveNodeLabel(nodeConfig.labelMode, {
          ...datum.data,
          displayCaption: datum.data.displayCaption
            ?? shortCaption(datum.data.caption, this.config.nodeCaptionLimit)
        });
        return {
        size: nodeConfig.defaultSize,
        fill: stableNodeColor(datum.data, this.viewerConfig.color),
        stroke: '#ffffff', lineWidth: nodeConfig.borderWidth, cursor: 'grab',
        labelText: datum.data.viewerLabelText ?? label.text,
        labelPlacement: datum.data.viewerLabelPlacement ?? label.placement,
        labelFontSize: nodeConfig.fontSize, labelFontWeight: nodeConfig.fontWeight,
        labelMaxWidth: nodeConfig.labelMode === 'OUTSIDE' ? 160 : nodeConfig.labelMaxWidth,
        labelOverflow: nodeConfig.labelOverflow,
        labelFill: nodeConfig.labelMode === 'OUTSIDE' ? '#334155' : '#ffffff',
        labelBackground: nodeConfig.labelMode === 'OUTSIDE',
        labelBackgroundFill: '#ffffff', labelBackgroundFillOpacity: 0.86,
        labelBackgroundPadding: [1, 3], labelTextAlign: 'center', labelTextBaseline: 'middle'
      }; },
      state: {
        selected: {
          size: nodeConfig.defaultSize + nodeConfig.selectedStyle.sizeOffset,
          fill: nodeConfig.selectedStyle.fill, stroke: nodeConfig.selectedStyle.stroke,
          lineWidth: nodeConfig.selectedStyle.lineWidth,
          halo: true, haloLineWidth: 5, haloStroke: nodeConfig.selectedStyle.haloStroke,
          haloStrokeOpacity: nodeConfig.selectedStyle.haloOpacity,
          labelOpacity: 1, labelFontWeight: 500
        },
        neighbor: {
          stroke: '#0ea5e9', lineWidth: 1.35,
          halo: true, haloLineWidth: 3, haloStroke: '#38bdf8', haloStrokeOpacity: 0.12,
          labelOpacity: 1
        },
        path: {
          stroke: '#e11d48', lineWidth: 1.4,
          halo: true, haloLineWidth: 2.5, haloStroke: '#fb7185', haloStrokeOpacity: 0.1,
          labelOpacity: 1
        },
        inactive: nodeConfig.inactiveStyle,
        hover: { ...nodeConfig.hoverStyle, labelOpacity: 1 }
      }
    };
  }

  #edgeOptions() {
    const edgeConfig = this.viewerConfig.edge;
    return {
      type: G6_EDGE_GEOMETRY.type,
      style: datum => {
        const automaticCaption = relationshipCaptionForLength(
          datum.data.type, this.#edgeLength(datum), edgeConfig.fontSize
        );
        return {
        stroke: '#a8b0ba', lineWidth: edgeConfig.width,
        strokeOpacity: edgeConfig.opacity, endArrow: true,
        endArrowSize: edgeConfig.arrowSize,
        endArrowType: G6_EDGE_GEOMETRY.endArrowType,
        endArrowLineWidth: 0.6,
        loopType: G6_EDGE_GEOMETRY.loopType,
        labelText: datum.data.viewerLabelText
          ?? resolveEdgeLabel(edgeConfig.labelMode, datum.data.type, automaticCaption),
        labelPlacement: 'center',
        labelAutoRotate: G6_EDGE_GEOMETRY.labelAutoRotate,
        labelIsBillboard: G6_EDGE_GEOMETRY.labelIsBillboard,
        labelMaxWidth: '70%',
        labelFontSize: edgeConfig.fontSize, labelFontWeight: 400, labelFill: '#334155',
        labelBackground: true, labelBackgroundFill: '#ffffff',
        labelBackgroundFillOpacity: 0.82, labelBackgroundPadding: [1, 3],
        labelOpacity: edgeConfig.labelMode === 'VISIBLE' ? 0.82 : 0
      }; },
      state: {
        selected: {
          stroke: edgeConfig.selectedStyle.stroke, lineWidth: edgeConfig.selectedWidth,
          strokeOpacity: edgeConfig.selectedStyle.opacity, labelOpacity: 1
        },
        related: {
          stroke: edgeConfig.selectedStyle.stroke, lineWidth: edgeConfig.selectedWidth,
          strokeOpacity: 0.72, labelOpacity: 0.82
        },
        highlighted: {
          stroke: '#e11d48', lineWidth: edgeConfig.pathWidth,
          strokeOpacity: 0.78, labelOpacity: 0.85
        },
        inactive: edgeConfig.inactiveStyle,
        hover: { stroke: '#0f172a', lineWidth: edgeConfig.selectedWidth, strokeOpacity: 0.85, labelOpacity: 1 }
      }
    };
  }

  #updatePathNodes(relationships) {
    const pathNodes = new Set();
    for (const relationship of relationships) {
      if (!this.highlightedRelationships.has(relationship.id)) continue;
      pathNodes.add(relationship.source);
      pathNodes.add(relationship.target);
    }
    this.selection.pathNodes = pathNodes;
  }

  #toGraphNode(node) {
    const simulationNode = this.physics.getNode(node.id);
    return {
      id: node.id,
      data: node,
      style: { size: this.viewerConfig.node.defaultSize, x: simulationNode.x, y: simulationNode.y }
    };
  }

  #toGraphData(data) {
    const nodes = withDegrees(data);
    this.displayCaptions = uniqueShortCaptions(nodes, this.config.nodeCaptionLimit);
    return {
      nodes: nodes.map(node => this.#toGraphNode({
        ...node, displayCaption: this.displayCaptions.get(node.id)
      })),
      // 物理层可按节点对去重，但显示层必须保留 GraphDTO 的每一条业务关系。
      edges: annotateRelationshipGeometry(data.relationships).map(edge => ({
        id: edge.id, source: edge.source, target: edge.target, data: edge
      }))
    };
  }

  #edgeLength(edge) {
    if (edge.source === edge.target) return Math.PI * Number(edge.style?.loopDist ?? 50);
    if (!this.graph) return this.config.linkDistance;
    try {
      const source = this.graph.getElementPosition(edge.source);
      const target = this.graph.getElementPosition(edge.target);
      return Math.hypot(target[0] - source[0], target[1] - source[1]);
    } catch {
      return this.config.linkDistance;
    }
  }

  #positions(ids) {
    const positions = new Map();
    for (const id of ids) {
      try { positions.set(id, this.graph.getElementPosition(id)); } catch { /* pending element */ }
    }
    return positions;
  }

  #refreshLabels() {
    if (!this.graph) return;
    this.graph.updateNodeData(this.graph.getNodeData().map(node => this.#nodeLabelUpdate(node)));
    this.graph.updateEdgeData(this.graph.getEdgeData().map(edge => this.#edgeLabelUpdate(edge)));
  }

  #nodeLabelUpdate(node) {
    const states = this.graph.getElementState(node.id);
    const active = states.includes('selected') || states.includes('hover');
    const label = resolveNodeLabel(this.viewerConfig.node.labelMode, node.data, { active });
    const outside = this.viewerConfig.node.labelMode === 'OUTSIDE';
    return {
      id: node.id,
      data: { ...node.data, viewerLabelText: label.text, viewerLabelPlacement: label.placement },
      style: {
        labelText: label.text, labelPlacement: label.placement,
        labelFill: outside ? '#334155' : '#ffffff',
        labelMaxWidth: active || outside ? 160 : this.viewerConfig.node.labelMaxWidth,
        labelBackground: outside, labelBackgroundFill: '#ffffff',
        labelBackgroundFillOpacity: 0.86, labelBackgroundPadding: [1, 3]
      }
    };
  }

  #edgeLabelUpdate(edge) {
    const states = this.graph.getElementState(edge.id);
    const automaticCaption = relationshipCaptionForLength(
      edge.data.type, this.#edgeLength(edge), this.viewerConfig.edge.fontSize
    );
    const labelText = resolveEdgeLabel(
      this.viewerConfig.edge.labelMode, edge.data.type, automaticCaption,
      { active: states.includes('hover') || states.includes('selected'),
        related: states.includes('related'), path: states.includes('highlighted') }
    );
    return {
      id: edge.id,
      data: { ...edge.data, viewerLabelText: labelText },
      style: { labelText }
    };
  }

  #bindEvents() {
    this.graph.on(NodeEvent.CLICK, event => {
      if (this.viewerConfig.interaction.click !== INTERACTION_ACTIONS.SELECT) return;
      this.selectedRelationshipId = null;
      this.callbacks.onRelationshipSelect?.(null);
      this.onSelect?.(event.target.id);
    });
    this.graph.on(NodeEvent.DBLCLICK, event => {
      if (!this.viewerConfig.interaction.doubleClick) return;
      this.callbacks.onNodeAction?.(event.target.id, this.viewerConfig.interaction.doubleClick);
    });
    this.graph.on(EdgeEvent.CLICK, event => {
      if (this.viewerConfig.interaction.click !== INTERACTION_ACTIONS.SELECT) return;
      this.callbacks.onRelationshipSelect?.(event.target.id);
    });
    this.graph.on(NodeEvent.POINTER_OVER, event => this.#toggleHover(event.target.id, true));
    this.graph.on(NodeEvent.POINTER_LEAVE, event => this.#toggleHover(event.target.id, false));
    this.graph.on(EdgeEvent.POINTER_OVER, event => this.#toggleHover(event.target.id, true));
    this.graph.on(EdgeEvent.POINTER_LEAVE, event => this.#toggleHover(event.target.id, false));
    this.graph.on(CanvasEvent.CLICK, () => {
      this.selectedRelationshipId = null;
      this.callbacks.onRelationshipSelect?.(null);
      this.onSelect?.(null);
    });
    this.graph.on(NodeEvent.DRAG_START, event => {
      if (this.viewerConfig.interaction.drag !== INTERACTION_ACTIONS.MOVE_WITH_FORCE) return;
      const id = event.target.id;
      const neighbors = this.graph.getNeighborNodesData(id).map(node => node.id);
      const node = this.physics.getNode(id);
      this.physics.pin(id, node.x, node.y);
      this.physics.beginInteraction();
      this.dragObservation = {
        id, neighbors, fixed: this.config.fixed,
        before: Object.fromEntries(this.#positions(neighbors))
      };
    });
    this.graph.on(NodeEvent.DRAG, event => {
      if (this.viewerConfig.interaction.drag !== INTERACTION_ACTIONS.MOVE_WITH_FORCE) return;
      if (!this.dragObservation) return;
      const zoom = this.graph.getZoom() || 1;
      this.physics.movePinned(this.dragObservation.id, event.dx / zoom, event.dy / zoom);
      this.dragObservation.during = Object.fromEntries(this.#positions(this.dragObservation.neighbors));
    });
    this.graph.on(NodeEvent.DRAG_END, () => {
      if (this.viewerConfig.interaction.drag !== INTERACTION_ACTIONS.MOVE_WITH_FORCE) return;
      if (!this.dragObservation) return;
      if (this.viewerConfig.interaction.dragEnd === INTERACTION_ACTIONS.RELEASE) {
        this.physics.unpin(this.dragObservation.id);
      }
      this.physics.endInteraction();
      const node = this.physics.getNode(this.dragObservation.id);
      this.dragObservation.after = Object.fromEntries(this.#positions(this.dragObservation.neighbors));
      this.dragObservation.pinnedAfterDrag = node.fx != null && node.fy != null;
    });
  }

  #schedulePositionDraw() {
    if (!this.graph || this.graph.destroyed) return;
    if (this.drawRunning) {
      this.drawDirty = true;
      return;
    }
    this.drawRunning = true;
    this.drawPromise = new Promise(resolve => {
      this.drawFrameHandle = requestAnimationFrame(() => {
        this.drawFrameHandle = null;
        resolve();
      });
    })
      .then(async () => {
        if (!this.graph || this.graph.destroyed) return;
        this.graph.updateNodeData(this.physics.activeNodes.map(node => ({
          id: node.id,
          style: { x: node.x, y: node.y }
        })));
        await this.graph.draw();
      })
      .catch(error => this.technicalErrors.push(error.message))
      .finally(() => {
        this.drawRunning = false;
        if (this.drawDirty) {
          this.drawDirty = false;
          this.#schedulePositionDraw();
        }
      });
  }

  async #flushPositionDraw() {
    this.drawDirty = false;
    await this.drawPromise;
    if (!this.graph || this.graph.destroyed) return;
    this.graph.updateNodeData(this.physics.activeNodes.map(node => ({
      id: node.id,
      style: { x: node.x, y: node.y }
    })));
    await this.graph.draw();
  }

  #waitForSimulationEnd(timeout = 10000) {
    return new Promise(resolve => {
      let completed = false;
      const finish = () => {
        if (completed) return;
        completed = true;
        clearTimeout(timer);
        this.expansionTimer = null;
        this.expansionEndListener = null;
        this.physics.simulation.on('end.expansion', null);
        resolve();
      };
      const timer = setTimeout(() => {
        this.technicalErrors.push('persistent simulation did not cool before timeout');
        this.physics.stop();
        finish();
      }, timeout);
      this.expansionTimer = timer;
      this.expansionEndListener = finish;
      this.physics.simulation.on('end.expansion', finish);
    });
  }

  async #toggleHover(id, enabled) {
    if (this.viewerConfig.interaction.hover !== INTERACTION_ACTIONS.HIGHLIGHT) return;
    const states = this.graph.getElementState(id).filter(state => state !== 'hover');
    if (enabled) states.push('hover');
    await this.graph.setElementState(id, states, false);
    if (this.graph.hasNode(id)) this.graph.updateNodeData([this.#nodeLabelUpdate(this.graph.getNodeData(id))]);
    else if (this.graph.hasEdge(id)) this.graph.updateEdgeData([this.#edgeLabelUpdate(this.graph.getEdgeData(id))]);
  }
}
