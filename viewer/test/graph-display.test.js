import test from 'node:test';
import assert from 'node:assert/strict';
import {
  DEFAULT_DISPLAY_NODE_LIMIT,
  DISPLAY_NODE_LIMITS,
  expansionDisplayDelta,
  preparePathGraphDto,
  toDisplayGraphDto
} from '../src/core/graph-display.js';

function graph(nodeCount) {
  const nodes = Array.from({ length: nodeCount }, (_, index) => ({
    id: `n${index}`, labels: ['Fixture'], kind: 'Fixture', caption: `N${index}`, properties: { index }
  }));
  const relationships = Array.from({ length: Math.max(0, nodeCount - 1) }, (_, index) => ({
    id: `r${index}`, source: `n${index}`, target: `n${index + 1}`, type: 'NEXT', properties: {}
  }));
  return { schemaVersion: '1', nodes, relationships, meta: { source: 'test' } };
}

test('display limits are exactly 100, 300, 500 and 1000 with default 300', () => {
  assert.deepEqual(DISPLAY_NODE_LIMITS, [100, 300, 500, 1000]);
  assert.equal(DEFAULT_DISPLAY_NODE_LIMIT, 300);
  for (const limit of DISPLAY_NODE_LIMITS) {
    assert.equal(toDisplayGraphDto(graph(1200), limit).displayCount, limit);
  }
  assert.throws(() => toDisplayGraphDto(graph(10), 200), /display node limit/);
});

test('stable display clipping reports display and total counts without dangling relationships', () => {
  const result = toDisplayGraphDto(graph(812), 300);

  assert.equal(result.displayCount, 300);
  assert.equal(result.totalCount, 812);
  assert.equal(result.truncated, true);
  assert.deepEqual(result.graphDto.nodes.map(node => node.id),
    Array.from({ length: 300 }, (_, index) => `n${index}`));
  const visible = new Set(result.graphDto.nodes.map(node => node.id));
  assert.ok(result.graphDto.relationships.every(relationship =>
    visible.has(relationship.source) && visible.has(relationship.target)));
  assert.equal(result.graphDto.relationships.length, 299);
});

test('display clipping does not mutate the original GraphDTO', () => {
  const original = graph(305);
  const before = structuredClone(original);

  const result = toDisplayGraphDto(original, 300);
  result.graphDto.nodes[0].caption = 'changed only in display result';

  assert.deepEqual(original, before);
});

test('path over the display limit is rejected without producing a partial GraphDTO', () => {
  const decision = preparePathGraphDto(graph(301), 300);

  assert.equal(decision.accepted, false);
  assert.equal(decision.graphDto, null);
  assert.equal(decision.displayCount, 0);
  assert.equal(decision.totalCount, 301);
  assert.equal(preparePathGraphDto(graph(300), 300).accepted, true);
});

test('expansion delta fills only remaining capacity and drops relationships with hidden endpoints', () => {
  const before = graph(99);
  const after = graph(102);
  const result = expansionDisplayDelta(before, after, 100);

  assert.equal(result.truncated, true);
  assert.equal(result.beforeDisplayCount, 99);
  assert.equal(result.displayCount, 100);
  assert.equal(result.totalCount, 102);
  assert.deepEqual(result.graphDto.nodes.map(node => node.id), ['n99']);
  assert.deepEqual(result.graphDto.relationships.map(relationship => relationship.id), ['r98']);
  const visibleAfter = new Set(toDisplayGraphDto(after, 100).graphDto.nodes.map(node => node.id));
  assert.ok(result.graphDto.relationships.every(relationship =>
    visibleAfter.has(relationship.source) && visibleAfter.has(relationship.target)));
});
