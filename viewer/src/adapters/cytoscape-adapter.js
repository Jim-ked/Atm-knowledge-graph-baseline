import cytoscape from 'cytoscape';
import fcose from 'cytoscape-fcose';
import layoutUtilities from 'cytoscape-layout-utilities';
import d3Force from 'cytoscape-d3-force';
import cola from 'cytoscape-cola';
import elk from 'cytoscape-elk';
import dagre from 'cytoscape-dagre';
import viewUtilities from 'cytoscape-view-utilities';
import undoRedo from 'cytoscape-undo-redo';
import contextMenus from 'cytoscape-context-menus';
import cytoscapePopper from 'cytoscape-popper';
import navigator from 'cytoscape-navigator';
import cise from 'cytoscape-cise';
import avsdf from 'cytoscape-avsdf';
import spread from 'cytoscape-spread';
import automove from 'cytoscape-automove';
import expandCollapse from 'cytoscape-expand-collapse';
import { computePosition, flip, shift, limitShift } from '@floating-ui/dom';
import { ViewerAdapter, toAdapterData } from '../core/viewer-adapter.js';
import {
  INTERACTION_ACTIONS, VIEWER_CONFIG, resolveEdgeLabel, resolveNodeLabel, stableNodeColor,
  withLabelModes
} from '../config/viewer-config.js';
import { annotateRelationshipGeometry, relationshipCaptionForLength, uniqueShortCaptions } from './g6-visual-geometry.js';
import {
  CYTOSCAPE_LAYOUT_POLICIES, chooseCytoscapeLayout, cytoscapeLayoutOptions
} from './cytoscape-layout-policy.js';

function floatingPopperFactory(reference, content, options = {}) {
  const update = () => computePosition(reference, content, {
    middleware: [flip(), shift({ limiter: limitShift() })], ...options
  }).then(({ x, y }) => Object.assign(content.style, { left: `${x}px`, top: `${y}px` }));
  update();
  return { update, destroy: () => content.remove() };
}

// Source: official README usage for each installed Cytoscape extension.
cytoscape.use(fcose);
cytoscape.use(layoutUtilities);
cytoscape.use(d3Force);
cytoscape.use(cola);
cytoscape.use(elk);
cytoscape.use(dagre);
cytoscape.use(viewUtilities);
cytoscape.use(undoRedo);
cytoscape.use(contextMenus);
cytoscape.use(cytoscapePopper(floatingPopperFactory));
cytoscape.use(navigator);
cytoscape.use(cise);
cytoscape.use(avsdf);
cytoscape.use(spread);
cytoscape.use(automove);
cytoscape.use(expandCollapse);

const relationPair = (source, target) => [source, target].sort().join('\u0000');

function initialPosition(index, width = 800, height = 600) {
  const angle = index * Math.PI * (3 - Math.sqrt(5));
  const radius = 90 + Math.sqrt(index + 1) * Math.max(28, Math.min(width, height) * 0.08);
  return { x: width / 2 + Math.cos(angle) * radius, y: height / 2 + Math.sin(angle) * radius };
}

function edgeGeometry(relationships) {
  const annotated = annotateRelationshipGeometry(relationships);
  const groups = new Map();
  for (const edge of annotated) {
    const key = relationPair(edge.source, edge.target);
    const group = groups.get(key) ?? [];
    group.push(edge);
    groups.set(key, group);
  }
  return annotated.map(edge => {
    const group = groups.get(relationPair(edge.source, edge.target));
    return { ...edge, curveStep: (group.indexOf(edge) - (group.length - 1) / 2) * 44 };
  });
}

function edgeDistance(cy, edge) {
  if (edge.source === edge.target) return 100;
  const source = cy.getElementById(edge.source).position();
  const target = cy.getElementById(edge.target).position();
  return Math.hypot(target.x - source.x, target.y - source.y);
}

function diagnosticClone(value) {
  return JSON.parse(JSON.stringify(value, (_key, item) => typeof item === 'function' ? '[Function]' : item));
}

