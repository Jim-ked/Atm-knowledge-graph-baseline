import { ApiClient } from './core/api-client.js';
import { GraphModel } from './core/graph-model.js';
import { createScaleFixture } from './core/scale-fixtures.js';
import { G6Adapter } from './adapters/g6-adapter.js';
import { DEFAULT_G6_POC_CONFIG, normalizeG6PocConfig } from './adapters/g6-poc-config.js';
import { CytoscapeAdapter } from './adapters/cytoscape-adapter.js';
import { SigmaAdapter } from './adapters/sigma-adapter.js';
import { createSigmaPerformance } from './adapters/sigma-performance.js';
import {
  INTERACTION_ACTIONS,
  VIEWER_CONFIG,
  withLabelModes
} from './config/viewer-config.js';

const isDebug = new URLSearchParams(window.location.search).get('debug') === 'true';
const performanceDiagnostics = createSigmaPerformance(isDebug);
document.body.dataset.debug = String(isDebug);
for (const element of document.querySelectorAll('[data-debug-only]')) element.hidden = !isDebug;

const PRESETS = {
  z001: {
    label: 'Z001 一跳',
    uid: 'urn:atm-knowledge-graph:entity:urn%3Aatm-knowledge-graph%3AAirport:Z001'
  },
  r001: {
    label: 'R001 K=2',
    uid: 'urn:atm-knowledge-graph:entity:urn%3Aatm-knowledge-graph%3ARoute:R001'
  },
  as0001: {
    label: 'AS0001 K=2',
    uid: 'urn:atm-knowledge-graph:entity:urn%3Aatm-knowledge-graph%3AAirspace:AS0001'
  }
};
PRESETS.z001.run = api => api.oneHop(PRESETS.z001.uid);
PRESETS.r001.run = api => api.kHop(PRESETS.r001.uid, 2);
PRESETS.as0001.run = api => api.kHop(PRESETS.as0001.uid, 2);
const PATH_PRESET = {
  from: 'urn:atm-knowledge-graph:entity:urn%3Aatm-knowledge-graph%3ARouteNode:R001%3AN001',
  to: 'urn:atm-knowledge-graph:entity:urn%3Aatm-knowledge-graph%3ARouteNode:R001%3AN006'
};

const elements = Object.fromEntries([
  'status', 'graph', 'graph-summary', 'render-time', 'metrics', 'uid', 'depth',
  'element-empty', 'node-empty', 'node-detail', 'node-caption', 'node-fields', 'node-properties',
  'relationship-detail', 'relationship-type', 'relationship-fields', 'relationship-properties',
  'path-from', 'path-to', 'path-depth', 'node-label-mode', 'edge-label-mode',
  'entity-query', 'rebalance', 'clear', 'collapse', 'pin', 'unpin', 'hide-node',
  'sigma-workbench', 'sigma-layout-policy', 'sigma-fit-neighborhood', 'sigma-state'
  , 'cytoscape-workbench', 'cytoscape-layout-policy', 'cytoscape-run-layout', 'cytoscape-fcose-probe',
  'cytoscape-automove', 'cytoscape-undo', 'cytoscape-redo', 'cytoscape-state'
  , 'g6-poc-controls', 'g6-fixed', 'g6-link-distance', 'g6-link-strength',
  'g6-many-body', 'g6-collide-strength', 'g6-collide-iterations', 'g6-center-strength', 'g6-poc-state',
  'g6-unpin', 'g6-force-preset', 'g6-restart-preset', 'g6-restart', 'g6-visual-preset',
  'g6-geometry-demo'
].map(id => [id, document.getElementById(id)]));
const api = new ApiClient();
const model = new GraphModel({ schemaVersion: '1', nodes: [], relationships: [], meta: {} });
const adapters = { g6: G6Adapter, cytoscape: CytoscapeAdapter, sigma: SigmaAdapter };
const metrics = [];
let engine = 'g6';
let adapter = null;
let dataset = 'empty';
let rendering = Promise.resolve();
let apiRequests = 0;
let g6PocConfig = { ...DEFAULT_G6_POC_CONFIG };
let viewerConfig = structuredClone(VIEWER_CONFIG);
let selectedRelationshipId = null;
const expansionHistory = new Map();
const runtimeTransitions = [];

function status(message, error = false) {
  elements.status.textContent = message;
  elements.status.classList.toggle('error', error);
}

function updateDebugWorkbenches() {
  if (elements['g6-poc-controls']) elements['g6-poc-controls'].hidden = !isDebug || engine !== 'g6';
  if (elements['sigma-workbench']) elements['sigma-workbench'].hidden = !isDebug || engine !== 'sigma';
  if (elements['cytoscape-workbench']) elements['cytoscape-workbench'].hidden = !isDebug || engine !== 'cytoscape';
}

function modelReplace(graphDto) {
  performanceDiagnostics.count('graphModelReplace');
  return model.replace(graphDto);
}

