import test from 'node:test';
import assert from 'node:assert/strict';
import {
  chooseCytoscapeLayout,
  cytoscapeLayoutOptions,
  isDirectedAcyclic
} from '../src/adapters/cytoscape-layout-policy.js';

function fakeCy({ nodes = ['a', 'b', 'c', 'd'], edges = [['a', 'b'], ['b', 'c'], ['c', 'd']], locked = [] } = {}) {
  const positions = new Map(nodes.map((id, index) => [id, { x: index * 50, y: index * 30 }]));
  const nodeObjects = nodes.map(id => ({
    id: () => id,
    position: () => positions.get(id),
    data: key => key === 'kind' ? (id < 'c' ? 'left' : 'right') : undefined,
    outerWidth: () => 40,
    outerHeight: () => 40
  }));
  nodeObjects.forEach = Array.prototype.forEach.bind(nodeObjects);
  const byId = new Map(nodeObjects.map(node => [node.id(), node]));
  return {
    nodes: selector => selector === ':locked' ? nodeObjects.filter(node => locked.includes(node.id())) : nodeObjects,
    edges: () => edges.map(([source, target]) => ({
      source: () => byId.get(source), target: () => byId.get(target)
    })),
    hasElementWithId: id => byId.has(id),
    getElementById: id => byId.get(id)
  };
}

test('AUTO keeps user positions, uses incremental fCoSE, and only selects ELK for a detected DAG', () => {
  const dag = fakeCy();
  const cyclic = fakeCy({ edges: [['a', 'b'], ['b', 'c'], ['c', 'a'], ['c', 'd']] });
  assert.equal(isDirectedAcyclic(dag), true);
  assert.equal(isDirectedAcyclic(cyclic), false);
  assert.equal(chooseCytoscapeLayout('AUTO', dag, { context: 'FULL_QUERY' }), 'ELK');
  assert.equal(chooseCytoscapeLayout('AUTO', cyclic, { context: 'FULL_QUERY' }), 'FCOSE');
  assert.equal(chooseCytoscapeLayout('AUTO', dag, { context: 'INCREMENTAL_EXPAND' }), 'FCOSE');
  assert.equal(chooseCytoscapeLayout('AUTO', dag, { context: 'USER_POSITIONED' }), 'KEEP');
});

test('incremental fCoSE preserves the anchor and enables label-aware packing and controlled energy', () => {
  const cy = fakeCy({ locked: ['d'] });
  const options = cytoscapeLayoutOptions('FCOSE', cy, { context: 'INCREMENTAL_EXPAND', anchorId: 'a' });
  assert.equal(options.name, 'fcose');
  assert.equal(options.randomize, false);
  assert.equal(options.nodeDimensionsIncludeLabels, true);
  assert.equal(options.packComponents, true);
  assert.equal(options.initialEnergyOnIncremental, 0.18);
  assert.deepEqual(options.fixedNodeConstraint.map(item => item.nodeId).sort(), ['a', 'd']);
});

test('debug layout candidates expose their intended documented options', () => {
  const cy = fakeCy();
  assert.equal(cytoscapeLayoutOptions('D3_FORCE', cy).name, 'd3-force');
  assert.equal(cytoscapeLayoutOptions('COLA', cy, { variant: 'flow' }).flow.axis, 'y');
  assert.equal(cytoscapeLayoutOptions('ELK', cy, { variant: 'stress' }).elk.algorithm, 'stress');
  assert.equal(cytoscapeLayoutOptions('DAGRE', cy, { variant: 'LR' }).rankDir, 'LR');
  assert.equal(cytoscapeLayoutOptions('CISE', cy).clusters.length, 2);
  assert.equal(cytoscapeLayoutOptions('AVSDF', cy).name, 'avsdf');
  assert.equal(cytoscapeLayoutOptions('SPREAD', cy).prelayout, false);
});