export class CytoscapeAdapter extends ViewerAdapter {
  cy = null;
  viewerConfig;
  callbacks;
  debug = false;
  layoutPolicy = 'AUTO';
  layoutContext = 'FULL_QUERY';
  activeLayout = null;
  selectedNodeId = null;
  selectedEdgeId = null;
  hoveredNodeId = null;
  hoveredEdgeId = null;
  pathEdges = new Set();
  pathNodes = new Set();
  tooltipInstances = new Map();
  navigatorInstance = null;
  navigatorContainer = null;
  automoveRule = null;
  expandCollapseApi = null;
  layoutUtilitiesApi = null;
  viewUtilitiesApi = null;
  undoRedoApi = null;
  contextMenusApi = null;
  layoutHistory = [];
  lastLayout = 'KEEP';
  lastLayoutOptions = null;
  lastExpansion = null;
  pluginDiagnostics = {};
  technicalErrors = [];

  constructor(container, onSelect, viewerConfig = VIEWER_CONFIG, callbacks = {}) {
    super(container, onSelect);
    this.viewerConfig = structuredClone(viewerConfig);
    this.callbacks = callbacks;
    this.debug = Boolean(callbacks.debug);
  }

  async render(snapshot) {
    await this.destroy();
    const data = toAdapterData(snapshot);
    this.selectedNodeId = data.selectedNodeId;
    this.pathEdges = new Set(data.highlightedRelationshipIds);
    this.#updatePathNodes(data.relationships);
    const width = this.container.clientWidth || 1000;
    const height = this.container.clientHeight || 700;
    const captions = uniqueShortCaptions(data.nodes, 8);
    const geometries = edgeGeometry(data.relationships);
    this.cy = cytoscape({
      container: this.container,
      elements: [
        ...data.nodes.map((node, index) => ({ group: 'nodes', data: {
          id: node.id, caption: node.caption, viewerLabel: captions.get(node.id), fullCaption: node.caption,
          kind: node.kind ?? '', labels: node.labels.join(', '), original: structuredClone(node),
          color: stableNodeColor(node, this.viewerConfig.color)
        }, position: initialPosition(index, width, height) })),
        ...geometries.map(edge => ({ group: 'edges', data: {
          id: edge.id, source: edge.source, target: edge.target, type: edge.type,
          viewerLabel: edge.type, geometryKind: edge.geometryKind, curveStep: edge.curveStep,
          original: structuredClone(edge)
        } }))
      ],
      style: this.#style(), boxSelectionEnabled: true, wheelSensitivity: 0.18,
      minZoom: 0.08, maxZoom: 6, selectionType: 'single'
    });
    this.#initPlugins();
    this.#bindEvents();
    await this.#applyLayout('AUTO', { context: 'FULL_QUERY', fit: true });
    await this.applySelection(data.selectedNodeId);
    this.markRuntimeMounted('cytoscape');
  }

  diagnostics() {
    return {
      engine: 'cytoscape-3.34.1',
      graphDto: { nodes: this.cy?.nodes().length ?? 0, relationships: this.cy?.edges().length ?? 0 },
      layoutPolicy: this.layoutPolicy, layoutContext: this.layoutContext, lastLayout: this.lastLayout,
      layoutHistory: [...this.layoutHistory], lastLayoutOptions: diagnosticClone(this.lastLayoutOptions),
      selectedNodeId: this.selectedNodeId, selectedEdgeId: this.selectedEdgeId,
      hoveredNodeId: this.hoveredNodeId, hoveredEdgeId: this.hoveredEdgeId,
      fixedNodeCount: this.cy?.nodes(':locked').length ?? 0,
      hiddenNodeCount: this.cy?.nodes(':hidden').length ?? 0,
      lastExpansion: this.lastExpansion, pluginDiagnostics: diagnosticClone(this.pluginDiagnostics),
      technicalErrors: [...this.technicalErrors], runtime: this.runtimeDiagnostics()
    };
  }

