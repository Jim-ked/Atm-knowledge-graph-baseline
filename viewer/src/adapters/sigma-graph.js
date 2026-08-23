import { MultiGraph } from 'graphology';
import { annotateRelationshipGeometry, uniqueShortCaptions } from './g6-visual-geometry.js';
import { stableNodeColor, VIEWER_CONFIG } from '../config/viewer-config.js';

export const SIGMA_LAYOUT_POLICIES = Object.freeze([
  'AUTO', 'D3_TOPOLOGY_FORCE', 'GRAPHOLOGY_FORCE', 'FORCE', 'FORCE_ATLAS2', 'NOVERLAP', 'KEEP'
]);

const SPIRAL_ANGLE = Math.PI * (3 - Math.sqrt(5));

export function initialSigmaPosition(index, count, width = 800, height = 600, anchor = null) {
  const angle = index * SPIRAL_ANGLE;
  const radius = anchor ? 70 + Math.sqrt(index) * 24 : 80 + Math.sqrt(index) * 42;
  const center = anchor ?? { x: width / 2, y: height / 2 };
  return { x: center.x + Math.cos(angle) * radius, y: center.y + Math.sin(angle) * radius };
}

function relationPair(source, target) {
  return [source, target].sort().join('\u0000');
}

function graphHasUsablePositions(graph) {
  return graph.nodes().every(node => Number.isFinite(graph.getNodeAttribute(node, 'x'))
    && Number.isFinite(graph.getNodeAttribute(node, 'y')));
}

export function chooseSigmaLayout(policy, graph, { context = 'FULL_QUERY', preferKeep = false } = {}) {
  if (!SIGMA_LAYOUT_POLICIES.includes(policy)) throw new Error(`unknown Sigma layout policy: ${policy}`);
  if (policy !== 'AUTO') return policy;
  if (context === 'USER_POSITIONED' || (preferKeep && context !== 'FULL_QUERY' && graphHasUsablePositions(graph))) return 'KEEP';
  if (context === 'INCREMENTAL_EXPAND') return graph.order <= 40 ? 'D3_TOPOLOGY_FORCE' : 'FORCE_ATLAS2';
  return graph.order <= 40 ? 'D3_TOPOLOGY_FORCE' : 'FORCE_ATLAS2';
}

export function createSigmaGraph(snapshot, {
  width = 800,
  height = 600,
  positions = new Map(),
  colorConfig = VIEWER_CONFIG.color,
  nodeSize = 14,
  edgeSize = 1.2,
  edgeLabelMode = 'AUTO'
} = {}) {
  const graph = new MultiGraph({ type: 'directed' });
  const captions = uniqueShortCaptions(snapshot.nodes, 8);
  const nodes = snapshot.nodes;
  nodes.forEach((node, index) => {
    const previous = positions.get(node.id);
    const point = previous ?? initialSigmaPosition(index, nodes.length, width, height);
    graph.addNode(node.id, {
      x: point.x,
      y: point.y,
      size: nodeSize,
      color: stableNodeColor(node, colorConfig),
      borderColor: '#ffffff',
      type: 'border',
      label: captions.get(node.id),
      fullCaption: node.caption,
      kind: node.kind,
      labels: [...node.labels],
      properties: structuredClone(node.properties),
      original: structuredClone(node),
      viewerLabelMode: 'AUTO',
      viewerEdgeLabelMode: edgeLabelMode,
      degree: 0,
      zIndex: 0
    });
  });

  const annotated = annotateRelationshipGeometry(snapshot.relationships);
  const pairCounts = new Map();
  for (const edge of annotated) {
    const pair = relationPair(edge.source, edge.target);
    pairCounts.set(pair, (pairCounts.get(pair) ?? 0) + 1);
  }
  for (const edge of annotated) {
    const curved = (pairCounts.get(relationPair(edge.source, edge.target)) ?? 0) > 1;
    graph.addDirectedEdgeWithKey(edge.id, edge.source, edge.target, {
      type: curved ? 'curved-arrow' : 'arrow',
      size: edgeSize,
      color: '#94a3b8',
      label: edge.type,
      original: structuredClone(edge),
      source: edge.source,
      target: edge.target,
      zIndex: 0
    });
    graph.setNodeAttribute(edge.source, 'degree', graph.getNodeAttribute(edge.source, 'degree') + 1);
    graph.setNodeAttribute(edge.target, 'degree', graph.getNodeAttribute(edge.target, 'degree') + 1);
  }
  indexParallelEdgesIndexLocal(graph);
  applyParallelCurvatures(graph);
  return graph;
}