function modelMerge(graphDto) {
  performanceDiagnostics.count('graphModelMerge');
  return model.merge(graphDto);
}

function modelApplyPatch(patch) {
  performanceDiagnostics.count('graphModelApplyPatch');
  return model.applyPatch(patch);
}

function setGraph(graphDto, name) {
  modelReplace(graphDto);
  // A full query starts a fresh interaction workspace. Do not carry selection
  // or path highlighting into a new Sigma renderer/physics instance.
  model.select(null);
  model.highlightPath([]);
  expansionHistory.clear();
  selectedRelationshipId = null;
  dataset = name;
  return renderCurrent();
}

function renderCurrent() {
  rendering = rendering.then(async () => {
    const started = performance.now();
    const previousAdapter = adapter;
    if (previousAdapter) {
      await previousAdapter.destroy();
      runtimeTransitions.push({
        phase: 'destroy',
        runtime: previousAdapter.runtimeDiagnostics?.() ?? null
      });
    }
    adapter = engine === 'g6'
      ? new G6Adapter(elements.graph, selectNode, g6PocConfig, viewerConfig, {
          onRelationshipSelect: selectRelationship,
          onNodeAction: handleNodeAction,
          onCollapse: collapseSelected
        })
      : new adapters[engine](elements.graph, selectNode, viewerConfig, {
          onRelationshipSelect: selectRelationship,
          onNodeAction: handleNodeAction,
          onCollapse: collapseSelected,
          debug: isDebug,
          performance: performanceDiagnostics
        });
    const snapshot = model.snapshot();
    await adapter.render(snapshot);
    runtimeTransitions.push({
      phase: 'mount',
      runtime: adapter.runtimeDiagnostics?.() ?? null,
      topology: { nodes: snapshot.nodes.length, relationships: snapshot.relationships.length }
    });
    const elapsed = Math.round((performance.now() - started) * 10) / 10;
    metrics.push({ engine, dataset, nodes: snapshot.nodes.length, relationships: snapshot.relationships.length, elapsed });
    elements['graph-summary'].textContent = `${snapshot.nodes.length} nodes · ${snapshot.relationships.length} relationships`;
    elements['render-time'].textContent = `render + layout ${elapsed} ms`;
    renderMetrics();
    renderDetail();
    window.dispatchEvent(new CustomEvent('atmkg-render-complete', { detail: { engine, dataset, elapsed } }));
  }).catch(error => status(error.message, true));
  return rendering;
}

function selectNode(nodeId) {
  selectedRelationshipId = null;
  model.select(nodeId);
  renderDetail();
  if (engine === 'g6' && adapter instanceof G6Adapter) {
    adapter.applySelection(nodeId).catch(error => status(error.message, true));
  } else if (engine === 'sigma' && adapter instanceof SigmaAdapter) {
    adapter.applySelection(nodeId).catch(error => status(error.message, true));
  } else if (engine === 'cytoscape' && adapter instanceof CytoscapeAdapter) {
    adapter.applySelection(nodeId).catch(error => status(error.message, true));
  } else renderCurrent();
}

function selectRelationship(relationshipId) {
  selectedRelationshipId = relationshipId;
  model.select(null);
  renderDetail();
  if (engine === 'g6' && adapter instanceof G6Adapter) {
    adapter.applyRelationshipSelection(relationshipId).catch(error => status(error.message, true));
  } else if (engine === 'sigma' && adapter instanceof SigmaAdapter) {
    adapter.applyRelationshipSelection(relationshipId).catch(error => status(error.message, true));
  } else if (engine === 'cytoscape' && adapter instanceof CytoscapeAdapter) {
    adapter.applyRelationshipSelection(relationshipId).catch(error => status(error.message, true));
  }
}

function renderDetail() {
  const snapshot = model.snapshot();
  const node = snapshot.nodes.find(candidate => candidate.id === snapshot.state.selectedNodeId);
  const relationship = snapshot.relationships.find(candidate => candidate.id === selectedRelationshipId);
  elements['element-empty'].hidden = Boolean(node || relationship);
  elements['node-detail'].hidden = !node;
  elements['relationship-detail'].hidden = !relationship;
  if (node) {
    elements['node-caption'].textContent = node.caption;
    fillDetails(elements['node-fields'], [
      ['ID', node.id], ['Kind', node.kind ?? ''], ['Labels', node.labels.join(', ')]
    ]);
    elements['node-properties'].textContent = JSON.stringify(node.properties, null, 2);
    elements.collapse.disabled = !expansionHistory.has(node.id);
  }
  if (relationship) {
    elements['relationship-type'].textContent = relationship.type;
    fillDetails(elements['relationship-fields'], [
      ['ID', relationship.id], ['Source', relationship.source], ['Target', relationship.target]
    ]);
    elements['relationship-properties'].textContent = JSON.stringify(relationship.properties, null, 2);
  }
}

