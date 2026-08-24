export const NODE_LABEL_MODES = Object.freeze(['AUTO', 'INSIDE', 'OUTSIDE', 'HIDDEN']);
export const EDGE_LABEL_MODES = Object.freeze(['AUTO', 'VISIBLE', 'HIDDEN']);

export const INTERACTION_ACTIONS = Object.freeze({
  SELECT: 'SELECT',
  EXPAND_OR_COLLAPSE: 'EXPAND_OR_COLLAPSE',
  HIGHLIGHT: 'HIGHLIGHT',
  MOVE_WITH_FORCE: 'MOVE_WITH_FORCE',
  PIN: 'PIN',
  RELEASE: 'RELEASE',
  ONE_HOP: 'ONE_HOP',
  LAST_EXPANSION: 'LAST_EXPANSION',
  UNPIN: 'UNPIN',
  HIDE: 'HIDE'
});

export const VIEWER_CONFIG = Object.freeze({
  node: Object.freeze({
    defaultSize: 40,
    borderWidth: 1,
    fontSize: 7.5,
    fontWeight: 400,
    labelMode: 'AUTO',
    labelMaxWidth: '82%',
    labelOverflow: 'ellipsis',
    selectedStyle: Object.freeze({
      sizeOffset: 4, fill: '#f59e0b', stroke: '#7c2d12', lineWidth: 1.8,
      haloStroke: '#fbbf24', haloOpacity: 0.2
    }),
    hoverStyle: Object.freeze({ stroke: '#0f172a', lineWidth: 1.5 }),
    inactiveStyle: Object.freeze({ opacity: 0.58, labelOpacity: 0.42 })
  }),
  edge: Object.freeze({
    width: 0.65,
    selectedWidth: 1.15,
    pathWidth: 1.35,
    opacity: 0.32,
    fontSize: 7,
    arrowSize: 5,
    labelMode: 'AUTO',
    selectedStyle: Object.freeze({ stroke: '#0284c7', opacity: 0.78 }),
    inactiveStyle: Object.freeze({ opacity: 0.24, labelOpacity: 0.12 })
  }),
  color: Object.freeze({
    strategy: 'KIND_OR_PRIMARY_LABEL',
    palette: Object.freeze(['#2563eb', '#0f766e', '#7c3aed', '#0369a1', '#b45309', '#be123c']),
    overrides: Object.freeze({
      // "某 kind 或主要 label": "#颜色"
    })
  }),
  interaction: Object.freeze({
    click: INTERACTION_ACTIONS.SELECT,
    doubleClick: INTERACTION_ACTIONS.EXPAND_OR_COLLAPSE,
    hover: INTERACTION_ACTIONS.HIGHLIGHT,
    drag: INTERACTION_ACTIONS.MOVE_WITH_FORCE,
    dragEnd: INTERACTION_ACTIONS.PIN,
    expand: INTERACTION_ACTIONS.ONE_HOP,
    collapse: INTERACTION_ACTIONS.LAST_EXPANSION,
    pin: INTERACTION_ACTIONS.PIN,
    unpin: INTERACTION_ACTIONS.UNPIN,
    hide: INTERACTION_ACTIONS.HIDE
  })
});

function requireMode(value, supported, label) {
  const mode = String(value ?? '').toUpperCase();
  if (!supported.includes(mode)) throw new Error(`${label} label mode: ${value}`);
  return mode;
}

export function withLabelModes(config, nodeMode, edgeMode) {
  return {
    ...config,
    node: { ...config.node, labelMode: requireMode(nodeMode, NODE_LABEL_MODES, 'node') },
    edge: { ...config.edge, labelMode: requireMode(edgeMode, EDGE_LABEL_MODES, 'edge') }
  };
}

export function nodeColorKey(node) {
  return String(node?.kind || node?.labels?.[0] || 'Entity');
}

export function stableNodeColor(node, colorConfig = VIEWER_CONFIG.color) {
  const key = nodeColorKey(node);
  if (Object.hasOwn(colorConfig.overrides ?? {}, key)) return colorConfig.overrides[key];
  const palette = colorConfig.palette?.length ? colorConfig.palette : VIEWER_CONFIG.color.palette;
  // 同一语义键使用确定性哈希，重新查询或重新加载时不会发生颜色漂移。
  const hash = Array.from(key).reduce(
    (value, character) => ((value * 31) + character.codePointAt(0)) | 0,
    0
  );
  return palette[Math.abs(hash) % palette.length];
}

export function resolveNodeLabel(mode, node, { active = false } = {}) {
  const normalized = requireMode(mode, NODE_LABEL_MODES, 'node');
  const full = String(node?.caption ?? '');
  const short = String(node?.displayCaption ?? full);
  if (normalized === 'OUTSIDE') return { text: full, placement: 'bottom' };
  if (normalized === 'HIDDEN') return { text: active ? full : '', placement: 'center' };
  if (normalized === 'INSIDE') return { text: short, placement: 'center' };
  return { text: active ? full : short, placement: 'center' };
}

export function resolveEdgeLabel(mode, fullCaption, automaticCaption, context = {}) {
  const normalized = requireMode(mode, EDGE_LABEL_MODES, 'edge');
  const active = Boolean(context.active || context.related || context.path);
  if (normalized === 'HIDDEN') return active ? String(fullCaption ?? '') : '';
  if (normalized === 'VISIBLE') return String(automaticCaption || fullCaption || '');
  return active ? String(automaticCaption || fullCaption || '') : '';
}
