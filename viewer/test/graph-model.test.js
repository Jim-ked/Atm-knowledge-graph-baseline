import test from 'node:test';
import assert from 'node:assert/strict';
import { GraphModel } from '../src/core/graph-model.js';

const graph = () => ({
  schemaVersion: '1',
  nodes: [
    { id: 'a', labels: ['Airport'], kind: 'Airport', caption: 'A', properties: { code: 'A' } },
    { id: 'b', labels: ['Runway'], kind: 'Runway', caption: 'B', properties: {} }
  ],
  relationships: [{ id: 'r1', source: 'a', target: 'b', type: 'HAS_RUNWAY', properties: {} }],
  meta: { complete: true }
});

test('replace, selection and engine-neutral snapshot preserve GraphDTO fields', () => {
  const model = new GraphModel();
  model.replace(graph());
  model.select('a');
  model.highlightPath(['r1']);
  const snapshot = model.snapshot();
  assert.equal(snapshot.nodes.length, 2);
  assert.equal(snapshot.relationships.length, 1);
  assert.equal(snapshot.state.selectedNodeId, 'a');
  assert.deepEqual(snapshot.state.highlightedRelationshipIds, ['r1']);
  snapshot.nodes[0].caption = 'mutated';
  assert.equal(model.snapshot().nodes[0].caption, 'A');
  assert.equal(Object.keys(snapshot.nodes[0]).some(key => /viewer|g6/i.test(key)), false);
});

test('merge and patch add, update and remove without dangling relationships', () => {
  const model = new GraphModel(graph());
  model.merge({
    schemaVersion: '1',
    nodes: [{ id: 'c', labels: ['Direction'], kind: 'Direction', caption: 'C', properties: {} }],
    relationships: [{ id: 'r2', source: 'b', target: 'c', type: 'HAS_DIRECTION', properties: {} }],
    meta: {}
  });
  model.applyPatch({
    upsertNodes: [{ id: 'b', labels: ['Runway'], kind: 'Runway', caption: 'B2', properties: { changed: true } }],
    removeNodeIds: ['c'],
    upsertRelationships: [{ id: 'r3', source: 'a', target: 'b', type: 'ALSO_HAS', properties: {} }],
    removeRelationshipIds: ['r1']
  });
  const snapshot = model.snapshot();
  assert.deepEqual(snapshot.nodes.map(node => node.id), ['a', 'b']);
  assert.deepEqual(snapshot.relationships.map(rel => rel.id), ['r3']);
  assert.equal(snapshot.nodes[1].caption, 'B2');
});

test('rejects duplicate identities and dangling relationship endpoints', () => {
  const model = new GraphModel();
  assert.throws(() => model.replace({ ...graph(), nodes: [graph().nodes[0], graph().nodes[0]] }), /duplicate node id/);
  assert.throws(() => model.replace({ ...graph(), relationships: [{ ...graph().relationships[0], target: 'missing' }] }), /missing endpoint/);
});
