import test from 'node:test';
import assert from 'node:assert/strict';
import { ResultState } from '../src/core/result-view.js';
import { SchemaCatalog } from '../src/core/schema-catalog.js';

const graph = { schemaVersion: '1', nodes: [{ id: 'a', kind: 'A', labels: ['A'], caption: 'A1', properties: { p: 1 } }, { id: 'b', kind: 'B', labels: ['B'], caption: 'B1', properties: {} }], relationships: [{ id: 'r', source: 'a', target: 'b', type: 'LINK', properties: {} }], meta: {} };
test('ResultState chooses graph/table defaults and preserves raw response', () => {
  const state = new ResultState({ ...graph, rows: [] });
  assert.equal(state.defaultView(), 'graph');
  assert.equal(state.tableRows().length, 3);
  const scalar = new ResultState({ columns: ['count'], rows: [{ count: 2 }] });
  assert.equal(scalar.defaultView(), 'table');
  assert.deepEqual(scalar.raw.rows, [{ count: 2 }]);
  const catalog = new SchemaCatalog({ classLabels: { A: '甲类' }, objectPropertyLabels: { LINK: '关联' } });
  const rows = state.tableRows(catalog);
  assert.equal(rows[0].category, '甲类');
  assert.equal(rows[2].identifier, '关联');
  assert.deepEqual(scalar.tableRows(catalog), [{ count: 2 }]);
});
test('ResultState filters only the displayed graph', () => {
  const state = new ResultState(graph);
  state.filters.classIri = 'A';
  assert.equal(state.filteredGraph().nodes.length, 1);
  assert.equal(state.graph.nodes.length, 2);
  state.filters.classIri = '';
  state.filters.relationshipType = 'NOPE';
  assert.equal(state.filteredGraph().relationships.length, 0);
  const iriState = new ResultState({ ...graph, nodes: [{ ...graph.nodes[0], kind: 'Airport', labels: ['Airport'] }, graph.nodes[1], { id: 'c', kind: 'Airport', labels: ['Airport'], caption: 'C1', properties: {} }], relationships: [...graph.relationships, { id: 'r2', source: 'a', target: 'c', type: 'hasRunway', properties: {} }] });
  const catalog = new SchemaCatalog({ classLabels: { 'urn:atm-knowledge-graph:Airport': '机场' }, objectPropertyLabels: { 'urn:atm-knowledge-graph:hasRunway': '具有跑道' } });
  iriState.filters.classIri = 'urn:atm-knowledge-graph:Airport';
  iriState.filters.relationshipType = 'urn:atm-knowledge-graph:hasRunway';
  const filtered = iriState.filteredGraph(catalog);
  assert.deepEqual(filtered.nodes.map(node => node.kind), ['Airport', 'Airport']);
  assert.deepEqual(filtered.relationships.map(rel => rel.type), ['hasRunway']);
});