function fillDetails(container, values) {
  container.replaceChildren();
  for (const [name, value] of values) {
    const term = document.createElement('dt'); term.textContent = name;
    const description = document.createElement('dd'); description.textContent = value;
    container.append(term, description);
  }
}

function renderMetrics() {
  elements.metrics.replaceChildren(...metrics.slice(-12).reverse().map(metric => {
    const row = document.createElement('tr');
    for (const value of [metric.engine, `${metric.dataset} (${metric.nodes}/${metric.relationships})`, metric.elapsed]) {
      const cell = document.createElement('td'); cell.textContent = value; row.append(cell);
    }
    return row;
  }));
}

async function callApi(operation, label) {
  status(`正在查询 ${label}…`);
  apiRequests += 1;
  const graphDto = await operation();
  await setGraph(graphDto, label);
  status(`${label}：${graphDto.nodes.length} nodes / ${graphDto.relationships.length} relationships`);
  return graphDto;
}

for (const button of document.querySelectorAll('[data-preset]')) {
  button.addEventListener('click', () => {
    const preset = PRESETS[button.dataset.preset];
    elements.uid.value = preset.uid;
    callApi(() => preset.run(api), preset.label).catch(error => status(error.message, true));
  });
}
for (const button of document.querySelectorAll('[data-scale]')) {
  button.addEventListener('click', () => {
    const size = Number(button.dataset.scale);
    setGraph(createScaleFixture(size), `scale-${size}`).then(() => status(`固定 GraphDTO：${size} nodes`));
  });
}
elements['entity-query'].addEventListener('click', () => {
  callApi(() => api.entity(elements.uid.value), '实体查询').catch(error => status(error.message, true));
});
document.getElementById('query-form').addEventListener('submit', event => {
  event.preventDefault();
  callApi(() => api.kHop(elements.uid.value, elements.depth.value), `K=${elements.depth.value}`)
    .catch(error => status(error.message, true));
});
document.getElementById('engine-switch').addEventListener('click', event => {
  const button = event.target.closest('[data-engine]');
  if (!button || button.dataset.engine === engine) return;
  engine = button.dataset.engine;
  for (const item of document.querySelectorAll('[data-engine]')) item.setAttribute('aria-pressed', String(item === button));
  updateDebugWorkbenches();
  renderCurrent().then(() => status(`已切换到 ${button.textContent}；复用当前内存 GraphDTO`));
});
document.getElementById('fit').addEventListener('click', () => adapter?.fit());
async function expandSelected(nodeId = model.snapshot().state.selectedNodeId) {
  let expandMeasure = null;
  try {
    const selected = nodeId;
    if (!selected) throw new Error('请先选择节点');
    expandMeasure = engine === 'sigma' ? performanceDiagnostics.start('sigma.expand.total', { anchorId: selected }) : null;
    if (model.snapshot().state.selectedNodeId !== selected) selectNode(selected);
    status('正在展开所选节点的一跳…');
    apiRequests += 1;
    const before = model.snapshot();
    const beforeNodeIds = new Set(before.nodes.map(node => node.id));
    const beforeRelationshipIds = new Set(before.relationships.map(relationship => relationship.id));
    const expanded = await api.oneHop(selected);
    const addedNodeIds = expanded.nodes.map(node => node.id).filter(id => !beforeNodeIds.has(id));
    const addedRelationshipIds = expanded.relationships.map(relationship => relationship.id)
      .filter(id => !beforeRelationshipIds.has(id));
    modelMerge(expanded);
    model.markExpanded(selected);
    if (addedNodeIds.length || addedRelationshipIds.length) {
      expansionHistory.set(selected, { addedNodeIds, addedRelationshipIds });
    }
    dataset = `${dataset}+expand`;
    if (engine === 'g6' && adapter instanceof G6Adapter) {
      const started = performance.now();
      const observation = await adapter.addGraphDto(expanded, selected);
      const elapsed = Math.round((performance.now() - started) * 10) / 10;
      const snapshot = model.snapshot();
      metrics.push({ engine, dataset, nodes: snapshot.nodes.length, relationships: snapshot.relationships.length, elapsed });
      elements['graph-summary'].textContent = `${snapshot.nodes.length} nodes · ${snapshot.relationships.length} relationships`;
      elements['render-time'].textContent = `persistent addData + draw ${elapsed} ms`;
      renderMetrics();
      renderDetail();
      status(`G6 持久力场展开：+${observation.addedNodes} nodes / +${observation.addedEdges} relationships；旧节点平均位移 ${observation.meanOldDisplacement}px`);
    } else if (engine === 'sigma' && adapter instanceof SigmaAdapter) {
      const started = performance.now();
      const observation = await adapter.addGraphDto(expanded, selected);
      const elapsed = Math.round((performance.now() - started) * 10) / 10;
      const snapshot = model.snapshot();
      metrics.push({ engine, dataset, nodes: snapshot.nodes.length, relationships: snapshot.relationships.length, elapsed });
      elements['graph-summary'].textContent = `${snapshot.nodes.length} nodes · ${snapshot.relationships.length} relationships`;
      elements['render-time'].textContent = `Sigma Graphology 增量：${elapsed} ms`;
      renderMetrics(); renderDetail();
      status(`Sigma 增量展开：+${observation.addedNodes} nodes / +${observation.addedEdges} relationships`);
    } else if (engine === 'cytoscape' && adapter instanceof CytoscapeAdapter) {
      const started = performance.now();
      const observation = await adapter.addGraphDto(expanded, selected);
      const elapsed = Math.round((performance.now() - started) * 10) / 10;
      const snapshot = model.snapshot();
      metrics.push({ engine, dataset, nodes: snapshot.nodes.length, relationships: snapshot.relationships.length, elapsed });
      elements['graph-summary'].textContent = `${snapshot.nodes.length} nodes · ${snapshot.relationships.length} relationships`;
      elements['render-time'].textContent = `Cytoscape incremental fCoSE ${elapsed} ms`;
      renderMetrics(); renderDetail();
      status(`Cytoscape 增量展开：+${observation.addedNodes} nodes / +${observation.addedEdges} relationships；旧节点平均位移 ${Math.round(observation.meanOldDisplacement * 10) / 10}`);
    } else {
      await renderCurrent();
      status(`已展开 ${expanded.nodes.length} nodes / ${expanded.relationships.length} relationships`);
    }
  } catch (error) { status(error.message, true); }
  finally { expandMeasure?.end(); }
}
document.getElementById('expand').addEventListener('click', () => expandSelected());

