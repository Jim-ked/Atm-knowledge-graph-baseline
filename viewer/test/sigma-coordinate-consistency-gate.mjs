import { chromium } from 'playwright-core';
import { mkdir, writeFile } from 'node:fs/promises';

const BASE = process.env.ATMKG_VIEWER_URL || 'http://127.0.0.1:18080/viewer/?debug=true';
const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const failures = [];
const consoleErrors = [];
const pageErrors = [];
const results = { fresh: {}, pinReset: null, environment: {} };
const require = (condition, message) => { if (!condition) failures.push(message); };

function maxDelta(nodes, leftX, leftY, rightX, rightY) {
  return nodes.reduce((max, node) => {
    const a = Number(node[leftX]);
    const b = Number(node[leftY]);
    const c = Number(node[rightX]);
    const d = Number(node[rightY]);
    if (![a, b, c, d].every(Number.isFinite)) return max;
    return Math.max(max, Math.hypot(a - c, b - d));
  }, 0);
}

function ratioSpread(summary) {
  return summary?.maxMin == null ? 0 : summary.maxMin;
}

const browser = await chromium.launch({ headless: true, executablePath: CHROME });
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 });
  const page = await context.newPage();
  page.setDefaultTimeout(180_000);
  page.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text()); });
  page.on('pageerror', error => pageErrors.push(error.message));

  async function fresh(name) {
    await page.goto(BASE, { waitUntil: 'networkidle' });
    await page.waitForFunction(() => window.__ATMKG_PHASE5__);
    await page.evaluate(() => window.__ATMKG_PHASE5__.switchEngine('sigma'));
    await page.evaluate(scene => window.__ATMKG_PHASE5__.loadPreset(scene), name);
    await page.waitForFunction(() => window.__ATMKG_PHASE5__.adapter?.renderer);
    return page.evaluate(scene => {
      const gate = window.__ATMKG_PHASE5__;
      const adapter = gate.adapter;
      const snapshot = gate.model.snapshot();
      const coordinates = gate.sigmaCoordinateDiagnostics();
      const physics = adapter.physics.diagnostics(adapter.graph);
      return {
        scene,
        topology: { modelNodes: snapshot.nodes.length, modelEdges: snapshot.relationships.length,
          graphNodes: adapter.graph.order, graphEdges: adapter.graph.size },
        coordinates,
        physics: {
          simulationCreationCount: adapter.physics.creationCount,
          fixedNodeCount: physics.fixedNodeCount,
          mode: physics.mode,
          settledEdgeLengthRatio: physics.settledEdgeLengthRatio
        },
        selectedNodeId: adapter.selectedNodeId,
        selectedEdgeId: adapter.selectedEdgeId,
        hoveredNodeId: adapter.hoveredNodeId,
        hoveredEdgeId: adapter.hoveredEdgeId,
        technicalErrors: adapter.technicalErrors
      };
    }, name);
  }

  for (const name of ['z001', 'r001']) {
    const observed = await fresh(name);
    results.fresh[name] = observed;
    require(observed.topology.modelNodes === observed.topology.graphNodes
      && observed.topology.modelEdges === observed.topology.graphEdges,
      `${name}: GraphDTO/Graphology topology mismatch`);
    require(observed.coordinates?.nodes?.length === observed.topology.graphNodes,
      `${name}: missing four-layer node coordinates`);
    require(observed.coordinates?.nodes?.every(node => node.displayAvailable),
      `${name}: Sigma display cache missing node data`);
    require(observed.physics.fixedNodeCount === 0, `${name}: fresh fixedNodeCount=${observed.physics.fixedNodeCount}`);
    require(observed.selectedNodeId == null && observed.selectedEdgeId == null
      && observed.hoveredNodeId == null && observed.hoveredEdgeId == null,
      `${name}: interaction state leaked into fresh query`);
    require(observed.coordinates.state.customBBox == null, `${name}: customBBox leaked into fresh query`);
    require(observed.technicalErrors.length === 0, `${name}: ${observed.technicalErrors.join(' | ')}`);

    const nodeDelta = maxDelta(observed.coordinates.nodes, 'physicsX', 'physicsY', 'graphologyX', 'graphologyY');
    const viewportDelta = maxDelta(observed.coordinates.nodes, 'viewportX', 'viewportY', 'displayViewportX', 'displayViewportY');
    observed.coordinateDeltas = { physicsToGraphologyMax: nodeDelta, graphologyViewportToDisplayViewportMax: viewportDelta };
    require(nodeDelta <= 1e-6, `${name}: physics -> Graphology coordinate mismatch ${nodeDelta}`);
    require(viewportDelta <= 1e-4, `${name}: Graphology -> Sigma display cache mismatch ${viewportDelta}`);
    require(ratioSpread(observed.coordinates.edgeRatios.physics) >= 1,
      `${name}: physics edge ratio summary missing`);
  }

  // Verify that a drag-induced pin cannot survive a real fresh full query.
  await page.evaluate(() => window.__ATMKG_PHASE5__.loadPreset('z001'));
  const pinnedBefore = await page.evaluate(() => {
    const gate = window.__ATMKG_PHASE5__;
    const id = gate.model.snapshot().nodes[0].id;
    gate.adapter.pin(id);
    return { id, node: gate.adapter.physics.getNode(id), fixed: gate.adapter.physics.diagnostics(gate.adapter.graph).fixedNodeCount };
  });
  await page.evaluate(() => window.__ATMKG_PHASE5__.loadPreset('z001'));
  const freshAfterPin = await page.evaluate(id => {
    const gate = window.__ATMKG_PHASE5__;
    return {
      fixed: gate.adapter.physics.diagnostics(gate.adapter.graph).fixedNodeCount,
      selected: gate.adapter.selectedNodeId
    };
  }, pinnedBefore.id);
  results.pinReset = { pinnedBefore: { id: pinnedBefore.id, fixed: pinnedBefore.fixed }, freshAfterPin };
  require(pinnedBefore.fixed === 1, `pin reset setup failed: fixed=${pinnedBefore.fixed}`);
  require(freshAfterPin.fixed === 0, `pin leaked across fresh query: fixed=${freshAfterPin.fixed}`);
  require(freshAfterPin.selected == null, 'selection leaked across fresh query after pin');

  require(consoleErrors.length === 0, `console errors: ${consoleErrors.join(' | ')}`);
  require(pageErrors.length === 0, `page errors: ${pageErrors.join(' | ')}`);
  results.environment = { consoleErrors, pageErrors, base: BASE };
  await mkdir('test-results/sigma', { recursive: true });
  await writeFile('test-results/sigma/coordinate-consistency-results.json', JSON.stringify(results, null, 2));
  if (failures.length) throw new Error(failures.join('\n'));
  console.log('SIGMA_COORDINATE_CONSISTENCY_GATE');
  console.log('fresh_z001=PASS');
  console.log('fresh_r001=PASS');
  console.log('pin_reset=PASS');
  console.log('console_errors=0');
} finally {
  await browser.close();
}
