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
    edgeLabelMode: document.querySelector('#edge-label-mode')?.value
  }));
  require(results.shell.normal.debug === 'false', 'normal viewer debug flag mismatch');
  require(results.shell.normal.engine === 'g6', 'normal viewer is not G6');
  require(results.shell.normal.visibleDebugBlocks === 0, 'normal viewer exposes debug controls');
  require(results.shell.normal.nodeLabelMode === 'AUTO' && results.shell.normal.edgeLabelMode === 'AUTO',
    'normal viewer label defaults mismatch');

  const debugPage = await context.newPage();
  debugPage.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text()); });
  debugPage.on('pageerror', error => pageErrors.push(error.message));
  await debugPage.goto(`${BASE}?debug=true`, { waitUntil: 'networkidle' });
  await debugPage.waitForFunction(() => window.__ATMKG_PHASE5__);
  results.shell.debug = await debugPage.evaluate(() => ({
    debug: document.body.dataset.debug,
    engineSwitchVisible: !document.querySelector('#engine-switch')?.hidden,
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
      expectedType: edge.data.type
    };
  });
  require(results.interaction.relationshipDetail.visible
    && results.interaction.relationshipDetail.type === results.interaction.relationshipDetail.expectedType,
  'relationship detail did not show type/properties context');

  await page.evaluate(() => window.__ATMKG_PHASE5__.loadPreset('z001'));
  await page.evaluate(() => window.__ATMKG_PHASE5__.selectNodeByKind('Runway'));
  const beforeExpand = await page.evaluate(() => window.__ATMKG_PHASE5__.model.snapshot());
  await page.evaluate(() => window.__ATMKG_PHASE5__.expandSelected());
  const afterExpand = await page.evaluate(() => window.__ATMKG_PHASE5__.model.snapshot());
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
  const doubleClickId = await page.evaluate(() => window.__ATMKG_PHASE5__.selectNodeByKind('Runway'));
  await page.evaluate(id => window.__ATMKG_PHASE5__.adapter.graph.emit('node:dblclick', { target: { id } }), doubleClickId);
  await page.waitForFunction(() => window.__ATMKG_PHASE5__.model.snapshot().nodes.length > 3);
  await page.evaluate(id => window.__ATMKG_PHASE5__.adapter.graph.emit('node:dblclick', { target: { id } }), doubleClickId);
  await page.waitForFunction(() => window.__ATMKG_PHASE5__.model.snapshot().nodes.length === 3);
  results.interaction.doubleClick = await page.evaluate(() => ({
    action: window.__ATMKG_PHASE5__.adapter.diagnostics().viewerConfig.interaction.doubleClick,
    topology: [
      window.__ATMKG_PHASE5__.model.snapshot().nodes.length,
      window.__ATMKG_PHASE5__.model.snapshot().relationships.length
    ]
  }));
  require(results.interaction.doubleClick.action === 'EXPAND_OR_COLLAPSE', 'double-click config mismatch');
  require(results.interaction.doubleClick.topology[0] === 3, 'double-click collapse failed');

  const snapshot = await page.evaluate(() => window.__ATMKG_PHASE5__.model.snapshot());
  const serialized = JSON.stringify(snapshot.nodes) + JSON.stringify(snapshot.relationships);
  results.graphDto.viewerSpecificFields = (serialized.match(/viewer|g6|cytoscape|sigma/gi) || []).length;
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
  console.log('pin_unpin=PASS');
  console.log('viewer_specific_graphdto_fields=0');
  console.log('console_errors=0');
} finally {
  await browser.close();
}
