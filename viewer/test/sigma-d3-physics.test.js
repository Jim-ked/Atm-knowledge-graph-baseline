import test from 'node:test';
import assert from 'node:assert/strict';
import { MultiGraph } from 'graphology';
import {
  SigmaD3Physics,
  SIGMA_LAYOUT_UNITS_PER_SIZE,
  sigmaLayoutRadius
} from '../src/adapters/sigma-d3-physics.js';

function graphFixture() {
  const graph = new MultiGraph({ type: 'directed' });
  for (const [id, x, y, size] of [
    ['a', 100, 100, 12], ['b', 220, 100, 12], ['c', 160, 220, 16], ['d', 40, 220, 12]
  ]) graph.addNode(id, { x, y, size, fixed: false });
  graph.addDirectedEdgeWithKey('r1', 'a', 'b');
  graph.addDirectedEdgeWithKey('r2', 'a', 'b');
  graph.addDirectedEdgeWithKey('r3', 'b', 'a');
  graph.addDirectedEdgeWithKey('r4', 'a', 'c');
  graph.addDirectedEdgeWithKey('r5', 'b', 'c');
  graph.addDirectedEdgeWithKey('r6', 'a', 'd');
  graph.addDirectedEdgeWithKey('loop', 'd', 'd');
  return graph;
}

test('Sigma D3 physics deduplicates undirected pairs without changing display relationships', () => {
  const graph = graphFixture();
  const physics = new SigmaD3Physics({ width: 800, height: 600 });
  physics.reconcile(graph, { mode: 'FULL_QUERY' });
  const diagnostic = physics.diagnostics(graph);
  assert.equal(graph.size, 7);
  assert.equal(diagnostic.displayRelationshipCount, 7);
  assert.equal(diagnostic.physicsLinkCount, 4);
  assert.equal(diagnostic.linkStrength.uniqueCount, 2);
  physics.stop();
});

test('Sigma visual size is explicitly converted into layout and collision radii', () => {
  const graph = graphFixture();
  const physics = new SigmaD3Physics({ width: 800, height: 600 });
  physics.reconcile(graph, { mode: 'FULL_QUERY' });
  const node = physics.getNode('a');
  assert.equal(sigmaLayoutRadius(12), 12 * SIGMA_LAYOUT_UNITS_PER_SIZE);
  assert.equal(node.layoutRadius, sigmaLayoutRadius(12));
  assert.ok(physics.collisionRadius(node) > node.layoutRadius);
  assert.ok(physics.preferredDistance(node, physics.getNode('b'))
    >= physics.collisionRadius(node) + physics.collisionRadius(physics.getNode('b')));
  physics.stop();
});

test('incremental reconcile reuses old physics nodes and seeds new nodes outside collision', () => {
  const graph = graphFixture();
  const physics = new SigmaD3Physics({ width: 800, height: 600 });
  physics.reconcile(graph, { mode: 'FULL_QUERY' });
  physics.settle(120);
  const anchor = physics.getNode('a');
  const reference = physics.getNode('b');
  const before = { x: reference.x, y: reference.y };
  graph.addNode('new', { x: anchor.x, y: anchor.y, size: 12, fixed: false });
  graph.addDirectedEdgeWithKey('new-edge', 'a', 'new');
  const result = physics.reconcile(graph, { mode: 'INCREMENTAL_EXPAND', anchorId: 'a' });
  const created = physics.getNode('new');
  assert.equal(physics.getNode('b'), reference);
  assert.deepEqual({ x: reference.x, y: reference.y }, before);
  assert.equal(result.initialCollisionViolations, 0);
  assert.ok(Math.hypot(created.x - anchor.x, created.y - anchor.y)
    >= physics.collisionRadius(created) + physics.collisionRadius(anchor));
  physics.stop();
});

test('full query starts a fresh physics workspace without stale pins or node objects', () => {
  const graph = graphFixture();
  const physics = new SigmaD3Physics({ width: 800, height: 600 });
  physics.reconcile(graph, { mode: 'FULL_QUERY' });
  const oldNode = physics.getNode('a');
  physics.pin('a', oldNode.x + 10, oldNode.y + 10);
  physics.reconcile(graph, { mode: 'FULL_QUERY' });
  assert.notEqual(physics.getNode('a'), oldNode);
  assert.equal(physics.diagnostics(graph).fixedNodeCount, 0);
  assert.equal(physics.coordinateSnapshot().every(node => node.fx == null && node.fy == null), true);
  physics.stop();
});
