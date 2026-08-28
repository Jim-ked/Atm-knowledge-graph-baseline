import test from 'node:test';
import assert from 'node:assert/strict';
import { ResultState } from '../src/core/result-view.js';

const graph = { schemaVersion: '1', nodes: [{ id: 'a', kind: 'A', labels: ['A'], caption: 'A1', properties: { p: 1 } }, { id: 'b', kind: 'B', labels: ['B'], caption: 'B1', properties: {} }], relationships: [{ id: 'r', source: 'a', target: 'b', type: 'LINK', properties: {} }], meta: {} };
test('ResultState chooses graph/table defaults and preserves raw response', () => {
  const state = new ResultState({ ...graph, rows: [] });
  assert.equal(state.defaultView(), 'graph');
  assert.equal(state.tableRows().length, 3);
  const scalar = new ResultState({ columns: ['count'], rows: [{ count: 2 }] });
  assert.equal(scalar.defaultView(), 'table');
  assert.deepEqual(scalar.raw.rows, [{ count: 2 }]);
});
test('ResultState filters only the displayed graph', () => {
  const state = new ResultState(graph);
  state.filters.classIri = 'A';
  assert.equal(state.filteredGraph().nodes.length, 1);
  assert.equal(state.graph.nodes.length, 2);
  state.filters.classIri = '';
  state.filters.relationshipType = 'NOPE';
  assert.equal(state.filteredGraph().relationships.length, 0);
});
