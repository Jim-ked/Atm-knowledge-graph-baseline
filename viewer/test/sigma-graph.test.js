import test from 'node:test';
import assert from 'node:assert/strict';
import {
  SIGMA_LAYOUT_POLICIES,
  createSigmaGraph,
  chooseSigmaLayout,
  getSigmaReducers,
  prepareSigmaReducerState,
  initialSigmaPosition,
  applyParallelCurvatures
} from '../src/adapters/sigma-graph.js';

const dto = {
  schemaVersion: '1',
  nodes: [
    { id: 'a', labels: ['Airport'], kind: 'Airport', caption: 'Z001', properties: { code: 'Z001' } },
    { id: 'b', labels: ['Runway'], kind: 'Runway', caption: 'Z001-RW', properties: {} },
    { id: 'c', labels: ['Direction'], kind: 'Direction', caption: 'LONG-DIRECTION', properties: {} }
  ],
  relationships: [
    { id: 'r1', source: 'a', target: 'b', type: 'HAS_RUNWAY', properties: {} },
    { id: 'r2', source: 'a', target: 'b', type: 'HAS_ALT', properties: {} },
    { id: 'r3', source: 'b', target: 'a', type: 'RETURN', properties: {} },
    { id: 'r4', source: 'b', target: 'c', type: 'HAS_DIRECTION', properties: {} }
  ],
  meta: {}
};

test('GraphDTO maps to stable Graphology node and edge keys without changing DTO', () => {
  const graph = createSigmaGraph(dto, { width: 800, height: 600 });
  assert.equal(graph.order, 3);
  assert.equal(graph.size, 4);
  assert.deepEqual(graph.nodes(), ['a', 'b', 'c']);
  assert.deepEqual(graph.edges(), ['r1', 'r2', 'r3', 'r4']);
  assert.equal(graph.getNodeAttribute('a', 'original').kind, 'Airport');
  assert.equal(graph.getNodeAttribute('a', 'original').properties.code, 'Z001');
  assert.ok(Number.isFinite(graph.getNodeAttribute('a', 'x')));
  assert.ok(Number.isFinite(graph.getNodeAttribute('a', 'y')));
  assert.equal(dto.nodes[0].id, 'a');
  assert.equal(Object.keys(dto.nodes[0]).some(key => /sigma|viewer/i.test(key)), false);
});

test('parallel and bidirectional edges use curve program with separated curvature', () => {
  const graph = createSigmaGraph(dto, { width: 800, height: 600 });
  applyParallelCurvatures(graph);
  assert.equal(graph.getEdgeAttribute('r4', 'type'), 'arrow');
  assert.equal(graph.getEdgeAttribute('r1', 'type'), 'curved-arrow');
  assert.equal(graph.getEdgeAttribute('r2', 'type'), 'curved-arrow');
  assert.equal(graph.getEdgeAttribute('r3', 'type'), 'curved-arrow');
  const curvatures = ['r1', 'r2', 'r3'].map(id => graph.getEdgeAttribute(id, 'curvature'));
  assert.ok(new Set(curvatures).size >= 2);
  assert.ok(curvatures.some(value => value < 0) && curvatures.some(value => value > 0));
  assert.equal(graph.size, dto.relationships.length);
});

test('layout policy distinguishes KEEP, Force, FA2 worker, Noverlap and AUTO', () => {
  assert.deepEqual(SIGMA_LAYOUT_POLICIES,
    ['AUTO', 'D3_TOPOLOGY_FORCE', 'GRAPHOLOGY_FORCE', 'FORCE', 'FORCE_ATLAS2', 'NOVERLAP', 'KEEP']);
  const graph = createSigmaGraph(dto, { width: 800, height: 600 });
  assert.equal(chooseSigmaLayout('KEEP', graph), 'KEEP');
  assert.equal(chooseSigmaLayout('FORCE', graph), 'FORCE');
  assert.equal(chooseSigmaLayout('FORCE_ATLAS2', graph), 'FORCE_ATLAS2');
  assert.equal(chooseSigmaLayout('NOVERLAP', graph), 'NOVERLAP');
  assert.equal(chooseSigmaLayout('AUTO', graph), 'D3_TOPOLOGY_FORCE');
  assert.equal(chooseSigmaLayout('AUTO', graph, { context: 'USER_POSITIONED', preferKeep: true }), 'KEEP');
  assert.equal(chooseSigmaLayout('AUTO', graph, { context: 'INCREMENTAL_EXPAND' }), 'D3_TOPOLOGY_FORCE');
});

test('reducers express selected, hover, neighbor, path and inactive states', () => {
  const graph = createSigmaGraph(dto, { width: 800, height: 600 });
  const reducers = getSigmaReducers(graph, {
    selectedNodeId: 'a', hoveredNodeId: 'a', selectedEdgeId: null,
    pathNodes: new Set(['a', 'b']), pathEdges: new Set(['r1']),
    nodeLabelMode: 'AUTO', edgeLabelMode: 'AUTO'
  });
  const selected = reducers.nodeReducer('a', graph.getNodeAttributes('a'));
  const neighbor = reducers.nodeReducer('b', graph.getNodeAttributes('b'));
  const inactive = reducers.nodeReducer('c', graph.getNodeAttributes('c'));
  const pathEdge = reducers.edgeReducer('r1', graph.getEdgeAttributes('r1'));
  assert.equal(selected.forceLabel, true);
  assert.equal(selected.highlighted, true);
  assert.ok(neighbor.opacity > inactive.opacity);
  assert.equal(pathEdge.hidden, false);
  assert.ok(pathEdge.size > graph.getEdgeAttribute('r4', 'size'));
});

test('reducer functions reuse one mutable interaction state cache', () => {
  const graph = createSigmaGraph(dto, { width: 800, height: 600 });
  const state = prepareSigmaReducerState(graph, { selectedNodeId: 'a' });
  const reducers = getSigmaReducers(graph, { state });
  const nodeReducer = reducers.nodeReducer;
  state.selectedNodeId = 'b';
  state.neighborNodeIds = new Set(graph.neighbors('b'));
  assert.equal(reducers.nodeReducer, nodeReducer);
  assert.equal(nodeReducer('b', graph.getNodeAttributes('b')).forceLabel, true);
  assert.equal(nodeReducer('a', graph.getNodeAttributes('a')).forceLabel, false);
});

test('hidden nodes are a renderer concern and hide related edges without changing GraphDTO', () => {
  const graph = createSigmaGraph(dto, { width: 800, height: 600 });
  const reducers = getSigmaReducers(graph, { hiddenNodes: new Set(['b']) });
  assert.equal(reducers.nodeReducer('b', graph.getNodeAttributes('b')).hidden, true);
  assert.equal(reducers.edgeReducer('r1', graph.getEdgeAttributes('r1')).hidden, true);
  assert.equal(dto.nodes.length, 3);
  assert.equal(dto.relationships.length, 4);
});

test('new Sigma nodes seed near an anchor while preserving existing coordinates', () => {
  const anchor = { x: 10, y: 20 };
  const first = initialSigmaPosition(0, 3, 100, 80, anchor);
  const second = initialSigmaPosition(1, 3, 100, 80, anchor);
  assert.notDeepEqual(first, second);
  assert.ok(Math.hypot(first.x - anchor.x, first.y - anchor.y) >= 50);
});
