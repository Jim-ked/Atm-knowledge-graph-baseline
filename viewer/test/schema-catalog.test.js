import test from 'node:test';
import assert from 'node:assert/strict';
import { SchemaCatalog } from '../src/core/schema-catalog.js';

test('SchemaCatalog resolves exact IRIs and unique local names deterministically', () => {
  const catalog = new SchemaCatalog({ classes: ['urn:A', 'urn:B'], classLabels: { 'urn:A': '甲类', 'urn:B': '乙类' }, datatypePropertyLabels: { 'urn:p': '名称' }, objectPropertyLabels: { 'urn:rel': '关联' } });
  assert.equal(catalog.classLabel('urn:A'), '甲类');
  assert.equal(catalog.datatypePropertyLabel('urn:p'), '名称');
  assert.equal(catalog.objectPropertyLabel('rel'), '关联');
  assert.deepEqual(catalog.classOptions().map(x => x.value), ['urn:A', 'urn:B']);
});
test('SchemaCatalog does not guess ambiguous local names', () => {
  const catalog = new SchemaCatalog({ datatypePropertyLabels: { 'urn:x/p': '一', 'urn:y/p': '二' } });
  assert.equal(catalog.datatypePropertyLabel('p'), 'p');
});