async function collapseSelected(nodeId = model.snapshot().state.selectedNodeId) {
  try {
    const selected = nodeId;
    const expansion = expansionHistory.get(selected);
    if (!selected || !expansion) throw new Error('当前节点没有可收起的本次展开');
    modelApplyPatch({
      removeNodeIds: expansion.addedNodeIds,
      removeRelationshipIds: expansion.addedRelationshipIds
    });
    expansionHistory.delete(selected);
    const snapshot = model.snapshot();
    if ((engine === 'g6' && adapter instanceof G6Adapter)
      || (engine === 'sigma' && adapter instanceof SigmaAdapter)
      || (engine === 'cytoscape' && adapter instanceof CytoscapeAdapter)) {
      await adapter.removeToSnapshot(snapshot);
    } else {
      await renderCurrent();
    }
    renderDetail();
    elements['graph-summary'].textContent = `${snapshot.nodes.length} nodes · ${snapshot.relationships.length} relationships`;
    status(`已收起所选节点：-${expansion.addedNodeIds.length} nodes / -${expansion.addedRelationshipIds.length} relationships`);
  } catch (error) { status(error.message, true); }
}

function handleNodeAction(nodeId, action) {
  if (action !== INTERACTION_ACTIONS.EXPAND_OR_COLLAPSE) return;
  selectNode(nodeId);
  const operation = expansionHistory.has(nodeId) ? collapseSelected(nodeId) : expandSelected(nodeId);
  operation.catch(error => status(error.message, true));
}

elements.collapse.addEventListener('click', () => collapseSelected());
document.getElementById('path-form').addEventListener('submit', async event => {
  event.preventDefault();
  try {
    apiRequests += 1;
    const path = await api.path(elements['path-from'].value, elements['path-to'].value, elements['path-depth'].value);
    modelReplace(path);
    model.highlightPath(path.relationships.map(relationship => relationship.id));
    dataset = 'R001-path';
    await renderCurrent();
    status(`路径已高亮：${path.nodes.length} nodes / ${path.relationships.length} relationships`);
  } catch (error) { status(error.message, true); }
});
async function runIncremental() {
  try {
    const snapshot = model.snapshot();
    if (snapshot.nodes.length === 0) throw new Error('请先加载一份 GraphDTO');
    const anchor = snapshot.nodes[0].id;
    const originalRelationship = snapshot.relationships[0];
    const temp = 'viewer-fixture:temporary-node';
    modelApplyPatch({
      upsertNodes: [{ id: temp, labels: ['ViewerFixture'], kind: 'ViewerFixture', caption: '新增节点', properties: { phase: 'add' } }],
      upsertRelationships: [{ id: 'viewer-fixture:temporary-edge', source: anchor, target: temp, type: 'VIEWER_FIXTURE_LINK', properties: {} }]
    });
    await renderCurrent();
    modelApplyPatch({ upsertNodes: [{ id: temp, labels: ['ViewerFixture'], kind: 'ViewerFixture', caption: '已更新节点', properties: { phase: 'update' } }] });
    await renderCurrent();
    if (originalRelationship) {
      modelApplyPatch({ removeRelationshipIds: [originalRelationship.id] });
      await renderCurrent();
      modelApplyPatch({ upsertRelationships: [originalRelationship] });
      await renderCurrent();
    }
    modelApplyPatch({ removeNodeIds: [temp], removeRelationshipIds: ['viewer-fixture:temporary-edge'] });
    await renderCurrent();
    status('增量验证完成：add node/relationship、update node、remove old relationship、remove node');
  } catch (error) { status(error.message, true); }
}
document.getElementById('incremental').addEventListener('click', runIncremental);

