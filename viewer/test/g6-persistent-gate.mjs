import { chromium } from 'playwright-core';
import { mkdir, writeFile } from 'node:fs/promises';

const BASE = process.env.ATMKG_VIEWER_URL || 'http://127.0.0.1:18080/viewer/';
const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const failures = [];
const consoleErrors = [];
const pageErrors = [];
const results = { expansions: {}, drag: {}, presets: {}, environment: {} };
const require = (condition, message) => { if (!condition) failures.push(message); };

const browser = await chromium.launch({ headless: true, executablePath: CHROME });
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 });
  page.setDefaultTimeout(120_000);
  page.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text()); });
  page.on('pageerror', error => pageErrors.push(error.message));
  const response = await page.goto(BASE, { waitUntil: 'networkidle' });
  require(response?.status() === 200, `viewer HTTP ${response?.status()}`);
  await page.waitForFunction(() => window.__ATMKG_PHASE5__);

  async function expand(name, kind, restartPreset) {
    await page.evaluate(preset => window.__ATMKG_PHASE5__.loadPreset(preset), name);
    await page.evaluate(({ targetKind, preset }) => {
      window.__ATMKG_PHASE5__.setG6RestartPreset(preset);
      return window.__ATMKG_PHASE5__.selectNodeByKind(targetKind);
    }, { targetKind: kind, preset: restartPreset });
    const before = await page.evaluate(() => {
      const gate = window.__ATMKG_PHASE5__;
      const diagnostic = gate.adapter.diagnostics();
      return {
        nodes: gate.model.snapshot().nodes.length,
        relationships: gate.model.snapshot().relationships.length,
        simulationCreationCount: diagnostic.simulationCreationCount
      };
    });
    await page.evaluate(() => window.__ATMKG_PHASE5__.expandSelected());
    const after = await page.evaluate(() => {
      const gate = window.__ATMKG_PHASE5__;
      const snapshot = gate.model.snapshot();
      const diagnostic = gate.adapter.diagnostics();
      return {
        ...diagnostic.lastExpansion,
        modelNodes: snapshot.nodes.length,
        modelRelationships: snapshot.relationships.length,
        graphNodes: gate.adapter.graph.getNodeData().length,
        graphRelationships: gate.adapter.graph.getEdgeData().length,
        simulationCreationCount: diagnostic.simulationCreationCount,
        technicalErrors: diagnostic.technicalErrors
      };
    });
    const key = `${name}-${restartPreset}`;
    results.expansions[key] = { before, after };
    require(before.simulationCreationCount === 1 && after.simulationCreationCount === 1,
      `${key}: simulation was recreated`);
    require(after.oldIdentityPreserved === true, `${key}: old simulation node identity changed`);
    require(after.oldStateReset === false, `${key}: old motion state was reset during reconcile`);
    require(after.modelNodes === after.graphNodes, `${key}: rendered node topology differs from GraphDTO`);
    require(after.modelRelationships === after.graphRelationships,
      `${key}: rendered relationship topology differs from GraphDTO`);
    require(after.physicsLinks <= after.graphRelationships,
      `${key}: physics links were not deduplicated from rendered relationships`);
    require(after.technicalErrors.length === 0, `${key}: ${after.technicalErrors.join(' | ')}`);
  }

  await expand('z001', 'Runway', 'browser-like');
  await expand('z001', 'Runway', 'gentle');
  await expand('r001', 'NavigationAid', 'browser-like');
  await expand('r001', 'NavigationAid', 'gentle');

  await page.evaluate(() => window.__ATMKG_PHASE5__.loadPreset('r001'));
  const routeId = await page.evaluate(() => window.__ATMKG_PHASE5__.selectNodeByKind('Route'));
  await page.evaluate(() => window.__ATMKG_PHASE5__.setG6Fixed(true));
  const graphBox = await page.locator('#graph').boundingBox();
  const routeViewport = await page.evaluate(id => {
    const graph = window.__ATMKG_PHASE5__.adapter.graph;
    return graph.getViewportByCanvas(graph.getElementPosition(id));
  }, routeId);
  await page.mouse.move(graphBox.x + routeViewport[0], graphBox.y + routeViewport[1]);
  await page.mouse.down();
  for (let step = 1; step <= 8; step += 1) {
    await page.mouse.move(graphBox.x + routeViewport[0] + step * 12, graphBox.y + routeViewport[1] + step * 5);
    await page.waitForTimeout(60);
  }
  await page.mouse.up();
  await page.waitForTimeout(300);
  results.drag.afterFixed = await page.evaluate(id => {
    const adapter = window.__ATMKG_PHASE5__.adapter;
    const node = adapter.physics.getNode(id);
    return {
      fixed: node.fx != null && node.fy != null,
      pinnedPosition: [node.fx, node.fy],
      observation: adapter.diagnostics().dragObservation
    };
  }, routeId);
  require(results.drag.afterFixed.fixed, 'fixed=true node did not remain pinned after drag');
  await page.evaluate(() => window.__ATMKG_PHASE5__.unpinSelected());
  results.drag.afterUnpin = await page.evaluate(id => {
    const node = window.__ATMKG_PHASE5__.adapter.physics.getNode(id);
    return { fx: node.fx, fy: node.fy };
  }, routeId);
  require(results.drag.afterUnpin.fx === null && results.drag.afterUnpin.fy === null,
    'unpin did not clear fx/fy');

  await page.evaluate(() => window.__ATMKG_PHASE5__.setG6ForcePreset('browser-reference'));
  results.presets.browserReference = await page.evaluate(() => {
    const config = window.__ATMKG_PHASE5__.adapter.diagnostics().forceConfig;
    return {
      velocityDecay: config.velocityDecay,
      charge: config.manyBodyStrength,
      centerStrength: config.centerStrength,
      collisionRadius: config.nodeSize / 2 + config.nodeGap,
      linkDistance: config.linkDistance
    };
  });
  results.presets.restart = await page.evaluate(() => ({
    browserLike: window.__ATMKG_PHASE5__.restartG6('browser-like'),
    gentle: window.__ATMKG_PHASE5__.restartG6('gentle')
  }));
  require(results.presets.browserReference.velocityDecay === 0.4, 'Browser reference velocityDecay mismatch');
  require(results.presets.browserReference.charge === -400, 'Browser reference charge mismatch');
  require(results.presets.browserReference.centerStrength === 0.03, 'Browser reference center strength mismatch');
  require(results.presets.browserReference.collisionRadius === 48, 'Browser reference collision radius mismatch');
  require(results.presets.browserReference.linkDistance === 136, 'Browser reference link distance mismatch');
  require(results.presets.restart.browserLike === 1 && results.presets.restart.gentle === 0.25,
    'restart alpha presets mismatch');

  require(consoleErrors.length === 0, `console errors: ${consoleErrors.join(' | ')}`);
  require(pageErrors.length === 0, `page errors: ${pageErrors.join(' | ')}`);
  results.environment = {
    consoleErrors,
    pageErrors,
    viewerUrl: BASE,
    userAgent: await page.evaluate(() => navigator.userAgent)
  };
  await mkdir('test-results/g6-persistent', { recursive: true });
  await writeFile('test-results/g6-persistent/results.json', JSON.stringify(results, null, 2));
  if (failures.length) throw new Error(failures.join('\n'));

  console.log('G6_PERSISTENT_SIMULATION_OK');
  console.log('simulation_creation_count=1');
  console.log('old_node_identity=PASS');
  console.log('old_motion_state_reset=NO');
  console.log('incremental_addData_draw=PASS');
  console.log('topology_preserved=PASS');
  console.log('fixed_and_unpin=PASS');
  console.log('console_errors=0');
} finally {
  await browser.close();
}
