import test from 'node:test';
import assert from 'node:assert/strict';
import { createScaleFixture } from '../src/core/scale-fixtures.js';

for (const size of [100, 500, 1000, 1500]) {
  test(`deterministic ${size} node GraphDTO fixture`, () => {
    const first = createScaleFixture(size);
    const second = createScaleFixture(size);
    assert.equal(first.nodes.length, size);
    assert.equal(first.relationships.length, size + Math.floor(size / 4) - 1);
    assert.deepEqual(first, second);
    assert.equal(first.meta.fixture, `scale-${size}`);
  });
}
