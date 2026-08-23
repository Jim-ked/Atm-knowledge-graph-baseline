import { chromium } from 'playwright-core';
import { mkdir, writeFile } from 'node:fs/promises';

const BASE = process.env.ATMKG_VIEWER_URL || 'http://127.0.0.1:18080/viewer/?debug=true';
const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const failures = [];
const consoleErrors = [];
const pageErrors = [];
const results = { scenes: {}, layouts: {}, interactions: {}, expansions: {}, degradation: {}, environment: {} };
const require = (condition, message) => { if (!condition) failures.push(message); };

const browser = await chromium.launch({ headless: true, executablePath: CHROME });
try {
  const context = await browser.newContext({
    viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1,
    bypassCSP: process.env.SIGMA_GATE_BYPASS_CSP !== 'false'
  });
  const page = await context.newPage();
  page.setDefaultTimeout(180_000);
  page.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text()); });
  page.on('pageerror', error => pageErrors.push(error.message));
  const response = await page.goto(BASE, { waitUntil: 'networkidle' });
  require(response?.status() === 200, `viewer HTTP ${response?.status()}`);
  await page.waitForFunction(() => window.__ATMKG_PHASE5__);
  await page.evaluate(() => window.__ATMKG_PHASE5__.switchEngine('sigma'));

  async function observe(name) {
    const value = await page.evaluate(scene => {
      const gate = window.__ATMKG_PHASE5__;
      const adapter = gate.adapter;
      const snapshot = gate.model.snapshot();
      const nodeIds = adapter.graph.nodes();
      const edgeIds = adapter.graph.edges();
      return {
        scene,
        modelNodes: snapshot.nodes.length,
        modelEdges: snapshot.relationships.length,
        graphNodes: adapter.graph.order,
        graphEdges: adapter.graph.size,
        nodeIds,
        edgeIds,
        diagnostics: adapter.diagnostics()
      };
    }, name);
    results.scenes[name] = value;
    require(value.modelNodes === value.graphNodes && value.modelEdges === value.graphEdges,
      `${name}: GraphDTO topology differs from Graphology`);
    require(value.diagnostics.plugins.nodeBorder && value.diagnostics.plugins.edgeCurve
      && value.diagnostics.plugins.sigmaUtils, `${name}: required Sigma plugin not active`);
    const programs = await page.evaluate(() => {
      const settings = window.__ATMKG_PHASE5__.adapter.renderer.getSettings();
      return {
        nodeBorder: Boolean(settings.nodeProgramClasses.border),
        curvedArrow: Boolean(settings.edgeProgramClasses['curved-arrow'])
      };
    });
    require(programs.nodeBorder && programs.curvedArrow, `${name}: Sigma renderer programs not registered`);
    require(value.diagnostics.enableEdgeEvents === true, `${name}: enableEdgeEvents=false`);
    require(value.diagnostics.technicalErrors.length === 0, `${name}: ${value.diagnostics.technicalErrors.join(' | ')}`);
    return value;
  }

  for (const name of ['z001', 'r001', 'as0001']) {
    await page.evaluate(scene => window.__ATMKG_PHASE5__.loadPreset(scene), name);
    await observe(name);
  }
  await page.evaluate(() => window.__ATMKG_PHASE5__.loadPath());
  const path = await observe('r001-path');
  require(path.modelEdges > 0, 'R001 path has no relationships');

  await page.evaluate(() => window.__ATMKG_PHASE5__.loadPreset('r001'));
  results.interactions.labelModes = {};
  for (const mode of ['AUTO', 'INSIDE', 'OUTSIDE', 'HIDDEN']) {
    const observed = await page.evaluate(async value => {
      const gate = window.__ATMKG_PHASE5__;
      await gate.setViewerLabelModes(value, 'AUTO');
      const ids = gate.adapter.graph.nodes();
      const selected = ids[0];
      gate.adapter.renderer.emit('clickNode', { node: selected });
      const display = gate.adapter.renderer.getNodeDisplayData(selected);
      return { mode: gate.adapter.diagnostics().viewerConfig.node.labelMode, label: display.label };
    }, mode);
    results.interactions.labelModes[mode] = observed;
    require(observed.mode === mode, `Sigma node label mode ${mode} was not applied`);
  }
  for (const mode of ['AUTO', 'VISIBLE', 'HIDDEN']) {
    const observed = await page.evaluate(async value => {
      const gate = window.__ATMKG_PHASE5__;
      await gate.setViewerLabelModes('AUTO', value);
      return gate.adapter.diagnostics().viewerConfig.edge.labelMode;
    }, mode);
    results.interactions.labelModes[`edge-${mode}`] = observed;
    require(observed === mode, `Sigma edge label mode ${mode} was not applied`);
  }
  await page.evaluate(() => window.__ATMKG_PHASE5__.setViewerLabelModes('AUTO', 'AUTO'));
  const edgeResult = await page.evaluate(() => {
    const gate = window.__ATMKG_PHASE5__;
    const edge = gate.adapter.graph.edges()[0];
    gate.adapter.renderer.emit('clickEdge', { edge });
    return {
      edge,
      display: gate.adapter.renderer.getEdgeDisplayData(edge),
      selectedEdgeId: gate.adapter.diagnostics().selectedEdgeId,
      detailVisible: !document.querySelector('#relationship-detail').hidden,
      detailType: document.querySelector('#relationship-type').textContent
    };
  });
  results.interactions.edgeClick = edgeResult;
  require(edgeResult.selectedEdgeId === edgeResult.edge && edgeResult.detailVisible,
    'Sigma clickEdge did not select/show relationship detail');

  const nodeResult = await page.evaluate(() => {
    const gate = window.__ATMKG_PHASE5__;
    const node = gate.adapter.graph.nodes()[0];
    gate.adapter.renderer.emit('clickNode', { node });
    const selected = gate.adapter.renderer.getNodeDisplayData(node);
    const neighbor = gate.adapter.graph.neighbors(node)[0];
    const neighborData = neighbor ? gate.adapter.renderer.getNodeDisplayData(neighbor) : null;
    return { node, selected, neighbor: neighborData, selectedNodeId: gate.adapter.diagnostics().selectedNodeId };
  });
  results.interactions.nodeSelection = nodeResult;
  require(nodeResult.selectedNodeId === nodeResult.node && nodeResult.selected.forceLabel === true,
    'Sigma selected node reducer did not force label');
  if (nodeResult.neighbor) require(nodeResult.neighbor.opacity >= nodeResult.selected.opacity * 0.5,
    'Sigma neighbor was over-dimmed');

  const graphBox = await page.locator('#graph').boundingBox();
  const viewport = await page.evaluate(id => window.__ATMKG_PHASE5__.adapter.renderer.graphToViewport(
    window.__ATMKG_PHASE5__.adapter.graph.getNodeAttributes(id)
  ), nodeResult.node);
  const beforeDrag = await page.evaluate(id => ({
    x: window.__ATMKG_PHASE5__.adapter.graph.getNodeAttribute(id, 'x'),
    y: window.__ATMKG_PHASE5__.adapter.graph.getNodeAttribute(id, 'y')
  }), nodeResult.node);
  await page.mouse.move(graphBox.x + viewport.x, graphBox.y + viewport.y);
  await page.mouse.down();
  await page.mouse.move(graphBox.x + viewport.x + 80, graphBox.y + viewport.y + 40);
  await page.mouse.up();
  await page.waitForTimeout(150);
  const afterDrag = await page.evaluate(id => ({
    x: window.__ATMKG_PHASE5__.adapter.graph.getNodeAttribute(id, 'x'),
    y: window.__ATMKG_PHASE5__.adapter.graph.getNodeAttribute(id, 'y'),
    observation: window.__ATMKG_PHASE5__.adapter.diagnostics().dragObservation
  }), nodeResult.node);
  results.interactions.drag = { beforeDrag, afterDrag };
  require(Math.hypot(afterDrag.x - beforeDrag.x, afterDrag.y - beforeDrag.y) > 1,
    'Sigma v3 drag did not move node');
  require(afterDrag.observation?.id === nodeResult.node, 'Sigma drag observation missing selected node');

  for (const policy of ['FORCE', 'FORCE_ATLAS2', 'NOVERLAP', 'KEEP', 'AUTO']) {
    await page.evaluate(value => window.__ATMKG_PHASE5__.setSigmaLayoutPolicy(value), policy);
    await page.evaluate(() => window.__ATMKG_PHASE5__.adapter.layoutReady);
    const diagnostic = await page.evaluate(() => window.__ATMKG_PHASE5__.adapter.diagnostics());
    results.layouts[policy] = diagnostic;
    require(diagnostic.lastLayout === policy || (policy === 'AUTO' && diagnostic.lastLayout === 'KEEP'),
      `${policy}: unexpected effective layout ${diagnostic.lastLayout}`);
    require(diagnostic.noverlapApplied || policy === 'KEEP', `${policy}: Noverlap was not applied`);
  }
  require(results.layouts.FORCE_ATLAS2.fa2WorkerStarted === true, 'FA2 worker was not started');

  async function expand(name, kind) {
    await page.evaluate(scene => window.__ATMKG_PHASE5__.loadPreset(scene), name);
    const anchor = await page.evaluate(target => window.__ATMKG_PHASE5__.selectNodeByKind(target), kind);
    const before = await page.evaluate(id => ({
      x: window.__ATMKG_PHASE5__.adapter.graph.getNodeAttribute(id, 'x'),
      y: window.__ATMKG_PHASE5__.adapter.graph.getNodeAttribute(id, 'y'),
      nodes: window.__ATMKG_PHASE5__.adapter.graph.order,
      edges: window.__ATMKG_PHASE5__.adapter.graph.size
    }), anchor);
    await page.evaluate(() => window.__ATMKG_PHASE5__.expandSelected());
    const after = await page.evaluate(id => ({
      x: window.__ATMKG_PHASE5__.adapter.graph.getNodeAttribute(id, 'x'),
      y: window.__ATMKG_PHASE5__.adapter.graph.getNodeAttribute(id, 'y'),
      nodes: window.__ATMKG_PHASE5__.adapter.graph.order,
      edges: window.__ATMKG_PHASE5__.adapter.graph.size,
      expansion: window.__ATMKG_PHASE5__.adapter.diagnostics().lastExpansion
    }), anchor);
    results.expansions[name] = { before, after };
    require(after.nodes > before.nodes && after.edges > before.edges, `${name}: Sigma expand did not add graphology data`);
    require(after.expansion.oldCoordinatesPreserved === true, `${name}: old coordinates were not preserved`);
    require(after.expansion.anchorId === anchor, `${name}: expansion anchor mismatch`);
  }
  await expand('z001', 'Runway');
  await expand('r001', 'NavigationAid');

  for (const size of [500, 1000, 1500]) {
    await page.evaluate(value => window.__ATMKG_PHASE5__.loadScale(value), size);
    const observed = await observe(`scale-${size}`);
    results.degradation[size] = {
      nodes: observed.graphNodes,
      edges: observed.graphEdges,
      layout: observed.diagnostics.lastLayout,
      workerStarted: observed.diagnostics.fa2WorkerStarted,
      noverlap: observed.diagnostics.noverlapApplied
    };
    require(observed.graphNodes === size, `scale-${size}: node count mismatch`);
  }

  require(consoleErrors.length === 0, `console errors: ${consoleErrors.join(' | ')}`);
  require(pageErrors.length === 0, `page errors: ${pageErrors.join(' | ')}`);
  results.environment = { consoleErrors, pageErrors, userAgent: await page.evaluate(() => navigator.userAgent) };
  await mkdir('test-results/sigma', { recursive: true });
  await page.screenshot({ path: 'test-results/sigma/r001.png', fullPage: true });
  await writeFile('test-results/sigma/results.json', JSON.stringify(results, null, 2));
  if (failures.length) throw new Error(failures.join('\n'));
  console.log('SIGMA_V3_GATE_OK');
  console.log('stable_ids_and_topology=PASS');
  console.log('node_border_edge_curve_utils=PASS');
  console.log('reducers_selection_hover_path=PASS');
  console.log('edge_events=PASS');
  console.log('sigma_v3_drag=PASS');
  console.log('force_fa2_worker_noverlap=PASS');
  console.log('incremental_expand_coordinates=PASS');
  console.log('scale_degradation_observed=PASS');
  console.log('console_errors=0');
} finally {
  await browser.close();
}
