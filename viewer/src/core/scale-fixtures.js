const ALLOWED_SIZES = new Set([100, 500, 1000, 1500]);

export function createScaleFixture(size) {
  if (!ALLOWED_SIZES.has(size)) throw new Error('scale fixture size must be 100, 500, 1000 or 1500');
  const nodes = Array.from({ length: size }, (_, index) => ({
    id: `scale-node-${index}`,
    labels: [index % 5 === 0 ? 'Hub' : 'FixtureEntity'],
    kind: index % 5 === 0 ? 'Hub' : 'FixtureEntity',
    caption: `N${String(index).padStart(4, '0')}`,
    properties: { fixtureIndex: index, group: index % 10 }
  }));
  const relationships = [];
  for (let index = 1; index < size; index += 1) {
    relationships.push({
      id: `scale-tree-${index}`,
      source: `scale-node-${Math.floor((index - 1) / 3)}`,
      target: `scale-node-${index}`,
      type: 'FIXTURE_LINK',
      properties: { fixture: true }
    });
  }
  for (let index = 0; index < Math.floor(size / 4); index += 1) {
    relationships.push({
      id: `scale-cross-${index}`,
      source: `scale-node-${index}`,
      target: `scale-node-${(index * 17 + 23) % size}`,
      type: 'FIXTURE_CROSS_LINK',
      properties: { fixture: true }
    });
  }
  return {
    schemaVersion: '1',
    nodes,
    relationships,
    meta: { fixture: `scale-${size}`, complete: true, nodeCount: size, relationshipCount: relationships.length }
  };
}