  async applySelection(nodeId) {
    if (!this.cy) return;
    this.selectedNodeId = nodeId ?? null;
    this.selectedEdgeId = null;
    this.cy.elements().unselect();
    if (nodeId && this.cy.hasElementWithId(nodeId)) this.cy.getElementById(nodeId).select();
    this.#refreshVisualState();
  }

  async applyRelationshipSelection(edgeId) {
    if (!this.cy) return;
    this.selectedEdgeId = edgeId ?? null;
    this.selectedNodeId = null;
    this.cy.elements().unselect();
    if (edgeId && this.cy.hasElementWithId(edgeId)) this.cy.getElementById(edgeId).select();
    this.#refreshVisualState();
  }

  async setLabelModes(nodeMode, edgeMode) {
    this.viewerConfig = withLabelModes(this.viewerConfig, nodeMode, edgeMode);
    this.#refreshVisualState();
    return structuredClone(this.viewerConfig);
  }

  async setLayoutPolicy(policy) {
    if (!CYTOSCAPE_LAYOUT_POLICIES.includes(policy)) throw new Error(`unknown Cytoscape layout policy: ${policy}`);
    this.layoutPolicy = policy;
    return this.#applyLayout(policy, { context: this.layoutContext, fit: false });
  }

  async rebalance() {
    this.layoutContext = 'FULL_QUERY';
    return this.#applyLayout('AUTO', { context: 'FULL_QUERY', fit: false });
  }

  async fit() { this.cy?.fit(undefined, 28); }

  async fitSelectedNeighborhood() {
    if (!this.cy || !this.selectedNodeId) return;
    this.cy.fit(this.cy.getElementById(this.selectedNodeId).closedNeighborhood(), 36);
  }

  pin(nodeId) {
    if (!nodeId || !this.cy?.hasElementWithId(nodeId)) return false;
    this.undoRedoApi?.do('atmkg-pin', { ids: [nodeId] });
    return true;
  }

  unpin(nodeId) {
    if (!nodeId || !this.cy?.hasElementWithId(nodeId)) return false;
    this.undoRedoApi?.do('atmkg-unpin', { ids: [nodeId] });
    return true;
  }

  async hide(nodeId) {
    if (!nodeId || !this.cy?.hasElementWithId(nodeId)) return false;
    this.undoRedoApi?.do('atmkg-hide', { ids: [nodeId] });
    return true;
  }

  async show(nodeId) {
    if (!nodeId || !this.cy?.hasElementWithId(nodeId)) return false;
    this.undoRedoApi?.do('atmkg-show', { ids: [nodeId] });
    return true;
  }

