import test from 'node:test';
import assert from 'node:assert/strict';
import {
  EDGE_LABEL_MODES,
  INTERACTION_ACTIONS,
  NODE_LABEL_MODES,
  VIEWER_CONFIG,
  nodeColorKey,
  resolveEdgeLabel,
  resolveNodeLabel,
  stableNodeColor,
  withLabelModes
} from '../src/config/viewer-config.js';

test('viewer config centralizes node, edge, color and interaction defaults', () => {
  assert.deepEqual(NODE_LABEL_MODES, ['AUTO', 'INSIDE', 'OUTSIDE', 'HIDDEN']);
  assert.deepEqual(EDGE_LABEL_MODES, ['AUTO', 'VISIBLE', 'HIDDEN']);
  assert.equal(VIEWER_CONFIG.node.labelMode, 'AUTO');
  assert.equal(VIEWER_CONFIG.edge.labelMode, 'AUTO');
  assert.equal(VIEWER_CONFIG.interaction.doubleClick, INTERACTION_ACTIONS.EXPAND_OR_COLLAPSE);
  assert.equal(VIEWER_CONFIG.interaction.dragEnd, INTERACTION_ACTIONS.PIN);
  assert.equal(VIEWER_CONFIG.color.strategy, 'KIND_OR_PRIMARY_LABEL');
});

test('stable node color uses kind then primary label and supports overrides', () => {
  const route = { kind: 'Route', labels: ['AviationBaseObject'] };
  const routeAgain = { kind: 'Route', labels: ['OtherLabel'] };
  const labelOnly = { kind: null, labels: ['Route'] };
  assert.equal(nodeColorKey(route), 'Route');
  assert.equal(nodeColorKey(labelOnly), 'Route');
  assert.equal(stableNodeColor(route), stableNodeColor(routeAgain));
  assert.equal(stableNodeColor(route, { ...VIEWER_CONFIG.color, overrides: { Route: '#123456' } }), '#123456');
});

test('node label modes preserve full captions outside GraphDTO', () => {
  const node = { caption: 'R001:NAVIGATION-LONG', displayCaption: 'R001…ONG' };
  assert.deepEqual(resolveNodeLabel('AUTO', node), { text: 'R001…ONG', placement: 'center' });
  assert.deepEqual(resolveNodeLabel('AUTO', node, { active: true }), {
    text: 'R001:NAVIGATION-LONG', placement: 'center'
  });
  assert.deepEqual(resolveNodeLabel('INSIDE', node), { text: 'R001…ONG', placement: 'center' });
  assert.deepEqual(resolveNodeLabel('OUTSIDE', node), { text: 'R001:NAVIGATION-LONG', placement: 'bottom' });
  assert.deepEqual(resolveNodeLabel('HIDDEN', node), { text: '', placement: 'center' });
  assert.deepEqual(resolveNodeLabel('HIDDEN', node, { active: true }), {
    text: 'R001:NAVIGATION-LONG', placement: 'center'
  });
});

test('edge label modes expose labels only for the configured context', () => {
  assert.equal(resolveEdgeLabel('AUTO', 'HAS_SEGMENT', 'HAS…ENT'), '');
  assert.equal(resolveEdgeLabel('AUTO', 'HAS_SEGMENT', 'HAS…ENT', { related: true }), 'HAS…ENT');
  assert.equal(resolveEdgeLabel('AUTO', 'HAS_SEGMENT', 'HAS…ENT', { path: true }), 'HAS…ENT');
  assert.equal(resolveEdgeLabel('VISIBLE', 'HAS_SEGMENT', 'HAS…ENT'), 'HAS…ENT');
  assert.equal(resolveEdgeLabel('HIDDEN', 'HAS_SEGMENT', 'HAS…ENT'), '');
  assert.equal(resolveEdgeLabel('HIDDEN', 'HAS_SEGMENT', 'HAS…ENT', { active: true }), 'HAS_SEGMENT');
});

test('label mode updates are validated without mutating the default config', () => {
  const updated = withLabelModes(VIEWER_CONFIG, 'OUTSIDE', 'VISIBLE');
  assert.equal(updated.node.labelMode, 'OUTSIDE');
  assert.equal(updated.edge.labelMode, 'VISIBLE');
  assert.equal(VIEWER_CONFIG.node.labelMode, 'AUTO');
  assert.throws(() => withLabelModes(VIEWER_CONFIG, 'BAD', 'AUTO'), /node label mode/);
  assert.throws(() => withLabelModes(VIEWER_CONFIG, 'AUTO', 'BAD'), /edge label mode/);
});