async function setViewerLabelModes(nodeMode, edgeMode) {
  viewerConfig = withLabelModes(viewerConfig, nodeMode, edgeMode);
  elements['node-label-mode'].value = viewerConfig.node.labelMode;
  elements['edge-label-mode'].value = viewerConfig.edge.labelMode;
  if (engine === 'g6' && adapter instanceof G6Adapter) {
    await adapter.setLabelModes(viewerConfig.node.labelMode, viewerConfig.edge.labelMode);
  } else if (engine === 'sigma' && adapter instanceof SigmaAdapter) {
    await adapter.setLabelModes(viewerConfig.node.labelMode, viewerConfig.edge.labelMode);
  } else if (engine === 'cytoscape' && adapter instanceof CytoscapeAdapter) {
    await adapter.setLabelModes(viewerConfig.node.labelMode, viewerConfig.edge.labelMode);
  }
  return structuredClone(viewerConfig);
}

elements['node-label-mode'].addEventListener('change', () => {
  setViewerLabelModes(elements['node-label-mode'].value, elements['edge-label-mode'].value)
    .catch(error => status(error.message, true));
});
elements['edge-label-mode'].addEventListener('change', () => {
  setViewerLabelModes(elements['node-label-mode'].value, elements['edge-label-mode'].value)
    .catch(error => status(error.message, true));
});
elements.rebalance.addEventListener('click', () => {
  if (engine === 'g6' && adapter instanceof G6Adapter) {
    adapter.rebalance();
    status('已按当前拓扑重新平衡');
  } else if (engine === 'sigma' && adapter instanceof SigmaAdapter) {
    adapter.rebalance();
    status('Sigma AUTO layout 已重新执行');
  } else if (engine === 'cytoscape' && adapter instanceof CytoscapeAdapter) {
    adapter.rebalance();
    status('Cytoscape AUTO layout 已重新执行');
  }
});
elements.clear.addEventListener('click', () => {
  setGraph({ schemaVersion: '1', nodes: [], relationships: [], meta: {} }, 'empty')
    .then(() => status('画布已清空'));
});
elements.pin.addEventListener('click', () => {
  const selected = model.snapshot().state.selectedNodeId;
  if (!((engine === 'g6' && adapter instanceof G6Adapter)
    || (engine === 'sigma' && adapter instanceof SigmaAdapter)
    || (engine === 'cytoscape' && adapter instanceof CytoscapeAdapter)) || !adapter.pin?.(selected)) {
    status('请先选择一个节点', true);
    return;
  }
  status('所选节点已固定');
});
elements.unpin.addEventListener('click', () => {
  const selected = model.snapshot().state.selectedNodeId;
  if (!((engine === 'g6' && adapter instanceof G6Adapter)
    || (engine === 'sigma' && adapter instanceof SigmaAdapter)
    || (engine === 'cytoscape' && adapter instanceof CytoscapeAdapter)) || !adapter.unpin?.(selected)) {
    status('请先选择一个节点', true);
    return;
  }
  status('已解除所选节点固定');
});
elements['hide-node'].addEventListener('click', async () => {
  const selected = model.snapshot().state.selectedNodeId;
  if (!((engine === 'g6' && adapter instanceof G6Adapter)
    || (engine === 'sigma' && adapter instanceof SigmaAdapter)
    || (engine === 'cytoscape' && adapter instanceof CytoscapeAdapter)) || !await adapter.hide(selected)) {
    status('请先选择一个节点', true);
    return;
  }
  status('所选节点及其画布关系已隐藏；重新查询可恢复');
});

elements['sigma-layout-policy'].addEventListener('change', async () => {
  if (!(engine === 'sigma' && adapter instanceof SigmaAdapter)) return;
  try {
    await adapter.setLayoutPolicy(elements['sigma-layout-policy'].value);
    elements['sigma-state'].textContent = `Sigma v3 · ${adapter.diagnostics().lastLayout} · Noverlap=${adapter.diagnostics().noverlapApplied}`;
    status(`Sigma LayoutPolicy=${elements['sigma-layout-policy'].value}`);
  } catch (error) { status(error.message, true); }
});
elements['sigma-fit-neighborhood'].addEventListener('click', () => {
  if (engine === 'sigma' && adapter instanceof SigmaAdapter) adapter.fitSelectedNeighborhood();
  if (engine === 'cytoscape' && adapter instanceof CytoscapeAdapter) adapter.fitSelectedNeighborhood();
});