function indexParallelEdgesIndexLocal(graph) {
  const groups = new Map();
  graph.forEachEdge((edge, attributes, source, target) => {
    const key = relationPair(source, target);
    const list = groups.get(key) ?? [];
    list.push(edge);
    groups.set(key, list);
  });
  for (const edges of groups.values()) {
    const min = 0;
    const max = edges.length - 1;
    edges.forEach((edge, index) => {
      graph.setEdgeAttribute(edge, 'parallelIndex', index);
      graph.setEdgeAttribute(edge, 'parallelMinIndex', min);
      graph.setEdgeAttribute(edge, 'parallelMaxIndex', max);
    });
  }
}

export function applyParallelCurvatures(graph) {
  graph.forEachEdge((edge, attributes) => {
    const min = attributes.parallelMinIndex;
    const max = attributes.parallelMaxIndex;
    const index = attributes.parallelIndex;
    if (!Number.isFinite(min) || !Number.isFinite(max) || min === max) {
      graph.setEdgeAttribute(edge, 'curvature', 0);
      return;
    }
    const centered = index - (min + max) / 2;
    graph.setEdgeAttribute(edge, 'curvature', centered * 0.22);
  });
  return graph;
}

function visibleEdgeLabel(mode, attributes, state) {
  if (mode === 'HIDDEN') return state.active ? attributes.label : '';
  if (mode === 'VISIBLE') return attributes.label;
  return state.active ? attributes.label : '';
}

export function prepareSigmaReducerState(graph, {
  selectedNodeId = null,
  hoveredNodeId = null,
  selectedEdgeId = null,
  hoveredEdgeId = null,
  pathNodes = new Set(),
  pathEdges = new Set(),
  hiddenNodes = new Set(),
  hiddenEdges = new Set(),
  nodeLabelMode = 'AUTO',
  edgeLabelMode = 'AUTO'
} = {}) {
  const neighborNodeIds = new Set(selectedNodeId ? graph.neighbors(selectedNodeId) : []);
  const neighborEdgeIds = new Set(selectedNodeId ? graph.edges(selectedNodeId) : []);
  return {
    selectedNodeId, hoveredNodeId, selectedEdgeId, hoveredEdgeId,
    pathNodeIds: pathNodes, pathEdgeIds: pathEdges,
    hiddenNodeIds: hiddenNodes, hiddenEdgeIds: hiddenEdges,
    neighborNodeIds, neighborEdgeIds, nodeLabelMode, edgeLabelMode
  };
}

export function getSigmaReducers(graph, options = {}) {
  const state = options.state ?? prepareSigmaReducerState(graph, options);
  const nodeReducer = (node, data) => {
    const selected = node === state.selectedNodeId;
    const hovered = node === state.hoveredNodeId;
    const path = state.pathNodeIds.has(node);
    const neighbor = state.neighborNodeIds.has(node);
    const active = selected || hovered || path;
    const hiddenLabel = state.nodeLabelMode === 'HIDDEN' && !active;
    return {
      ...data,
      size: selected ? data.size + 3 : data.size,
      color: selected ? '#f59e0b' : data.color,
      borderColor: selected || hovered ? '#0f172a' : data.borderColor,
      borderSize: selected ? '16%' : '10%',
      highlighted: active,
      forceLabel: selected || hovered,
      hidden: state.hiddenNodeIds.has(node),
      label: hiddenLabel ? null : (active ? data.fullCaption : data.label),
      opacity: selected || hovered || path || neighbor ? 1 : 0.5,
      zIndex: selected || hovered ? 4 : path ? 3 : neighbor ? 2 : 0
    };
  };
  const edgeReducer = (edge, data) => {
    const source = graph.source(edge);
    const target = graph.target(edge);
    const selected = edge === state.selectedEdgeId;
    const hovered = edge === state.hoveredEdgeId;
    const path = state.pathEdgeIds.has(edge);
    const related = state.selectedNodeId != null && (source === state.selectedNodeId || target === state.selectedNodeId);
    const active = selected || hovered || path || related;
    return {
      ...data,
      color: selected || hovered ? '#0f172a' : path ? '#e11d48' : related ? '#0284c7' : data.color,
      size: selected || hovered ? data.size + 0.9 : path || related ? data.size + 0.45 : data.size,
      label: visibleEdgeLabel(state.edgeLabelMode, data, { active }),
      hidden: state.hiddenEdgeIds.has(edge) || state.hiddenNodeIds.has(source) || state.hiddenNodeIds.has(target),
      zIndex: selected || hovered ? 4 : path ? 3 : related ? 2 : 0
    };
  };
  return { nodeReducer, edgeReducer };
}
