export const G6_VISUAL_PRESETS = Object.freeze({
  light: Object.freeze({
    visualNodeSize: 36, nodeStrokeWidth: 0.8, nodeFontSize: 7.5, nodeFontWeight: 400,
    nodeCaptionLimit: 6, edgeLineWidth: 0.5, edgeFontSize: 7
  }),
  balanced: Object.freeze({
    visualNodeSize: 40, nodeStrokeWidth: 1, nodeFontSize: 8, nodeFontWeight: 400,
    nodeCaptionLimit: 7, edgeLineWidth: 0.65, edgeFontSize: 7.5
  }),
  readable: Object.freeze({
    visualNodeSize: 44, nodeStrokeWidth: 1.2, nodeFontSize: 8.5, nodeFontWeight: 400,
    nodeCaptionLimit: 8, edgeLineWidth: 0.8, edgeFontSize: 8
  })
});

export const DEFAULT_G6_POC_CONFIG = Object.freeze({
  fixed: true,
  visualPreset: 'balanced',
  ...G6_VISUAL_PRESETS.balanced,
  nodeSize: 46,
  nodeGap: 9,
  linkDistance: 100,
  linkStrength: 0.55,
  manyBodyStrength: -160,
  collideStrength: 0.95,
  collideIterations: 3,
  centerStrength: 0.08,
  parallelEdgeDistance: 14
});

const clamp = (value, min, max, fallback) => {
  const number = Number(value);
  return Number.isFinite(number) ? Math.min(max, Math.max(min, number)) : fallback;
};

export function normalizeG6PocConfig(input = {}) {
  const visualPreset = Object.hasOwn(G6_VISUAL_PRESETS, input.visualPreset)
    ? input.visualPreset : DEFAULT_G6_POC_CONFIG.visualPreset;
  const visual = G6_VISUAL_PRESETS[visualPreset];
  return {
    ...DEFAULT_G6_POC_CONFIG,
    visualPreset,
    fixed: input.fixed === undefined ? DEFAULT_G6_POC_CONFIG.fixed : Boolean(input.fixed),
    nodeSize: clamp(input.nodeSize, 36, 72, DEFAULT_G6_POC_CONFIG.nodeSize),
    visualNodeSize: clamp(input.visualNodeSize, 32, 56, visual.visualNodeSize),
    nodeStrokeWidth: clamp(input.nodeStrokeWidth, 0.5, 3, visual.nodeStrokeWidth),
    nodeFontSize: clamp(input.nodeFontSize, 7, 14, visual.nodeFontSize),
    nodeFontWeight: Math.round(clamp(input.nodeFontWeight, 300, 600, visual.nodeFontWeight)),
    nodeCaptionLimit: Math.round(clamp(input.nodeCaptionLimit, 5, 10, visual.nodeCaptionLimit)),
    edgeLineWidth: clamp(input.edgeLineWidth, 0.5, 3, visual.edgeLineWidth),
    edgeFontSize: clamp(input.edgeFontSize, 7, 12, visual.edgeFontSize),
    nodeGap: clamp(input.nodeGap, 2, 24, DEFAULT_G6_POC_CONFIG.nodeGap),
    linkDistance: clamp(input.linkDistance, 40, 240, DEFAULT_G6_POC_CONFIG.linkDistance),
    linkStrength: clamp(input.linkStrength, 0, 1, DEFAULT_G6_POC_CONFIG.linkStrength),
    manyBodyStrength: clamp(input.manyBodyStrength, -1500, -30, DEFAULT_G6_POC_CONFIG.manyBodyStrength),
    collideStrength: clamp(input.collideStrength, 0, 1, DEFAULT_G6_POC_CONFIG.collideStrength),
    collideIterations: Math.round(clamp(input.collideIterations, 1, 6, DEFAULT_G6_POC_CONFIG.collideIterations)),
    centerStrength: clamp(input.centerStrength, 0, 1, DEFAULT_G6_POC_CONFIG.centerStrength),
    parallelEdgeDistance: clamp(input.parallelEdgeDistance, 8, 36, DEFAULT_G6_POC_CONFIG.parallelEdgeDistance)
  };
}

export function shortCaption(value, limit = 8) {
  const characters = Array.from(String(value ?? ''));
  if (characters.length <= limit) return characters.join('');
  const head = Math.ceil((limit - 1) / 2);
  const tail = limit - 1 - head;
  return `${characters.slice(0, head).join('')}…${characters.slice(-tail).join('')}`;
}

function diameter(value, fallback) {
  const size = value?._original?.style?.size ?? value?.style?.size ?? fallback;
  if (Array.isArray(size)) return Math.max(...size.map(Number).filter(Number.isFinite));
  return Number.isFinite(Number(size)) ? Number(size) : fallback;
}

export function collisionRadius(node, config) {
  return diameter(node, config.nodeSize) / 2 + config.nodeGap;
}

const priority = (node, selection) => {
  if (node.id === selection.current) return Number.MAX_SAFE_INTEGER;
  if (selection.pathNodes?.has(node.id)) return Number.MAX_SAFE_INTEGER - 1;
  return Number(node.data?.degree ?? 0);
};

export function createG6PocOptions(rawConfig = {}, selection = { current: null }) {
  const config = normalizeG6PocConfig(rawConfig);
  return {
    layout: {
      type: 'd3-force',
      link: { distance: config.linkDistance, strength: config.linkStrength, iterations: 2 },
      manyBody: { strength: config.manyBodyStrength, distanceMin: 16, distanceMax: 900 },
      collide: {
        radius: node => collisionRadius(node, config),
        strength: config.collideStrength,
        iterations: config.collideIterations
      },
      center: { strength: config.centerStrength },
      alphaDecay: 0.045,
      velocityDecay: 0.35
    },
    behaviors: [
      { type: 'drag-canvas', key: 'g6-poc-drag-canvas' },
      { type: 'zoom-canvas', key: 'g6-poc-zoom-canvas' },
      {
        type: 'drag-element',
        key: 'g6-poc-drag-force',
        enable: event => event.targetType === 'node',
        hideEdge: 'none'
      },
      {
        type: 'auto-adapt-label',
        key: 'g6-poc-labels',
        padding: 4,
        throttle: 50,
        sortNode: (nodeA, nodeB) => Math.sign(priority(nodeB, selection) - priority(nodeA, selection))
      }
    ],
    transforms: [{
      type: 'process-parallel-edges',
      key: 'g6-poc-parallel-edges',
      mode: 'bundle',
      distance: config.parallelEdgeDistance,
      loopMode: 'spread',
      loopDistance: 12
    }]
  };
}