  async addGraphDto(graphDto, anchorId) {
    if (!this.cy) throw new Error('Cytoscape graph is not ready');
    const beforeIds = new Set(this.cy.nodes().map(node => node.id()));
    const beforePositions = new Map(this.cy.nodes().map(node => [node.id(), { ...node.position() }]));
    const incomingNodes = (graphDto.nodes ?? []).filter(node => !beforeIds.has(node.id));
    const existingEdges = new Set(this.cy.edges().map(edge => edge.id()));
    const incomingEdges = (graphDto.relationships ?? []).filter(edge => !existingEdges.has(edge.id));
    const geometries = edgeGeometry(graphDto.relationships ?? []);
    const byEdge = new Map(geometries.map(edge => [edge.id, edge]));
    this.cy.add([
      ...incomingNodes.map(node => ({ group: 'nodes', data: {
        id: node.id, caption: node.caption, viewerLabel: node.caption, fullCaption: node.caption,
        kind: node.kind ?? '', labels: node.labels.join(', '), original: structuredClone(node),
        color: stableNodeColor(node, this.viewerConfig.color)
      }, position: { x: 0, y: 0 } })),
      ...incomingEdges.map(edge => ({ group: 'edges', data: {
        id: edge.id, source: edge.source, target: edge.target, type: edge.type,
        viewerLabel: edge.type, geometryKind: byEdge.get(edge.id)?.geometryKind ?? 'single',
        curveStep: byEdge.get(edge.id)?.curveStep ?? 0, original: structuredClone(edge)
      } }))
    ]);
    if (this.layoutUtilitiesApi && incomingNodes.length) {
      this.layoutUtilitiesApi.placeNewNodes(this.cy.nodes().filter(node => !beforeIds.has(node.id)));
    } else if (anchorId && this.cy.hasElementWithId(anchorId)) {
      const anchor = this.cy.getElementById(anchorId).position();
      incomingNodes.forEach((node, index) => this.cy.getElementById(node.id).position({ x: anchor.x + 76, y: anchor.y + (index - (incomingNodes.length - 1) / 2) * 58 }));
    }
    const anchor = anchorId ? this.cy.getElementById(anchorId) : null;
    const wasLocked = Boolean(anchor?.locked());
    anchor?.lock();
    await this.#applyLayout('FCOSE', { context: 'INCREMENTAL_EXPAND', anchorId, fit: false });
    if (!wasLocked) anchor?.unlock();
    this.#refreshVisualState();
    const moved = [...beforePositions].map(([id, point]) => {
      const next = this.cy.getElementById(id).position();
      return Math.hypot(next.x - point.x, next.y - point.y);
    });
    const anchorAfter = anchor?.position() ?? { x: 0, y: 0 };
    const anchorBefore = anchor ? beforePositions.get(anchorId) : anchorAfter;
    this.lastExpansion = {
      addedNodes: incomingNodes.length, addedEdges: incomingEdges.length,
      oldCoordinatesPreserved: [...beforeIds].every(id => this.cy.hasElementWithId(id)),
      meanOldDisplacement: moved.length ? moved.reduce((sum, value) => sum + value, 0) / moved.length : 0,
      maxOldDisplacement: moved.length ? Math.max(...moved) : 0, anchorId,
      anchorDisplacement: anchorBefore ? Math.hypot(anchorAfter.x - anchorBefore.x, anchorAfter.y - anchorBefore.y) : 0,
      graphNodes: this.cy.nodes().length, graphEdges: this.cy.edges().length
    };
    return this.lastExpansion;
  }

  async removeToSnapshot(snapshot) {
    if (!this.cy) throw new Error('Cytoscape graph is not ready');
    const nodeIds = new Set(snapshot.nodes.map(node => node.id));
    const edgeIds = new Set(snapshot.relationships.map(edge => edge.id));
    const removedNodes = this.cy.nodes().filter(node => !nodeIds.has(node.id())).length;
    const removedEdges = this.cy.edges().filter(edge => !edgeIds.has(edge.id())).length;
    this.cy.edges().filter(edge => !edgeIds.has(edge.id())).remove();
    this.cy.nodes().filter(node => !nodeIds.has(node.id())).remove();
    this.#refreshVisualState();
    return { removedNodes, removedEdges };
  }

