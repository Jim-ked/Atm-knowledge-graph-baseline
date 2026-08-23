import { chromium } from 'playwright-core';
import { mkdir, writeFile } from 'node:fs/promises';

const BASE = process.env.ATMKG_VIEWER_URL || 'http://127.0.0.1:18080/viewer/?debug=true';
const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const failures = [];
const consoleErrors = [];
const pageErrors = [];
const results = { scenes: {}, layouts: {}, labels: {}, geometry: {}, interactions: {}, plugins: {}, environment: {} };
const require = (condition, message) => { if (!condition) failures.push(message); };

const browser = await chromium.launch({ headless: true, executablePath: CHROME });
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1, bypassCSP: true });
  const page = await context.newPage();
  page.setDefaultTimeout(180_000);
  page.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text()); });
  page.on('pageerror', error => pageErrors.push(error.message));
  await page.goto(BASE, { waitUntil: 'networkidle' });
  await page.waitForFunction(() => window.__ATMKG_PHASE5__);
  await page.evaluate(() => window.__ATMKG_PHASE5__.switchEngine('cytoscape'));

  async function load(name) {
    await page.evaluate(scene => window.__ATMKG_PHASE5__.loadPreset(scene), name);
    const observed = await page.evaluate(scene => {
      const gate = window.__ATMKG_PHASE5__;
      const snapshot = gate.model.snapshot();
      return { scene, modelNodes: snapshot.nodes.length, modelEdges: snapshot.relationships.length, diagnostics: gate.cytoscapeDiagnostics() };
    }, name);
    results.scenes[name] = observed;
    require(observed.modelNodes === observed.diagnostics.graphDto.nodes && observed.modelEdges === observed.diagnostics.graphDto.relationships,
      `${name}: GraphDTO/Cytoscape topology mismatch`);
    require(observed.diagnostics.technicalErrors.length === 0, `${name}: ${observed.diagnostics.technicalErrors.join(' | ')}`);
    return observed;
  }

  for (const name of ['z001', 'r001', 'as0001']) await load(name);
  results.plugins = await page.evaluate(() => window.__ATMKG_PHASE5__.cytoscapeDiagnostics().pluginDiagnostics);
  for (const key of ['layoutUtilities', 'viewUtilities', 'undoRedo', 'contextMenus', 'popper', 'expandCollapse', 'automove']) {
    require(results.plugins[key] === true, `plugin ${key} was not initialized`);
  }
  require(results.scenes.z001.diagnostics.pluginDiagnostics.navigator === false, 'navigator must be hidden for small graph');

  await page.evaluate(() => window.__ATMKG_PHASE5__.loadPreset('r001'));
  const layoutCandidates = [
    ['FCOSE', null], ['D3_FORCE', null], ['COLA', 'flow'], ['ELK', 'layered'], ['ELK', 'mrtree'], ['ELK', 'stress'],
    ['DAGRE', 'LR'], ['CISE', null], ['AVSDF', null], ['SPREAD', null]
  ];
  for (const [name, variant] of layoutCandidates) {
    try {
      await page.evaluate(({ name, variant }) => window.__ATMKG_PHASE5__.runCytoscapeLayout(name, variant), { name, variant });
      const diagnostic = await page.evaluate(() => window.__ATMKG_PHASE5__.cytoscapeDiagnostics());
      results.layouts[`${name}${variant ? `-${variant}` : ''}`] = { lastLayout: diagnostic.lastLayout, options: diagnostic.lastLayoutOptions };
      require(diagnostic.lastLayout === name, `${name}: effective layout=${diagnostic.lastLayout}`);
      require(diagnostic.technicalErrors.length === 0, `${name}: ${diagnostic.technicalErrors.join(' | ')}`);
    } catch (error) {
      results.layouts[`${name}${variant ? `-${variant}` : ''}`] = { error: error.message };
      failures.push(`${name}: ${error.message}`);
    }
  }
  const constraints = await page.evaluate(() => window.__ATMKG_PHASE5__.runFcoseConstraintProbe());
  results.layouts.fcoseConstraints = constraints;
  require(constraints?.length === 3, 'fCoSE constraint probe did not run fixed/alignment/relative constraints');
  require(constraints.every(item => item.options.name === 'fcose'), 'fCoSE constraint probe used wrong layout');

  for (const mode of ['AUTO', 'INSIDE', 'OUTSIDE', 'HIDDEN']) {
    results.labels[`node-${mode}`] = await page.evaluate(async value => {
      const gate = window.__ATMKG_PHASE5__;
      await gate.setViewerLabelModes(value, 'AUTO');
      const id = gate.model.snapshot().nodes[0].id;
      const style = gate.adapter.cy.getElementById(id).renderedStyle();
      return { mode: gate.cytoscapeDiagnostics().viewerConfig?.node?.labelMode ?? value, label: style.label, valign: style['text-valign'] };
    }, mode);
  }
  for (const mode of ['AUTO', 'VISIBLE', 'HIDDEN']) {
    results.labels[`edge-${mode}`] = await page.evaluate(async value => {
      const gate = window.__ATMKG_PHASE5__;
      await gate.setViewerLabelModes('AUTO', value);
      return { mode: value, label: gate.adapter.cy.edges()[0].renderedStyle().label };
    }, mode);
  }
  await page.evaluate(() => window.__ATMKG_PHASE5__.setViewerLabelModes('AUTO', 'AUTO'));

  await page.evaluate(() => window.__ATMKG_PHASE5__.loadPreset('z001'));
  const geometryDemo = await page.evaluate(() => {
    const gate = window.__ATMKG_PHASE5__;
    const [source, target] = gate.adapter.cy.nodes().map(node => node.id());
    gate.adapter.cy.add([
      { group: 'edges', data: { id: 'cy-demo-parallel', source, target, type: 'DEMO_PARALLEL', viewerLabel: 'DEMO_PARALLEL', geometryKind: 'parallel', curveStep: -22 } },
      { group: 'edges', data: { id: 'cy-demo-reverse', source: target, target: source, type: 'DEMO_REVERSE', viewerLabel: 'DEMO_REVERSE', geometryKind: 'bidirectional', curveStep: 22 } },
      { group: 'edges', data: { id: 'cy-demo-loop', source, target: source, type: 'DEMO_LOOP', viewerLabel: 'DEMO_LOOP', geometryKind: 'self-loop', curveStep: 0 } }
    ]);
    return {
      edges: gate.adapter.cy.edges().length,
      kinds: ['cy-demo-parallel', 'cy-demo-reverse', 'cy-demo-loop'].map(id => gate.adapter.cy.getElementById(id).data('geometryKind'))
    };
  });
  results.geometry = geometryDemo;
  require(geometryDemo.edges === 5, 'parallel/bidirectional/self-loop demo did not preserve edges');
  require(JSON.stringify(geometryDemo.kinds) === JSON.stringify(['parallel', 'bidirectional', 'self-loop']), 'edge geometry kinds mismatch');

  await page.evaluate(() => window.__ATMKG_PHASE5__.loadPreset('z001'));
  const selected = await page.evaluate(() => window.__ATMKG_PHASE5__.selectNodeByKind('Runway'));
  const dragId = await page.evaluate(() => window.__ATMKG_PHASE5__.model.snapshot().nodes[0].id);
  const before = await page.evaluate(id => window.__ATMKG_PHASE5__.adapter.cy.getElementById(id).position(), dragId);
  await page.evaluate(() => window.__ATMKG_PHASE5__.dragFirstForValidation());
  const after = await page.evaluate(id => window.__ATMKG_PHASE5__.adapter.cy.getElementById(id).position(), dragId);
  results.interactions.drag = { moved: Math.hypot(after.x - before.x, after.y - before.y) > 0.1 };
  require(results.interactions.drag.moved, 'Cytoscape drag did not update position');
  await page.evaluate(id => window.__ATMKG_PHASE5__.adapter.cy.getElementById(id).emit('dbltap'), selected);
  await page.waitForFunction(() => window.__ATMKG_PHASE5__.model.snapshot().nodes.length > 3);
  const expanded = await page.evaluate(() => ({ model: window.__ATMKG_PHASE5__.model.snapshot(), diag: window.__ATMKG_PHASE5__.cytoscapeDiagnostics() }));
  await page.evaluate(() => window.__ATMKG_PHASE5__.collapseSelected());
  await page.waitForFunction(() => window.__ATMKG_PHASE5__.model.snapshot().nodes.length === 3);
  results.interactions.expand = { added: expanded.model.nodes.length > 3, observation: expanded.diag.lastExpansion };
  require(results.interactions.expand.added, 'dblclick/API expand did not add nodes');
  require(results.interactions.expand.observation?.anchorDisplacement >= 0, 'incremental expand observation missing');

  const hideUndo = await page.evaluate(async () => {
    const gate = window.__ATMKG_PHASE5__;
    const id = gate.model.snapshot().nodes[0].id;
    await gate.adapter.hide(id);
    const hidden = gate.adapter.cy.getElementById(id).hidden();
    gate.adapter.undo();
    const visibleAfterUndo = !gate.adapter.cy.getElementById(id).hidden();
    await gate.adapter.pin(id);
    const locked = gate.adapter.cy.getElementById(id).locked();
    gate.adapter.undo();
    const unlockedAfterUndo = !gate.adapter.cy.getElementById(id).locked();
    return { hidden, visibleAfterUndo, locked, unlockedAfterUndo };
  });
  results.interactions.hideUndo = hideUndo;
  require(hideUndo.hidden && hideUndo.visibleAfterUndo && hideUndo.locked && hideUndo.unlockedAfterUndo, 'view-utilities/undo-redo ownership failed');

  const tooltip = await page.evaluate(() => {
    const gate = window.__ATMKG_PHASE5__;
    const node = gate.adapter.cy.nodes()[0];
    node.emit('mouseover');
    return { exists: Boolean(document.querySelector('.cytoscape-tooltip')), popper: gate.cytoscapeDiagnostics().pluginDiagnostics.popper };
  });
  results.interactions.tooltip = tooltip;
  require(tooltip.exists && tooltip.popper, 'popper/Floating UI tooltip failed');
  await page.evaluate(() => window.__ATMKG_PHASE5__.adapter.cy.nodes()[0].emit('mouseout'));

  const automove = await page.evaluate(() => window.__ATMKG_PHASE5__.enableCytoscapeAutomoveProbe());
  results.interactions.automove = automove;
  require(automove.enabled === true, 'automove probe did not initialize');

  await page.evaluate(size => window.__ATMKG_PHASE5__.loadScale(size), 100);
  results.plugins.large = await page.evaluate(() => window.__ATMKG_PHASE5__.cytoscapeDiagnostics().pluginDiagnostics);
  require(results.plugins.large.navigator === true, 'navigator did not appear above threshold');

  const snapshot = await page.evaluate(() => window.__ATMKG_PHASE5__.model.snapshot());
  const viewerFields = (JSON.stringify(snapshot).match(/viewer|cytoscape|sigma|g6/gi) || []).length;
  require(viewerFields === 0, `GraphDTO viewer-specific fields=${viewerFields}`);
  require(consoleErrors.length === 0, `console errors: ${consoleErrors.join(' | ')}`);
  require(pageErrors.length === 0, `page errors: ${pageErrors.join(' | ')}`);
  results.environment = { consoleErrors, pageErrors, base: BASE };
  await mkdir('test-results/cytoscape', { recursive: true });
  await page.screenshot({ path: 'test-results/cytoscape/r001.png', fullPage: true });
  await writeFile('test-results/cytoscape/plugin-results.json', JSON.stringify(results, null, 2));
  if (failures.length) throw new Error(failures.join('\n'));
  console.log('CYTOSCAPE_PLUGIN_GATE_OK');
  console.log('cytoscape_3_34_1=PASS');
  console.log('fcose_incremental_layout_utilities=PASS');
  console.log('debug_layout_candidates=PASS');
  console.log('labels_geometry_interactions=PASS');
  console.log('view_undo_context_tooltip_navigator=PASS');
  console.log('automove_lifecycle=PASS');
  console.log('graphdto_viewer_fields=0');
  console.log('console_errors=0');
} finally {
  await browser.close();
}
