import test from 'node:test';
import assert from 'node:assert/strict';
import {
  DEFAULT_G6_POC_CONFIG,
  G6_VISUAL_PRESETS,
  collisionRadius,
  createG6PocOptions,
  normalizeG6PocConfig,
  shortCaption
} from '../src/adapters/g6-poc-config.js';

test('G6 POC keeps d3-force values and uses direct external-force dragging', () => {
  const selected = { current: 'selected-node' };
  const options = createG6PocOptions(DEFAULT_G6_POC_CONFIG, selected);
  const drag = options.behaviors.find(behavior => behavior.type === 'drag-element');
  const labels = options.behaviors.find(behavior => behavior.type === 'auto-adapt-label');

  assert.equal(options.layout.type, 'd3-force');
  assert.equal(options.layout.link.distance, 100);
  assert.equal(options.layout.link.strength, 0.55);
  assert.equal(options.layout.manyBody.strength, -160);
  assert.equal(options.layout.collide.radius({ _original: { style: { size: 46 } } }), 32);
  assert.equal(options.layout.collide.strength, 0.95);
  assert.equal(options.layout.collide.iterations, 3);
  assert.equal(options.layout.center.strength, 0.08);
  assert.equal(drag.trigger, undefined);
  assert.equal(drag.enable({ targetType: 'node' }), true);
  assert.equal(drag.enable({ targetType: 'combo' }), false);
  assert.equal(labels.sortNode({ id: 'other' }, { id: 'selected-node' }), 1);
  assert.deepEqual(options.transforms[0], {
    type: 'process-parallel-edges', key: 'g6-poc-parallel-edges', mode: 'bundle', distance: 14,
    loopMode: 'spread', loopDistance: 12
  });
});

test('visual presets stay limited and make ordinary graph elements lightweight', () => {
  assert.deepEqual(Object.keys(G6_VISUAL_PRESETS), ['light', 'balanced', 'readable']);
  assert.equal(DEFAULT_G6_POC_CONFIG.visualPreset, 'balanced');
  assert.equal(DEFAULT_G6_POC_CONFIG.nodeSize, 46);
  assert.equal(DEFAULT_G6_POC_CONFIG.visualNodeSize, 40);
  assert.equal(DEFAULT_G6_POC_CONFIG.nodeStrokeWidth, 1);
  assert.equal(DEFAULT_G6_POC_CONFIG.nodeFontWeight, 400);
  assert.equal(DEFAULT_G6_POC_CONFIG.edgeFontSize, 7.5);
  assert.equal(DEFAULT_G6_POC_CONFIG.edgeLineWidth, 0.65);

  const light = normalizeG6PocConfig({
    ...DEFAULT_G6_POC_CONFIG,
    ...G6_VISUAL_PRESETS.light,
    visualPreset: 'light'
  });
  assert.equal(light.nodeSize, 46);
  assert.equal(light.visualNodeSize, 36);
  assert.equal(light.nodeStrokeWidth, 0.8);
  assert.equal(light.edgeLineWidth, 0.5);
});

test('fixed toggle and bounded tuning values are normalized', () => {
  const config = normalizeG6PocConfig({
    fixed: true,
    linkDistance: 999,
    linkStrength: -1,
    manyBodyStrength: 10,
    collideStrength: 9,
    collideIterations: 99,
    centerStrength: -5
  });
  assert.deepEqual(config, {
    ...DEFAULT_G6_POC_CONFIG,
    fixed: true,
    linkDistance: 240,
    linkStrength: 0,
    manyBodyStrength: -30,
    collideStrength: 1,
    collideIterations: 6,
    centerStrength: 0
  });
});

test('caption is short, Unicode-safe and collision radius follows rendered diameter', () => {
  assert.equal(shortCaption('R001:N001', 8), 'R001…001');
  assert.equal(shortCaption('机场', 8), '机场');
  assert.equal(collisionRadius({ style: { size: [44, 52] } }, { nodeGap: 9, nodeSize: 40 }), 35);
});
