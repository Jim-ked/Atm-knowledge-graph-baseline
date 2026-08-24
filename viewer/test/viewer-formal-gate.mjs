import { chromium } from 'playwright-core';
import { mkdir, writeFile } from 'node:fs/promises';

const BASE = process.env.ATMKG_VIEWER_URL || 'http://127.0.0.1:18080/viewer/';
const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const failures = [];
const consoleErrors = [];
const pageErrors = [];
const results = { shell: {}, labels: {}, interaction: {}, graphDto: {}, environment: {} };
const require = (condition, message) => { if (!condition) failures.push(message); };

const browser = await chromium.launch({ headless: true, executablePath: CHROME });
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 });
  const page = await context.newPage();
  page.setDefaultTimeout(120_000);
  page.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text()); });
  page.on('pageerror', error => pageErrors.push(error.message));
  const response = await page.goto(BASE, { waitUntil: 'networkidle' });
  require(response?.status() === 200, `viewer HTTP ${response?.status()}`);
  await page.waitForFunction(() => window.__ATMKG_PHASE5__);
  await page.evaluate(() => window.__ATMKG_PHASE5__.rendering);

  results.shell.normal = await page.evaluate(() => ({
    debug: document.body.dataset.debug,
    title: document.querySelector('h1')?.textContent,
    engine: window.__ATMKG_PHASE5__.engine,
    visibleDebugBlocks: [...document.querySelectorAll('[data-debug-only]')]
      .filter(element => !element.hidden).length,
    nodeLabelMode: document.querySelector('#node-label-mode')?.value,
    edgeLabelMode: document.querySelector('#edge-label-mode')?.value,
    eyebrow: document.querySelector('.eyebrow')?.textContent ?? null,
    pathQueryCollapsible: document.querySelector('details.path-query #path-form') != null,
    cypherInput: document.querySelector('#cypher') != null,
    cypherForm: document.querySelector('#cypher-form') != null,
    hasDisplayNodeLimit: document.querySelector('#display-node-limit') != null
  }));
  require(results.shell.normal.debug === 'false', 'normal viewer debug flag mismatch');
  require(results.shell.normal.engine === 'g6', 'normal viewer is not G6');
  require(results.shell.normal.visibleDebugBlocks === 0, 'normal viewer exposes debug controls');
  require(results.shell.normal.nodeLabelMode === 'AUTO' && results.shell.normal.edgeLabelMode === 'AUTO',
    'normal viewer label defaults mismatch');
  require(results.shell.normal.eyebrow === null, 'normal viewer still exposes the development eyebrow');
  require(results.shell.normal.pathQueryCollapsible, 'path query was not kept in a compact details element');
  require(results.shell.normal.cypherInput && results.shell.normal.cypherForm,
    'formal viewer is missing the read-only Cypher input');
  require(!results.shell.normal.hasDisplayNodeLimit, 'formal viewer exposes a node display limit control');

  const debugPage = await context.newPage();
  debugPage.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text()); });
  debugPage.on('pageerror', error => pageErrors.push(error.message));
  await debugPage.goto(`${BASE}?debug=true`, { waitUntil: 'networkidle' });
  await debugPage.waitForFunction(() => window.__ATMKG_PHASE5__);
  results.shell.debug = await debugPage.evaluate(() => ({
    debug: document.body.dataset.debug,
    scaleVisible: !document.querySelector('#scale-controls')?.hidden,
    pocVisible: !document.querySelector('#g6-poc-controls')?.hidden,
    metricsVisible: !document.querySelector('#session-metrics')?.hidden
  }));
  require(results.shell.debug.debug === 'true', 'debug viewer flag mismatch');
  require(Object.values(results.shell.debug).every(Boolean), 'debug viewer did not retain all POC controls');
  await debugPage.close();

  await page.evaluate(() => window.__ATMKG_PHASE5__.loadPreset('r001'));
  for (const nodeMode of ['AUTO', 'INSIDE', 'OUTSIDE', 'HIDDEN']) {
    results.labels[nodeMode] = await page.evaluate(async mode => {
      await window.__ATMKG_PHASE5__.setViewerLabelModes(mode, 'AUTO');
      const gate = window.__ATMKG_PHASE5__;
      const first = gate.model.snapshot().nodes[0].id;
      const style = gate.adapter.graph.getElementRenderStyle(first);
      return {
        mode: gate.adapter.diagnostics().viewerConfig.node.labelMode,
        labelText: style.labelText ?? '',
        labelPlacement: style.labelPlacement
      };
    }, nodeMode);
    require(results.labels[nodeMode].mode === nodeMode, `node label mode ${nodeMode} was not applied`);
  }
  require(results.labels.OUTSIDE.labelPlacement === 'bottom', 'OUTSIDE node labels are not external');
  require(results.labels.HIDDEN.labelText === '', 'HIDDEN node labels remain visible');

  results.labels.autoSelected = await page.evaluate(async () => {
    const gate = window.__ATMKG_PHASE5__;
    await gate.setViewerLabelModes('AUTO', 'AUTO');
    const id = await gate.selectNodeByKind('Route');
    const node = gate.model.snapshot().nodes.find(candidate => candidate.id === id);
    return { fullCaption: node.caption, renderedCaption: gate.adapter.graph.getElementRenderStyle(id).labelText };
  });
  require(results.labels.autoSelected.renderedCaption === results.labels.autoSelected.fullCaption,
    'AUTO selected node does not expose its full caption');

  results.interaction.nodeSelectionContext = await page.evaluate(async () => {
    const gate = window.__ATMKG_PHASE5__;
    await gate.setViewerLabelModes('AUTO', 'VISIBLE');
    const selected = await gate.selectNodeByKind('Route');
    const graph = gate.adapter.graph;
    const distantNode = graph.getNodeData().find(node => graph.getElementState(node.id).length === 0);
    const unrelatedEdge = graph.getEdgeData().find(edge => graph.getElementState(edge.id).length === 0);
    return {
      selectedState: graph.getElementState(selected),
      distantNodeState: distantNode ? graph.getElementState(distantNode.id) : null,
      unrelatedEdgeState: unrelatedEdge ? graph.getElementState(unrelatedEdge.id) : null,
      unrelatedEdgeLabel: unrelatedEdge
        ? graph.getElementRenderStyle(unrelatedEdge.id).labelText ?? '' : ''
    };
  });
  require(results.interaction.nodeSelectionContext.selectedState.includes('selected'),
    'ordinary selection did not emphasize the selected node');
  require(results.interaction.nodeSelectionContext.distantNodeState?.length === 0,
    'ordinary selection inactivated a distant node');
  require(results.interaction.nodeSelectionContext.unrelatedEdgeState?.length === 0,
    'ordinary selection inactivated an unrelated edge');
  require(results.interaction.nodeSelectionContext.unrelatedEdgeLabel !== '',
    'VISIBLE unrelated edge label disappeared after ordinary selection');

  await page.evaluate(() => window.__ATMKG_PHASE5__.adapter.graph.emit('canvas:click', { target: {} }));

  results.labels.edge = {};
  for (const edgeMode of ['AUTO', 'VISIBLE', 'HIDDEN']) {
    results.labels.edge[edgeMode] = await page.evaluate(async mode => {
      await window.__ATMKG_PHASE5__.setViewerLabelModes('AUTO', mode);
      const gate = window.__ATMKG_PHASE5__;
      const edge = gate.adapter.graph.getEdgeData()[0];
      return {
        mode: gate.adapter.diagnostics().viewerConfig.edge.labelMode,
        renderedCaption: gate.adapter.graph.getElementRenderStyle(edge.id).labelText ?? ''
      };
    }, edgeMode);
    require(results.labels.edge[edgeMode].mode === edgeMode, `edge label mode ${edgeMode} was not applied`);
  }
  require(results.labels.edge.VISIBLE.renderedCaption !== '', 'VISIBLE edge labels are empty');
  require(results.labels.edge.HIDDEN.renderedCaption === '', 'HIDDEN edge labels remain visible');

  results.interaction.relationshipDetail = await page.evaluate(async () => {
    const gate = window.__ATMKG_PHASE5__;
    const edge = gate.adapter.graph.getEdgeData()[0];
    gate.adapter.graph.emit('edge:click', { target: { id: edge.id } });
    await new Promise(resolve => requestAnimationFrame(resolve));
    return {
      visible: !document.querySelector('#relationship-detail').hidden,
      type: document.querySelector('#relationship-type').textContent,
      expectedType: edge.data.type,
      selectedState: gate.adapter.graph.getElementState(edge.id),
      otherState: gate.adapter.graph.getEdgeData().slice(1)
        .map(candidate => gate.adapter.graph.getElementState(candidate.id))
        .find(states => states.length === 0) ?? null
    };
  });
  require(results.interaction.relationshipDetail.visible
    && results.interaction.relationshipDetail.type === results.interaction.relationshipDetail.expectedType,
  'relationship detail did not show type/properties context');
  require(results.interaction.relationshipDetail.selectedState.includes('selected')
    && results.interaction.relationshipDetail.otherState?.length === 0,
  'relationship selection inactivated unrelated graph context');

  results.interaction.pathFocus = await page.evaluate(async () => {
    const gate = window.__ATMKG_PHASE5__;
    const highlighted = gate.adapter.graph.getEdgeData()[0];
    gate.model.highlightPath([highlighted.id]);
    gate.adapter.highlightedRelationships = new Set([highlighted.id]);
    await gate.adapter.applySelection(null);
    const otherEdge = gate.adapter.graph.getEdgeData().find(edge => edge.id !== highlighted.id);
    const pathNodeIds = new Set([highlighted.source, highlighted.target]);
    const otherNode = gate.adapter.graph.getNodeData().find(node => !pathNodeIds.has(node.id));
    return {
      highlightedState: gate.adapter.graph.getElementState(highlighted.id),
      otherEdgeState: otherEdge ? gate.adapter.graph.getElementState(otherEdge.id) : null,
      otherNodeState: otherNode ? gate.adapter.graph.getElementState(otherNode.id) : null
    };
  });
  require(results.interaction.pathFocus.highlightedState.includes('highlighted')
    && results.interaction.pathFocus.otherEdgeState?.includes('inactive')
    && results.interaction.pathFocus.otherNodeState?.includes('inactive'),
  'path focus no longer inactivates non-path context');

  await page.evaluate(() => window.__ATMKG_PHASE5__.loadPreset('z001'));
  await page.evaluate(() => window.__ATMKG_PHASE5__.selectNodeByKind('Runway'));
  const beforeExpand = await page.evaluate(() => window.__ATMKG_PHASE5__.model.snapshot());
  await page.evaluate(() => window.__ATMKG_PHASE5__.expandSelected());
  const afterExpand = await page.evaluate(() => window.__ATMKG_PHASE5__.model.snapshot());
  results.interaction.expandSelectionContext = await page.evaluate(oldNodeIds => {
    const graph = window.__ATMKG_PHASE5__.adapter.graph;
    return {
      oldInactiveNodes: oldNodeIds.filter(id => graph.getElementState(id).includes('inactive')),
      oldInactiveEdges: graph.getEdgeData()
        .filter(edge => graph.getElementState(edge.id).includes('inactive')).map(edge => edge.id)
    };
  }, beforeExpand.nodes.map(node => node.id));
  await page.evaluate(() => window.__ATMKG_PHASE5__.collapseSelected());
  const afterCollapse = await page.evaluate(() => window.__ATMKG_PHASE5__.model.snapshot());
  results.interaction.expandCollapse = {
    before: [beforeExpand.nodes.length, beforeExpand.relationships.length],
    expanded: [afterExpand.nodes.length, afterExpand.relationships.length],
    collapsed: [afterCollapse.nodes.length, afterCollapse.relationships.length]
  };
  require(afterExpand.nodes.length > beforeExpand.nodes.length, 'expand did not add nodes');
  require(afterCollapse.nodes.length === beforeExpand.nodes.length
    && afterCollapse.relationships.length === beforeExpand.relationships.length,
  'collapse did not restore pre-expansion topology');
  require(results.interaction.expandSelectionContext.oldInactiveNodes.length === 0
    && results.interaction.expandSelectionContext.oldInactiveEdges.length === 0,
  'expand inactivated existing graph context');

  results.interaction.pin = await page.evaluate(() => {
    const gate = window.__ATMKG_PHASE5__;
    gate.pinSelected();
    const id = gate.model.snapshot().state.selectedNodeId;
    const pinned = gate.adapter.physics.getNode(id);
    const afterPin = pinned.fx != null && pinned.fy != null;
    gate.unpinSelected();
    const released = gate.adapter.physics.getNode(id);
    return { afterPin, afterUnpin: released.fx === null && released.fy === null };
  });
  require(results.interaction.pin.afterPin && results.interaction.pin.afterUnpin, 'pin/unpin failed');

  await page.evaluate(() => window.__ATMKG_PHASE5__.loadPreset('z001'));
  const beforeDoubleClick = await page.evaluate(() => window.__ATMKG_PHASE5__.model.snapshot());
  const doubleClickId = await page.evaluate(() => window.__ATMKG_PHASE5__.selectNodeByKind('Runway'));
  await page.evaluate(id => window.__ATMKG_PHASE5__.adapter.graph.emit('node:dblclick', { target: { id } }), doubleClickId);
  await page.waitForFunction(beforeCount =>
    window.__ATMKG_PHASE5__.model.snapshot().nodes.length > beforeCount, beforeDoubleClick.nodes.length);
  await page.waitForFunction(() => document.querySelector('#status')?.textContent.includes('G6 持久力场展开'));
  await page.evaluate(id => window.__ATMKG_PHASE5__.adapter.graph.emit('node:dblclick', { target: { id } }), doubleClickId);
  await page.waitForFunction(beforeCount =>
    window.__ATMKG_PHASE5__.model.snapshot().nodes.length === beforeCount, beforeDoubleClick.nodes.length);
  results.interaction.doubleClick = await page.evaluate(() => ({
    action: window.__ATMKG_PHASE5__.adapter.diagnostics().viewerConfig.interaction.doubleClick,
    topology: [
      window.__ATMKG_PHASE5__.model.snapshot().nodes.length,
      window.__ATMKG_PHASE5__.model.snapshot().relationships.length
    ]
  }));
  require(results.interaction.doubleClick.action === 'EXPAND_OR_COLLAPSE', 'double-click config mismatch');
  require(results.interaction.doubleClick.topology[0] === beforeDoubleClick.nodes.length
    && results.interaction.doubleClick.topology[1] === beforeDoubleClick.relationships.length,
  'double-click collapse did not restore the pre-expansion topology');

  const snapshot = await page.evaluate(() => window.__ATMKG_PHASE5__.model.snapshot());
  const serialized = JSON.stringify(snapshot.nodes) + JSON.stringify(snapshot.relationships);
  results.graphDto.viewerSpecificFields = (serialized.match(/viewer|g6/gi) || []).length;
  require(results.graphDto.viewerSpecificFields === 0, 'GraphDTO contains viewer-specific fields');
  require(consoleErrors.length === 0, `console errors: ${consoleErrors.join(' | ')}`);
  require(pageErrors.length === 0, `page errors: ${pageErrors.join(' | ')}`);

  results.environment = { viewerUrl: BASE, consoleErrors, pageErrors };
  await mkdir('test-results/viewer-formal', { recursive: true });
  await page.screenshot({ path: 'test-results/viewer-formal/normal-viewer.png', fullPage: true });
  await writeFile('test-results/viewer-formal/results.json', JSON.stringify(results, null, 2));
  if (failures.length) throw new Error(failures.join('\n'));
  console.log('VIEWER_FORMAL_GATE_OK');
  console.log('normal_default_g6=PASS');
  console.log('debug_isolation=PASS');
  console.log('node_label_modes=4');
  console.log('edge_label_modes=3');
  console.log('expand_collapse=PASS');
  console.log('selection_context=PASS');
  console.log('path_focus=PASS');
  console.log('pin_unpin=PASS');
  console.log('viewer_specific_graphdto_fields=0');
  console.log('console_errors=0');
} finally {
  await browser.close();
}
