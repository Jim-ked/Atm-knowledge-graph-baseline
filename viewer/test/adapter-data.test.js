import test from 'node:test';
import assert from 'node:assert/strict';
import { toAdapterData } from '../src/core/viewer-adapter.js';

test('G6 receives normalized model data without viewer-specific fields', () => {
  const snapshot = {
    schemaVersion: '1',
    nodes: [{ id: 'n1', labels: ['Airport'], kind: 'Airport', caption: 'Z001', properties: { name: 'Test' } }],
    relationships: [], meta: {},
    state: { selectedNodeId: 'n1', highlightedRelationshipIds: [], expandedNodeIds: [] }
  };
  const data = toAdapterData(snapshot);
  assert.deepEqual(data.nodes[0], snapshot.nodes[0]);
  assert.equal(data.selectedNodeId, 'n1');
  assert.equal(JSON.stringify(data).match(/viewer|g6/gi), null);
});
