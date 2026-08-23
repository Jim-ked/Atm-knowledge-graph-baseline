import test from 'node:test';
import assert from 'node:assert/strict';
import {
  BROWSER_REFERENCE_FORCE_CONFIG,
  PersistentForceSimulation,
  RESTART_PRESETS
} from '../src/adapters/g6-persistent-simulation.js';

const nodes = [
  { id: 'a' },
  { id: 'b' }
];
const relationships = [
  { id: 'r1', source: 'a', target: 'b' },
  { id: 'r2', source: 'a', target: 'b' },
  { id: 'r3', source: 'b', target: 'a' }
];

test('reconcile preserves old simulation node identity and all motion state', () => {
  const physics = new PersistentForceSimulation({ width: 800, height: 600 });
  physics.reconcile(nodes, relationships);
  const original = physics.getNode('a');
  Object.assign(original, { x: 101, y: 202, vx: 3, vy: 4, fx: 105, fy: 205 });

  const result = physics.reconcile([...nodes, { id: 'c' }], [
    ...relationships,
    { id: 'r4', source: 'b', target: 'c' }
  ], { anchorId: 'b' });

  assert.strictEqual(physics.getNode('a'), original);
  assert.deepEqual(
    (({ x, y, vx, vy, fx, fy }) => ({ x, y, vx, vy, fx, fy }))(physics.getNode('a')),
    { x: 101, y: 202, vx: 3, vy: 4, fx: 105, fy: 205 }
  );
  assert.equal(result.createdNodes, 1);
  assert.equal(result.reusedNodes, 2);
  assert.equal(physics.creationCount, 1);
  physics.stop();
});

test('new nodes start at preferred link distance without initial collision violations', () => {
  const physics = new PersistentForceSimulation({ width: 800, height: 600, visualNodeSize: 40 });
  physics.reconcile(nodes, relationships);
  Object.assign(physics.getNode('b'), { x: 320, y: 180 });
  const renderedRelationshipCount = relationships.length + 1;

  physics.reconcile([...nodes, { id: 'c' }], [
    ...relationships,
    { id: 'r4', source: 'b', target: 'c' }
  ], { anchorId: 'b' });

  const created = physics.getNode('c');
  const initialDistance = Math.hypot(created.x - 320, created.y - 180);
  const diagnostic = physics.diagnostics();
  assert.equal(diagnostic.geometry.visualRadius, 20);
  assert.equal(diagnostic.geometry.collisionRadius, 32);
  assert.equal(diagnostic.geometry.preferredLinkDistance, 100);
  assert.equal(diagnostic.lastReconcile.initialCollisionViolations, 0);
  assert.ok(initialDistance >= 64);
  assert.ok(initialDistance / 100 >= 0.95 && initialDistance / 100 <= 1.05);
  assert.ok(diagnostic.lastReconcile.newLinkInitialDistanceRatio.p50 >= 0.95);
  assert.ok(diagnostic.lastReconcile.newLinkInitialDistanceRatio.p50 <= 1.05);
  assert.equal(physics.physicsLinks.length, 2);
  assert.equal(renderedRelationshipCount, 4);
  physics.stop();
});

test('visual-size-driven geometry survives force reconfiguration', () => {
  const physics = new PersistentForceSimulation({ width: 800, height: 600, visualNodeSize: 40 });
  physics.reconcile([{ id: 'small', visualSize: 40 }, { id: 'large', visualSize: 60 }], [
    { id: 's-l', source: 'small', target: 'large' }
  ]);

  assert.equal(physics.collisionRadiusFor(physics.getNode('small')), 32);
  assert.equal(physics.collisionRadiusFor(physics.getNode('large')), 42);
  assert.equal(physics.preferredDistance(physics.getNode('small'), physics.getNode('large')), 110);

  physics.configure({ manyBodyStrength: -160 });

  assert.equal(physics.collisionRadiusFor(physics.getNode('small')), 32);
  assert.equal(physics.collisionRadiusFor(physics.getNode('large')), 42);
  assert.equal(physics.preferredDistance(physics.getNode('small'), physics.getNode('large')), 110);
  physics.stop();
});