  async runPluginLayout(name, variant = null) { return this.#applyLayout(name, { context: 'FULL_QUERY', variant, fit: false }); }

  async runFcoseConstraintProbe() {
    if (!this.cy || this.cy.nodes().length < 4) return null;
    const nodes = this.cy.nodes().slice(0, 4).map(node => node.id());
    const positions = nodes.map(id => ({ nodeId: id, position: { ...this.cy.getElementById(id).position() } }));
    const probes = [
      { name: 'fixedNodeConstraint', fixedNodeConstraint: positions.slice(0, 1) },
      { name: 'alignmentConstraint', alignmentConstraint: { vertical: [[nodes[0], nodes[1]]] } },
      { name: 'relativePlacementConstraint', relativePlacementConstraint: [{ left: nodes[0], right: nodes[1], gap: 60 }] }
    ];
    const results = [];
    for (const probe of probes) {
      await this.#applyLayout('FCOSE', { context: 'FULL_QUERY', constraints: probe, fit: false });
      results.push({ name: probe.name, options: this.lastLayoutOptions });
    }
    this.pluginDiagnostics.fcoseConstraints = results;
    return results;
  }

  async enableAutomoveProbe() {
    if (!this.debug || !this.cy?.automove) return { enabled: false, reason: 'debug-only or plugin unavailable' };
    const nodes = this.cy.nodes().slice(0, 2);
    if (nodes.length < 2) return { enabled: false, reason: 'requires two nodes' };
    nodes[1].addClass('automove-probe');
    this.automoveRule?.destroy?.();
    this.automoveRule = this.cy.automove({ nodesMatching: '.automove-probe', reposition: 'drag', dragWith: `#${nodes[0].id()}` });
    this.pluginDiagnostics.automove = { enabled: true, target: nodes[1].id(), dragWith: nodes[0].id() };
    return this.pluginDiagnostics.automove;
  }

  undo() { return this.undoRedoApi?.undo?.(); }
  redo() { return this.undoRedoApi?.redo?.(); }

  async destroy() {
    this.activeLayout?.stop?.();
    this.activeLayout = null;
    for (const instance of this.tooltipInstances.values()) instance.destroy?.();
    this.tooltipInstances.clear();
    this.navigatorInstance?.destroy?.();
    this.navigatorInstance = null;
    this.navigatorContainer?.remove();
    this.navigatorContainer = null;
    this.automoveRule?.destroy?.();
    this.automoveRule = null;
    this.contextMenusApi?.destroy?.();
    this.contextMenusApi = null;
    this.expandCollapseApi?.clearVisualCue?.();
    this.undoRedoApi?.reset?.();
    this.cy?.destroy();
    this.cy = null;
    this.container.replaceChildren();
    this.markRuntimeDestroyed();
  }

  #initPlugins() {
    this.layoutUtilitiesApi = this.cy.layoutUtilities({ idealEdgeLength: 86, offset: 24, componentSpacing: 80 });
    this.viewUtilitiesApi = this.cy.viewUtilities({
      highlightStyles: [{ node: { 'border-color': '#0284c7', 'border-width': 3 }, edge: { 'line-color': '#0284c7', 'target-arrow-color': '#0284c7', width: 2 } }],
      selectStyles: { node: { 'border-color': '#7c2d12', 'border-width': 3 }, edge: { width: 2 } },
      setVisibilityOnHide: true, setDisplayOnHide: true, zoomAnimationDuration: 250
    });
    this.undoRedoApi = this.cy.undoRedo({ undoableDrag: true, stackSizeLimit: 80 });
    this.undoRedoApi.action('atmkg-pin', ({ ids }) => {
      const previous = ids.map(id => ({ id, locked: this.cy.getElementById(id).locked() }));
      ids.forEach(id => this.cy.getElementById(id).lock()); return previous;
    }, previous => { previous.forEach(item => item.locked ? this.cy.getElementById(item.id).lock() : this.cy.getElementById(item.id).unlock()); return previous; });
    this.undoRedoApi.action('atmkg-unpin', ({ ids }) => {
      const previous = ids.map(id => ({ id, locked: this.cy.getElementById(id).locked() }));
      ids.forEach(id => this.cy.getElementById(id).unlock()); return previous;
    }, previous => { previous.forEach(item => item.locked ? this.cy.getElementById(item.id).lock() : this.cy.getElementById(item.id).unlock()); return previous; });
    this.undoRedoApi.action('atmkg-hide', ({ ids }) => { const eles = this.cy.collection(ids.map(id => this.cy.getElementById(id))); this.viewUtilitiesApi.hide(eles); return ids; }, ids => { this.viewUtilitiesApi.show(this.cy.collection(ids.map(id => this.cy.getElementById(id)))); return ids; });
    this.undoRedoApi.action('atmkg-show', ({ ids }) => { const eles = this.cy.collection(ids.map(id => this.cy.getElementById(id))); this.viewUtilitiesApi.show(eles); return ids; }, ids => { this.viewUtilitiesApi.hide(this.cy.collection(ids.map(id => this.cy.getElementById(id)))); return ids; });
    this.expandCollapseApi = this.cy.expandCollapse({ undoable: false, animate: false, fisheye: false, layoutBy: null });
    this.contextMenusApi = this.cy.contextMenus({ menuItems: this.#contextMenuItems() });
    if (this.cy.nodes().length > 50) {
      this.navigatorContainer = document.createElement('div'); this.navigatorContainer.className = 'cytoscape-navigator-container';
      this.container.parentElement?.append(this.navigatorContainer);
      this.navigatorInstance = this.cy.navigator({ container: this.navigatorContainer, viewLiveFramerate: false, thumbnailEventFramerate: 30, rerenderDelay: 100 });
    }
    this.pluginDiagnostics = {
      layoutUtilities: Boolean(this.layoutUtilitiesApi), viewUtilities: Boolean(this.viewUtilitiesApi), undoRedo: Boolean(this.undoRedoApi),
      contextMenus: Boolean(this.contextMenusApi), popper: typeof this.cy.nodes()[0]?.popper === 'function', navigator: Boolean(this.navigatorInstance),
      expandCollapse: Boolean(this.expandCollapseApi), complexityManagement: { available: false, reason: 'no npm package published' }, automove: Boolean(this.cy.automove)
    };
  }

  #contextMenuItems() {
    return [
      { id: 'expand-one-hop', content: '展开一跳', selector: 'node', onClickFunction: event => this.callbacks.onNodeAction?.(event.target.id(), INTERACTION_ACTIONS.EXPAND_OR_COLLAPSE) },
      { id: 'collapse-expansion', content: '收起本次展开', selector: 'node', onClickFunction: event => this.callbacks.onCollapse?.(event.target.id()) },
      { id: 'pin-node', content: '固定', selector: 'node', onClickFunction: event => this.pin(event.target.id()) },
      { id: 'unpin-node', content: '解除固定', selector: 'node', onClickFunction: event => this.unpin(event.target.id()) },
      { id: 'hide-node', content: '隐藏', selector: 'node', onClickFunction: event => this.hide(event.target.id()) },
      { id: 'detail-node', content: '查看详情', selector: 'node', onClickFunction: event => this.onSelect?.(event.target.id()) },
      { id: 'fit-neighborhood', content: 'fit neighborhood', selector: 'node', onClickFunction: event => { this.selectedNodeId = event.target.id(); this.fitSelectedNeighborhood(); } },
      { id: 'detail-edge', content: '查看详情', selector: 'edge', onClickFunction: event => this.callbacks.onRelationshipSelect?.(event.target.id()) },
      { id: 'highlight-edge-endpoints', content: '突出端点', selector: 'edge', onClickFunction: event => this.viewUtilitiesApi.highlightNeighbors(event.target) },
      { id: 'hide-edge', content: '本地隐藏', selector: 'edge', onClickFunction: event => this.viewUtilitiesApi.hide(event.target) }
    ];
  }

  #bindEvents() {
    this.cy.on('tap', 'node', event => this.onSelect?.(event.target.id()));
    this.cy.on('tap', 'edge', event => this.callbacks.onRelationshipSelect?.(event.target.id()));
    this.cy.on('mouseover', 'node', event => { this.hoveredNodeId = event.target.id(); this.viewUtilitiesApi.highlight(event.target); this.#showTooltip(event.target); });
    this.cy.on('mouseout', 'node', event => { if (this.hoveredNodeId === event.target.id()) this.hoveredNodeId = null; this.viewUtilitiesApi.removeHighlights(event.target); this.#hideTooltip(event.target); });
    this.cy.on('mouseover', 'edge', event => { this.hoveredEdgeId = event.target.id(); this.viewUtilitiesApi.highlight(event.target); this.#showTooltip(event.target); });
    this.cy.on('mouseout', 'edge', event => { if (this.hoveredEdgeId === event.target.id()) this.hoveredEdgeId = null; this.viewUtilitiesApi.removeHighlights(event.target); this.#hideTooltip(event.target); });
    this.cy.on('dbltap', 'node', event => this.callbacks.onNodeAction?.(event.target.id(), INTERACTION_ACTIONS.EXPAND_OR_COLLAPSE));
    this.cy.on('select unselect position lock unlock', 'node, edge', () => this.#refreshVisualState());
    this.cy.on('free', 'node', event => { this.pluginDiagnostics.lastDrag = { id: event.target.id(), position: { ...event.target.position() } }; });
  }

  #showTooltip(ele) {
    if (!ele?.popper) return;
    this.#hideTooltip(ele);
    const content = document.createElement('div'); content.className = 'cytoscape-tooltip';
    content.textContent = ele.isNode() ? `${ele.data('caption')} · ${ele.data('kind')}` : ele.data('type');
    document.body.append(content);
    this.tooltipInstances.set(ele.id(), ele.popper({ content, popper: { placement: 'top' } }));
  }

  #hideTooltip(ele) {
    const key = ele?.id?.(); const instance = this.tooltipInstances.get(key);
    instance?.destroy?.(); this.tooltipInstances.delete(key);
  }

  #refreshLabels() {
    if (!this.cy) return;
    this.cy.nodes().forEach(node => {
      const active = node.id() === this.selectedNodeId || node.id() === this.hoveredNodeId || node.selected();
      const label = resolveNodeLabel(this.viewerConfig.node.labelMode, { caption: node.data('fullCaption'), displayCaption: node.data('viewerLabel') }, { active });
      node.data('viewerLabel', label.text);
    });
    this.cy.edges().forEach(edge => {
      const active = edge.id() === this.selectedEdgeId || edge.id() === this.hoveredEdgeId || edge.selected();
      edge.data('viewerLabel', resolveEdgeLabel(this.viewerConfig.edge.labelMode, edge.data('type'), relationshipCaptionForLength(edge.data('type'), edgeDistance(this.cy, { source: edge.source().id(), target: edge.target().id() }), this.viewerConfig.edge.fontSize), { active }));
    });
  }

  #refreshVisualState() {
    if (!this.cy) return;
    this.#refreshLabels();
    this.cy.nodes().forEach(node => {
      const selected = node.id() === this.selectedNodeId;
      const neighbor = Boolean(this.selectedNodeId && node.neighborhood().nodes().some(item => item.id() === this.selectedNodeId));
      const path = this.pathNodes.has(node.id());
      node.toggleClass('selected', selected).toggleClass('neighbor', neighbor).toggleClass('path', path).toggleClass('inactive', Boolean(this.selectedNodeId || this.pathEdges.size) && !selected && !neighbor && !path);
    });
    this.cy.edges().forEach(edge => {
      const related = Boolean(this.selectedNodeId && (edge.source().id() === this.selectedNodeId || edge.target().id() === this.selectedNodeId));
      edge.toggleClass('selected', edge.id() === this.selectedEdgeId).toggleClass('related', related).toggleClass('path', this.pathEdges.has(edge.id())).toggleClass('inactive', Boolean(this.selectedNodeId || this.pathEdges.size) && !related && !this.pathEdges.has(edge.id()));
    });
  }

  #updatePathNodes(relationships) {
    this.pathNodes = new Set();
    for (const edge of relationships) if (this.pathEdges.has(edge.id)) { this.pathNodes.add(edge.source); this.pathNodes.add(edge.target); }
  }

  async #applyLayout(policy, { context = 'FULL_QUERY', anchorId = null, variant = null, constraints = {}, fit = false } = {}) {
    if (!this.cy) return;
    this.activeLayout?.stop?.();
    const effective = chooseCytoscapeLayout(policy, this.cy, { context });
    this.layoutContext = context; this.lastLayout = effective;
    if (effective === 'KEEP') { this.lastLayoutOptions = { name: 'preset', fit: false }; return; }
    const options = cytoscapeLayoutOptions(effective, this.cy, { context, anchorId, variant, constraints });
    options.fit = Boolean(fit);
    this.lastLayoutOptions = diagnosticClone({ ...options, fixedNodeConstraint: options.fixedNodeConstraint?.map(item => item.nodeId) });
    this.layoutHistory.push(effective);
    this.activeLayout = this.cy.layout(options);
    await new Promise((resolve, reject) => {
      let settled = false;
      const finish = () => { settled = true; resolve(); };
      this.activeLayout.one('layoutstop', finish);
      try { this.activeLayout.run(); } catch (error) { reject(error); return; }
      if (effective === 'D3_FORCE' || effective === 'COLA' || effective === 'SPREAD') setTimeout(() => { if (!settled) { this.activeLayout.stop?.(); resolve(); } }, 2500);
    }).catch(error => { this.technicalErrors.push(`${effective}: ${error.message}`); throw error; }).finally(() => { this.activeLayout = null; });
  }

  #style() {
    const node = this.viewerConfig.node; const edge = this.viewerConfig.edge;
    return [
      { selector: 'node', style: { 'background-color': 'data(color)', width: node.defaultSize, height: node.defaultSize, 'border-width': node.borderWidth, 'border-color': '#ffffff', label: 'data(viewerLabel)', color: '#1f2937', 'font-size': node.fontSize + 3, 'font-weight': node.fontWeight, 'text-valign': 'center', 'text-halign': 'center', 'text-wrap': 'ellipsis', 'text-max-width': `${node.labelMaxWidth}`, 'text-overflow-wrap': 'whitespace', 'min-zoomed-font-size': 6, 'text-rotation': 'none', 'overlay-opacity': 0, 'z-index-compare': 'manual', 'z-index': 0 } },
      { selector: 'node.selected', style: { 'background-color': '#f59e0b', 'border-color': '#7c2d12', 'border-width': 3, 'z-index': 10, 'font-weight': 600, 'text-max-width': 180 } },
      { selector: 'node.neighbor', style: { 'border-color': '#0284c7', 'border-width': 2, 'z-index': 5 } },
      { selector: 'node.path', style: { 'border-color': '#e11d48', 'border-width': 2, 'z-index': 5 } },
      { selector: 'node.inactive', style: { opacity: 0.62, 'text-opacity': 0.55 } },
      { selector: 'node.hover', style: { 'border-color': '#0f172a', 'border-width': 2 } },
      { selector: 'edge', style: { width: edge.width, 'line-color': '#94a3b8', 'line-opacity': edge.opacity, 'target-arrow-color': '#94a3b8', 'target-arrow-shape': 'triangle', 'arrow-scale': 0.75, 'curve-style': 'bezier', 'control-point-step-size': 'data(curveStep)', label: 'data(viewerLabel)', color: '#334155', 'font-size': edge.fontSize, 'font-weight': 400, 'text-rotation': 'autorotate', 'text-wrap': 'ellipsis', 'text-max-width': 90, 'text-overflow-wrap': 'whitespace', 'min-zoomed-font-size': 7, 'text-background-color': '#ffffff', 'text-background-opacity': 0.78, 'text-background-padding': 2, 'z-index-compare': 'manual', 'z-index': 0 } },
      { selector: 'edge[geometryKind = "self-loop"]', style: { 'curve-style': 'loop', 'loop-direction': '0deg', 'loop-sweep': '90deg' } },
      { selector: 'edge.selected, edge.related', style: { width: edge.selectedWidth, 'line-opacity': 0.78, 'line-color': '#0284c7', 'target-arrow-color': '#0284c7', 'z-index': 5 } },
      { selector: 'edge.path', style: { width: edge.pathWidth, 'line-opacity': 0.72, 'line-color': '#e11d48', 'target-arrow-color': '#e11d48', 'z-index': 4 } },
      { selector: 'edge.inactive', style: { 'line-opacity': 0.18, 'text-opacity': 0.18 } }
    ];
  }
}
