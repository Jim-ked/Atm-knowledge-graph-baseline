import test from 'node:test';
import assert from 'node:assert/strict';
import { toAdapterData } from '../src/core/viewer-adapter.js';

test('all engines receive the same normalized model data', () => {
  const snapshot = {
    schemaVersion: '1',
    nodes: [{ id: 'n1', labels: ['Airport'], kind: 'Airport', caption: 'Z001', properties: { name: 'Test' } }],
    relationships: [], meta: {},
    state: { selectedNodeId: 'n1', highlightedRelationshipIds: [], expandedNodeIds: [] }
  };
  const data = toAdapterData(snapshot);
  assert.deepEqual(data.nodes[0], snapshot.nodes[0]);
  assert.equal(data.selectedNodeId, 'n1');
  assert.equal(JSON.stringify(data).match(/g6|cytoscape|sigma/gi), null);
});
