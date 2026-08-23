export const CYTOSCAPE_LAYOUT_POLICIES = Object.freeze([
  'AUTO', 'FCOSE', 'D3_FORCE', 'COLA', 'ELK', 'DAGRE', 'CISE', 'AVSDF', 'SPREAD', 'KEEP'
]);

export const CYTOSCAPE_LAYOUT_CONTEXTS = Object.freeze([
  'FULL_QUERY', 'INCREMENTAL_EXPAND', 'USER_POSITIONED'
]);

function ids(cy) {
  return cy?.nodes?.().map(node => node.id()) ?? [];
}

export function isDirectedAcyclic(cy) {
  const nodeIds = ids(cy);
  if (nodeIds.length < 4) return false;
  const incoming = new Map(nodeIds.map(id => [id, 0]));
  const outgoing = new Map(nodeIds.map(id => [id, []]));
  cy.edges().forEach(edge => {
    const source = edge.source().id();
    const target = edge.target().id();
    if (source === target) return incoming.set(target, Number.POSITIVE_INFINITY);
    incoming.set(target, (incoming.get(target) ?? 0) + 1);
    outgoing.get(source)?.push(target);
  });
  const queue = nodeIds.filter(id => incoming.get(id) === 0);
  let visited = 0;
  while (queue.length) {
    const current = queue.shift();
    visited += 1;
    for (const target of outgoing.get(current) ?? []) {
      incoming.set(target, incoming.get(target) - 1);
      if (incoming.get(target) === 0) queue.push(target);
    }
  }
  return visited === nodeIds.length;
}

export function chooseCytoscapeLayout(policy, cy, { context = 'FULL_QUERY' } = {}) {
  if (!CYTOSCAPE_LAYOUT_POLICIES.includes(policy)) throw new Error(`unknown Cytoscape layout policy: ${policy}`);
  if (!CYTOSCAPE_LAYOUT_CONTEXTS.includes(context)) throw new Error(`unknown Cytoscape layout context: ${context}`);
  if (policy !== 'AUTO') return policy;
  if (context === 'USER_POSITIONED') return 'KEEP';
  if (context === 'INCREMENTAL_EXPAND') return 'FCOSE';
  return isDirectedAcyclic(cy) ? 'ELK' : 'FCOSE';
}

function fixedConstraints(cy, anchorId) {
  const result = [];
  cy.nodes(':locked').forEach(node => result.push({ nodeId: node.id(), position: { ...node.position() } }));
  if (anchorId && cy.hasElementWithId(anchorId) && !result.some(item => item.nodeId === anchorId)) {
    result.push({ nodeId: anchorId, position: { ...cy.getElementById(anchorId).position() } });
  }
  return result;
}

function ciseClusters(cy) {
  const groups = new Map();
  cy.nodes().forEach(node => {
    const kind = String(node.data('kind') ?? 'Entity');
    const group = groups.get(kind) ?? [];
    group.push(node.id());
    groups.set(kind, group);
  });
  return [...groups.values()].filter(group => group.length > 1);
}

export function cytoscapeLayoutOptions(policy, cy, {
  context = 'FULL_QUERY',
  anchorId = null,
  variant = null,
  constraints = {}
} = {}) {
  const effective = chooseCytoscapeLayout(policy, cy, { context });
  const common = { animate: false, fit: false, padding: 28 };
  if (effective === 'KEEP') return { name: 'preset', fit: false, animate: false };
  if (effective === 'FCOSE') {
    const incremental = context === 'INCREMENTAL_EXPAND';
    const fixedNodeConstraint = constraints.fixedNodeConstraint ?? fixedConstraints(cy, anchorId);
    return {
      ...common,
      name: 'fcose',
      quality: incremental ? 'proof' : 'default',
      randomize: !incremental,
      nodeDimensionsIncludeLabels: true,
      packComponents: true,
      fixedNodeConstraint: fixedNodeConstraint.length ? fixedNodeConstraint : undefined,
      alignmentConstraint: constraints.alignmentConstraint,
      relativePlacementConstraint: constraints.relativePlacementConstraint,
      initialEnergyOnIncremental: incremental ? 0.18 : 0.3,
      idealEdgeLength: () => 86,
      nodeRepulsion: () => 5200,
      numIter: incremental ? 900 : 1800
    };
  }
  if (effective === 'D3_FORCE') return {
    ...common, name: 'd3-force', randomize: false, maxIterations: 100, maxSimulationTime: 850,
    // cytoscape-d3-force receives its own simulation node objects, not Cytoscape elements.
    // Keep the radius in the same visual-size scale as the shared viewer config.
    // Cytoscape edge data stores source/target as stable semantic ids.  The
    // plugin defaults to d3's numeric index accessor, which makes those
    // strings fail with "node not found" as soon as a force layout starts.
    // Explicitly resolve links by the copied simulation node `id` field.
    linkId: node => node.id,
    collideRadius: 32,
    collideStrength: 0.85, linkDistance: 88, manyBodyStrength: -90, velocityDecay: 0.42
  };
  if (effective === 'COLA') return {
    ...common, name: 'cola', randomize: false, maxSimulationTime: 1000,
    avoidOverlap: true, handleDisconnected: true, nodeDimensionsIncludeLabels: true,
    nodeSpacing: () => 16, edgeLength: 88, flow: variant === 'flow' ? { axis: 'y', minSeparation: 70 } : undefined,
    alignment: variant === 'constraints' ? { vertical: [[{ node: cy.nodes()[0] }, { node: cy.nodes()[1] }]] } : undefined,
    gapInequalities: variant === 'constraints' && cy.nodes().length > 2
      ? [{ axis: 'x', left: cy.nodes()[0], right: cy.nodes()[2], gap: 70 }] : undefined
  };
  if (effective === 'ELK') return {
    ...common, name: 'elk', nodeDimensionsIncludeLabels: true,
    elk: { algorithm: variant ?? 'layered', 'elk.direction': 'DOWN', 'elk.spacing.nodeNode': '55' }
  };
  if (effective === 'DAGRE') return {
    ...common, name: 'dagre', rankDir: variant ?? 'TB', nodeSep: 58, edgeSep: 22, rankSep: 84,
    nodeDimensionsIncludeLabels: true, useDagreEdgeControlPoints: true, automaticDagreEdgeStyle: true
  };
  if (effective === 'CISE') return { ...common, name: 'cise', clusters: ciseClusters(cy), nodeSeparation: 24 };
  if (effective === 'AVSDF') return { ...common, name: 'avsdf', nodeSeparation: 65 };
  if (effective === 'SPREAD') return {
    ...common, name: 'spread', prelayout: false, randomize: false, minDist: 52,
    maxExpandIterations: 2, expandingFactor: 0.15
  };
  throw new Error(`no Cytoscape layout options for: ${effective}`);
}

export function runCytoscapeLayout(cy, options) {
  return new Promise((resolve, reject) => {
    let layout;
    try {
      layout = cy.layout({ ...options, stop: event => {
        options.stop?.(event);
        resolve(layout);
      } });
      layout.run();
    } catch (error) {
      reject(error);
    }
  });
}
