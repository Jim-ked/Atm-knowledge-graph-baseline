import test from 'node:test';
import assert from 'node:assert/strict';
import { buildGraphElementStates } from '../src/core/graph-selection.js';

const graph = () => ({
  nodes: [{ id: 'a' }, { id: 'b' }, { id: 'c' }, { id: 'd' }],
  relationships: [
    { id: 'ab', source: 'a', target: 'b' },
    { id: 'cd', source: 'c', target: 'd' }
  ]
});

test('ordinary node selection emphasizes its local context without inactivating the distant graph', () => {
  const states = buildGraphElementStates({
    ...graph(), selectedNodeId: 'a', selectedRelationshipId: null, highlightedRelationshipIds: []
  });

  assert.deepEqual(states.a, ['selected']);
  assert.deepEqual(states.b, ['neighbor']);
  assert.deepEqual(states.ab, ['related']);
  assert.deepEqual(states.c, []);
  assert.deepEqual(states.d, []);
  assert.deepEqual(states.cd, []);
});

test('relationship selection emphasizes only that relationship and keeps all other context normal', () => {
  const states = buildGraphElementStates({
    ...graph(), selectedNodeId: null, selectedRelationshipId: 'ab', highlightedRelationshipIds: []
  });

  assert.deepEqual(states.ab, ['selected']);
  assert.deepEqual(states.cd, []);
  assert.deepEqual(states.a, []);
  assert.deepEqual(states.d, []);
});

test('path highlighting remains a focus mode that inactivates non-path elements', () => {
  const states = buildGraphElementStates({
    ...graph(), selectedNodeId: null, selectedRelationshipId: null, highlightedRelationshipIds: ['ab']
  });

  assert.deepEqual(states.a, ['path']);
  assert.deepEqual(states.b, ['path']);
  assert.deepEqual(states.ab, ['highlighted']);
  assert.deepEqual(states.c, ['inactive']);
  assert.deepEqual(states.d, ['inactive']);
  assert.deepEqual(states.cd, ['inactive']);
});

test('expanded nodes do not make unrelated old nodes or relationships inactive', () => {
  const expanded = graph();
  expanded.nodes.push({ id: 'new' });
  expanded.relationships.push({ id: 'an', source: 'a', target: 'new' });

  const states = buildGraphElementStates({
    ...expanded, selectedNodeId: 'a', selectedRelationshipId: null, highlightedRelationshipIds: []
  });

  assert.deepEqual(states.new, ['neighbor']);
  assert.deepEqual(states.an, ['related']);
  assert.deepEqual(states.c, []);
  assert.deepEqual(states.cd, []);
});