elements['cytoscape-layout-policy'].addEventListener('change', async () => {
  if (!(engine === 'cytoscape' && adapter instanceof CytoscapeAdapter)) return;
  try {
    await adapter.setLayoutPolicy(elements['cytoscape-layout-policy'].value);
    elements['cytoscape-state'].textContent = `Cytoscape · ${adapter.diagnostics().lastLayout}`;
    status(`Cytoscape LayoutPolicy=${elements['cytoscape-layout-policy'].value}`);
  } catch (error) { status(error.message, true); }
});
elements['cytoscape-run-layout'].addEventListener('click', async () => {
  if (!(engine === 'cytoscape' && adapter instanceof CytoscapeAdapter)) return;
  try { await adapter.runPluginLayout(elements['cytoscape-layout-policy'].value); status('Cytoscape Debug layout 完成'); }
  catch (error) { status(error.message, true); }
});
elements['cytoscape-fcose-probe'].addEventListener('click', async () => {
  if (!(engine === 'cytoscape' && adapter instanceof CytoscapeAdapter)) return;
  try { const result = await adapter.runFcoseConstraintProbe(); status(`fCoSE constraints 已验证：${result?.length ?? 0}`); }
  catch (error) { status(error.message, true); }
});
elements['cytoscape-automove'].addEventListener('click', async () => {
  if (!(engine === 'cytoscape' && adapter instanceof CytoscapeAdapter)) return;
  try { const result = await adapter.enableAutomoveProbe(); status(result.enabled ? 'Automove probe 已启用' : result.reason, !result.enabled); }
  catch (error) { status(error.message, true); }
});
elements['cytoscape-undo'].addEventListener('click', () => { if (engine === 'cytoscape') adapter?.undo?.(); });
elements['cytoscape-redo'].addEventListener('click', () => { if (engine === 'cytoscape') adapter?.redo?.(); });

elements['g6-fixed'].addEventListener('change', () => {
  g6PocConfig = normalizeG6PocConfig({ ...g6PocConfig, fixed: elements['g6-fixed'].value === 'true' });
  viewerConfig.interaction.dragEnd = g6PocConfig.fixed
    ? INTERACTION_ACTIONS.PIN : INTERACTION_ACTIONS.RELEASE;
  if (engine === 'g6' && adapter instanceof G6Adapter) adapter.setFixed(g6PocConfig.fixed);
  elements['g6-poc-state'].textContent = `d3-force · fixed=${g6PocConfig.fixed} · 直接拖动，无辅助键`;
  status(`G6 持久力场拖动已切换为 fixed=${g6PocConfig.fixed}`);
});
elements['g6-visual-preset'].addEventListener('change', async () => {
  if (!(engine === 'g6' && adapter instanceof G6Adapter)) return;
  try {
    await adapter.setVisualPreset(elements['g6-visual-preset'].value);
    g6PocConfig = { ...adapter.config };
    viewerConfig = structuredClone(adapter.viewerConfig);
    elements['g6-poc-state'].textContent = `${elements['g6-visual-preset'].selectedOptions[0].textContent} · visual node ${g6PocConfig.visualNodeSize}px · 物理参数未变`;
    status(`视觉档位已切换为 ${elements['g6-visual-preset'].selectedOptions[0].textContent}；simulation 未重建`);
  } catch (error) { status(error.message, true); }
});
elements['g6-geometry-demo'].addEventListener('click', async () => {
  if (!(engine === 'g6' && adapter instanceof G6Adapter)) return;
  try {
    const observation = await adapter.addGeometryDemo();
    status(observation.addedEdges
      ? '已添加临时平行边、反向边与 self-loop；仅存在于当前 G6 画布'
      : '临时关系几何示例已存在');
  } catch (error) { status(error.message, true); }
});
elements['g6-unpin'].addEventListener('click', () => {
  const selected = model.snapshot().state.selectedNodeId;
  if (!(engine === 'g6' && adapter instanceof G6Adapter) || !selected) {
    status('请先在 G6 中选择一个节点', true);
    return;
  }
  adapter.unpin(selected);
  status('已解除所选节点固定，并以 Gentle alpha=0.25 重新平衡');
});
elements['g6-force-preset'].addEventListener('change', () => {
  if (engine === 'g6' && adapter instanceof G6Adapter) {
    adapter.setForcePreset(elements['g6-force-preset'].value);
    status(`已选择 ${elements['g6-force-preset'].selectedOptions[0].textContent}，点击“应用并重新平衡”观察`);
  }
});
elements['g6-restart-preset'].addEventListener('change', () => {
  if (engine === 'g6' && adapter instanceof G6Adapter) {
    adapter.setRestartPreset(elements['g6-restart-preset'].value);
    status(`重新加热档已切换为 ${elements['g6-restart-preset'].selectedOptions[0].textContent}`);
  }
});
elements['g6-restart'].addEventListener('click', () => {
  if (engine === 'g6' && adapter instanceof G6Adapter) {
    adapter.setForcePreset(elements['g6-force-preset'].value);
    adapter.setRestartPreset(elements['g6-restart-preset'].value);
    adapter.physics.restart(elements['g6-restart-preset'].value);
    status(`${elements['g6-force-preset'].selectedOptions[0].textContent} · ${elements['g6-restart-preset'].selectedOptions[0].textContent}`);
  }
});
elements['g6-poc-controls'].addEventListener('submit', event => {
  event.preventDefault();
  g6PocConfig = normalizeG6PocConfig({
    ...g6PocConfig,
    linkDistance: elements['g6-link-distance'].value,
    linkStrength: elements['g6-link-strength'].value,
    manyBodyStrength: elements['g6-many-body'].value,
    collideStrength: elements['g6-collide-strength'].value,
    collideIterations: elements['g6-collide-iterations'].value,
    centerStrength: elements['g6-center-strength'].value
  });
  if (engine === 'g6' && adapter instanceof G6Adapter) {
    adapter.configureForces(g6PocConfig);
    elements['g6-force-preset'].value = 'current';
    status('G6 持久 d3-force 参数已应用；simulation 与旧节点对象未重建');
  }
});

