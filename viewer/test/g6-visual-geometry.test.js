import test from 'node:test';
import assert from 'node:assert/strict';
import {
  G6_EDGE_GEOMETRY,
  annotateRelationshipGeometry,
  relationshipCaptionForLength,
  uniqueShortCaptions
} from '../src/adapters/g6-visual-geometry.js';

test('short node captions are unique after truncation without changing full captions', () => {
  const nodes = [
    { id: 'a', caption: 'R001:NAVIGATION-LONG' },
    { id: 'b', caption: 'R001:NAVIGATION-LONG' },
    { id: 'c', caption: '机场' }
  ];
  const captions = uniqueShortCaptions(nodes, 7);

  assert.notEqual(captions.get('a'), captions.get('b'));
  assert.ok(captions.get('a').length <= 7);
  assert.ok(captions.get('b').length <= 7);
  assert.equal(captions.get('c'), '机场');
  assert.equal(nodes[0].caption, 'R001:NAVIGATION-LONG');
});

test('relationship caption is omitted, shortened or complete according to actual edge length', () => {
  assert.equal(relationshipCaptionForLength('HAS_LONG_RELATIONSHIP', 38, 7.5), '');
  const shortened = relationshipCaptionForLength('HAS_LONG_RELATIONSHIP', 78, 7.5);
  assert.ok(shortened.includes('…'));
  assert.ok(shortened.length < 'HAS_LONG_RELATIONSHIP'.length);
  assert.equal(relationshipCaptionForLength('HAS_LONG_RELATIONSHIP', 180, 7.5), 'HAS_LONG_RELATIONSHIP');
});

test('single, parallel, bidirectional and self-loop relationships receive stable geometry kinds', () => {
  const annotated = annotateRelationshipGeometry([
    { id: 'single', source: 'a', target: 'b' },
    { id: 'parallel-1', source: 'c', target: 'd' },
    { id: 'parallel-2', source: 'c', target: 'd' },
    { id: 'forward', source: 'e', target: 'f' },
    { id: 'reverse', source: 'f', target: 'e' },
    { id: 'loop', source: 'g', target: 'g' }
  ]);
  const kind = Object.fromEntries(annotated.map(edge => [edge.id, edge.geometryKind]));

  assert.deepEqual(kind, {
    single: 'single',
    'parallel-1': 'parallel',
    'parallel-2': 'parallel',
    forward: 'bidirectional',
    reverse: 'bidirectional',
    loop: 'self-loop'
  });
  assert.equal(G6_EDGE_GEOMETRY.type, 'quadratic');
  assert.equal(G6_EDGE_GEOMETRY.labelAutoRotate, true);
  assert.equal(G6_EDGE_GEOMETRY.endArrowSize, 5);
});