test('new nodes choose a locally empty direction around their anchor', () => {
  const physics = new PersistentForceSimulation({ width: 800, height: 600 });
  physics.reconcile([{ id: 'anchor' }, { id: 'occupied' }], [
    { id: 'existing', source: 'anchor', target: 'occupied' }
  ]);
  Object.assign(physics.getNode('anchor'), { x: 300, y: 300 });
  Object.assign(physics.getNode('occupied'), {
    x: 300 + Math.cos(2) * 60,
    y: 300 + Math.sin(2) * 60
  });

  physics.reconcile([{ id: 'anchor' }, { id: 'occupied' }, { id: 'new' }], [
    { id: 'existing', source: 'anchor', target: 'occupied' },
    { id: 'new-link', source: 'anchor', target: 'new' }
  ], { anchorId: 'anchor' });

  const newVector = {
    x: physics.getNode('new').x - physics.getNode('anchor').x,
    y: physics.getNode('new').y - physics.getNode('anchor').y
  };
  const occupiedVector = {
    x: physics.getNode('occupied').x - physics.getNode('anchor').x,
    y: physics.getNode('occupied').y - physics.getNode('anchor').y
  };
  assert.ok(newVector.x * occupiedVector.x + newVector.y * occupiedVector.y < 0);
  const distance = Math.hypot(
    physics.getNode('new').x - physics.getNode('anchor').x,
    physics.getNode('new').y - physics.getNode('anchor').y
  );
  assert.ok(distance >= 95 && distance <= 105);
  assert.equal(physics.diagnostics().lastReconcile.initialCollisionViolations, 0);
  physics.stop();
});

test('native forceLink strength follows deduplicated physical degree topology', () => {
  const physics = new PersistentForceSimulation({ width: 800, height: 600, visualNodeSize: 40 });
  physics.reconcile(
    [{ id: 'a' }, { id: 'b' }, { id: 'c' }, { id: 'd' }],
    [
      { id: 'ab-1', source: 'a', target: 'b' },
      { id: 'ab-duplicate', source: 'a', target: 'b' },
      { id: 'ac', source: 'a', target: 'c' },
      { id: 'ad', source: 'a', target: 'd' },
      { id: 'bc', source: 'b', target: 'c' }
    ]
  );
  const diagnostic = physics.diagnostics();

  assert.equal(diagnostic.physicsLinkCount, 4);
  assert.deepEqual(diagnostic.physicalDegreeDistribution, { 1: 1, 2: 2, 3: 1 });
  assert.equal(diagnostic.linkStrength.min, 0.5);
  assert.equal(diagnostic.linkStrength.max, 1);
  assert.equal(diagnostic.linkStrength.uniqueCount, 2);
  physics.stop();
});

test('physics uses weak forceX and forceY instead of forceCenter', () => {
  const physics = new PersistentForceSimulation({ width: 800, height: 600 });
  assert.equal(physics.simulation.force('center'), undefined);
  assert.ok(physics.simulation.force('x'));
  assert.ok(physics.simulation.force('y'));
  assert.equal(physics.diagnostics().axisStrength, 0.03);
  physics.stop();
});

test('restart presets, fixed state, unpin and Browser reference values are explicit', () => {
  const physics = new PersistentForceSimulation({ width: 800, height: 600 });
  physics.reconcile(nodes, relationships);

  physics.restart('browser-like');
  assert.equal(physics.simulation.alpha(), RESTART_PRESETS['browser-like']);
  physics.restart('gentle');
  assert.equal(physics.simulation.alpha(), RESTART_PRESETS.gentle);

  physics.pin('a', 10, 20);
  assert.deepEqual([physics.getNode('a').fx, physics.getNode('a').fy], [10, 20]);
  physics.unpin('a');
  assert.deepEqual([physics.getNode('a').fx, physics.getNode('a').fy], [null, null]);

  assert.equal(BROWSER_REFERENCE_FORCE_CONFIG.velocityDecay, 0.4);
  assert.equal(BROWSER_REFERENCE_FORCE_CONFIG.manyBodyStrength, -400);
  assert.equal(BROWSER_REFERENCE_FORCE_CONFIG.centerStrength, 0.03);
  assert.equal(BROWSER_REFERENCE_FORCE_CONFIG.nodeGap, 25);
  assert.equal(BROWSER_REFERENCE_FORCE_CONFIG.linkGap, 90);
  physics.stop();
});