elements.uid.value = PRESETS.z001.uid;
elements['path-from'].value = PATH_PRESET.from;
elements['path-to'].value = PATH_PRESET.to;
window.__ATMKG_PHASE5__ = {
  model, metrics, PRESETS, PATH_PRESET,
  performanceDiagnostics: () => performanceDiagnostics.snapshot(),
  resetPerformanceDiagnostics: () => performanceDiagnostics.reset(),
  performanceDelta: before => performanceDiagnostics.delta(before),
  get engine() { return engine; },
  get adapter() { return adapter; },
  get apiRequests() { return apiRequests; },
  get g6PocConfig() { return { ...g6PocConfig }; },
  get rendering() { return rendering; },
  switchEngine: async name => {
    if (!adapters[name]) throw new Error(`unknown engine: ${name}`);
    if (engine !== name) {
      engine = name;
      for (const item of document.querySelectorAll('[data-engine]')) {
        item.setAttribute('aria-pressed', String(item.dataset.engine === name));
      }
      updateDebugWorkbenches();
      await renderCurrent();
    }
  },
  loadPreset: name => callApi(() => PRESETS[name].run(api), PRESETS[name].label),
  loadScale: size => setGraph(createScaleFixture(size), `scale-${size}`),
  loadPath: async () => {
    apiRequests += 1;
    const path = await api.path(PATH_PRESET.from, PATH_PRESET.to, 6);
    modelReplace(path);
    model.highlightPath(path.relationships.map(relationship => relationship.id));
    dataset = 'R001-path';
    await renderCurrent();
    return path;
  },
  selectFirstViaEngine: async () => {
    const first = model.snapshot().nodes[0]?.id;
    if (!first) throw new Error('graph is empty');
    if (engine === 'g6') adapter.graph.emit('node:click', { target: { id: first } });
    else if (engine === 'cytoscape') adapter.cy.getElementById(first).emit('tap');
    else adapter.renderer.emit('clickNode', { node: first, event: {} });
    await rendering;
    return model.snapshot().state.selectedNodeId;
  },
  selectNodeByKind: async kind => {
    const node = model.snapshot().nodes.find(candidate => candidate.kind === kind || candidate.labels.includes(kind));
    if (!node) throw new Error(`node kind not found: ${kind}`);
    selectNode(node.id);
    if (engine === 'g6' || engine === 'sigma' || engine === 'cytoscape') await adapter.applySelection(node.id);
    else await rendering;
    return node.id;
  },
  firstNodeViewport: () => {
    const first = model.snapshot().nodes[0]?.id;
    if (!first) return null;
    if (engine === 'g6') {
      const [x, y] = adapter.graph.getViewportByCanvas(adapter.graph.getElementPosition(first));
      return { x, y };
    }
    if (engine === 'cytoscape') return adapter.cy.getElementById(first).renderedPosition();
    const attributes = adapter.graph.getNodeAttributes(first);
    return adapter.renderer.graphToViewport({ x: attributes.x, y: attributes.y });
  },
  firstNodePosition: () => {
    const first = model.snapshot().nodes[0]?.id;
    if (!first) return null;
    if (engine === 'g6') {
      const [x, y] = adapter.graph.getElementPosition(first);
      return { x, y };
    }
    if (engine === 'cytoscape') return adapter.cy.getElementById(first).position();
    const attributes = adapter.graph.getNodeAttributes(first);
    return { x: attributes.x, y: attributes.y };
  },
  dragFirstForValidation: async () => {
    const first = model.snapshot().nodes[0]?.id;
    if (!first) throw new Error('graph is empty');
    if (engine === 'g6') await adapter.graph.translateElementBy(first, [40, 30], false);
    else if (engine === 'cytoscape') {
      const node = adapter.cy.getElementById(first);
      const position = node.position();
      node.position({ x: position.x + 40, y: position.y + 30 });
    } else {
      const viewport = window.__ATMKG_PHASE5__.firstNodeViewport();
      const original = { preventDefault() {} };
      const sigmaEvent = { preventSigmaDefault() {}, original };
      adapter.renderer.emit('downNode', { node: first, event: sigmaEvent });
      adapter.captor.emit('mousemovebody', {
        x: viewport.x + 40, y: viewport.y + 30, preventSigmaDefault() {}
      });
      adapter.up();
    }
  },
  expandSelected,
  collapseSelected,
  setViewerLabelModes,
  runIncremental,
  setG6Fixed: fixed => {
    elements['g6-fixed'].value = String(Boolean(fixed));
    elements['g6-fixed'].dispatchEvent(new Event('change'));
  },
  setG6VisualPreset: async preset => {
    elements['g6-visual-preset'].value = preset;
    if (!(adapter instanceof G6Adapter)) throw new Error('G6 adapter is not active');
    await adapter.setVisualPreset(preset);
    g6PocConfig = { ...adapter.config };
    viewerConfig = structuredClone(adapter.viewerConfig);
    return adapter.diagnostics().config;
  },
  addG6GeometryDemo: () => {
    if (!(adapter instanceof G6Adapter)) throw new Error('G6 adapter is not active');
    return adapter.addGeometryDemo();
  },
  setG6RestartPreset: preset => {
    elements['g6-restart-preset'].value = preset;
    elements['g6-restart-preset'].dispatchEvent(new Event('change'));
  },
  setG6ForcePreset: preset => {
    elements['g6-force-preset'].value = preset;
    elements['g6-force-preset'].dispatchEvent(new Event('change'));
  },
  restartG6: preset => {
    if (!(adapter instanceof G6Adapter)) throw new Error('G6 adapter is not active');
    adapter.setRestartPreset(preset);
    return adapter.physics.restart(preset);
  },
  unpinSelected: () => {
    if (!(adapter instanceof G6Adapter)) throw new Error('G6 adapter is not active');
    return adapter.unpin(model.snapshot().state.selectedNodeId);
  },
  pinSelected: () => {
    if (!(adapter instanceof G6Adapter) && !(adapter instanceof SigmaAdapter) && !(adapter instanceof CytoscapeAdapter)) throw new Error('G6/Sigma/Cytoscape adapter is not active');
    return adapter.pin(model.snapshot().state.selectedNodeId);
  },
  setSigmaLayoutPolicy: async policy => {
    if (!(adapter instanceof SigmaAdapter)) throw new Error('Sigma adapter is not active');
    return adapter.setLayoutPolicy(policy);
  },
  sigmaDiagnostics: () => {
    if (!(adapter instanceof SigmaAdapter)) throw new Error('Sigma adapter is not active');
    return adapter.diagnostics();
  },
  setCytoscapeLayoutPolicy: async policy => {
    if (!(adapter instanceof CytoscapeAdapter)) throw new Error('Cytoscape adapter is not active');
    return adapter.setLayoutPolicy(policy);
  },
  cytoscapeDiagnostics: () => {
    if (!(adapter instanceof CytoscapeAdapter)) throw new Error('Cytoscape adapter is not active');
    return adapter.diagnostics();
  },
  runCytoscapeLayout: (name, variant = null) => {
    if (!(adapter instanceof CytoscapeAdapter)) throw new Error('Cytoscape adapter is not active');
    return adapter.runPluginLayout(name, variant);
  },
  runFcoseConstraintProbe: () => {
    if (!(adapter instanceof CytoscapeAdapter)) throw new Error('Cytoscape adapter is not active');
    return adapter.runFcoseConstraintProbe();
  },
  enableCytoscapeAutomoveProbe: () => {
    if (!(adapter instanceof CytoscapeAdapter)) throw new Error('Cytoscape adapter is not active');
    return adapter.enableAutomoveProbe();
  },
  sigmaCoordinateDiagnostics: () => {
    if (!(adapter instanceof SigmaAdapter)) throw new Error('Sigma adapter is not active');
    return adapter.coordinateDiagnostics();
  },
  runtimeDiagnostics: () => ({
    current: adapter?.runtimeDiagnostics?.() ?? null,
    transitions: runtimeTransitions.map(item => structuredClone(item))
  }),
  resetRuntimeDiagnostics: () => {
    runtimeTransitions.length = 0;
  }
};
updateDebugWorkbenches();
renderCurrent();
