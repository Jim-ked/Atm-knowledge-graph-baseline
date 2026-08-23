import { chromium } from 'playwright-core';
import { mkdir, writeFile } from 'node:fs/promises';

const BASE = process.env.ATMKG_VIEWER_URL || 'http://127.0.0.1:18080/viewer/';
const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const EXPECTED = {
  z001: { nodes: 3, relationships: 2 },
  r001: { nodes: 14, relationships: 28 },
  as0001: { nodes: 5, relationships: 4 }
};
const failures = [];
const consoleErrors = [];
const pageErrors = [];
const results = { initial: {}, drag: {}, expansions: {}, environment: {} };
const require = (condition, message) => { if (!condition) failures.push(message); };

function assertComposition(name, observed) {
  const physics = observed.physics;
  require(physics.forceComposition.link === 'd3.forceLink/native-default-strength',
    `${name}: native d3 forceLink is not active`);
  require(physics.forceComposition.center === null
    && physics.forceComposition.x === 'd3.forceX'
    && physics.forceComposition.y === 'd3.forceY',
  `${name}: weak x/y center composition is not active`);
  require(physics.axisStrength === 0.03, `${name}: axis strength is ${physics.axisStrength}`);
  require(physics.physicsLinkCount <= observed.relationships,
    `${name}: physics links exceed rendered relationships`);
  require(physics.linkStrength.count === physics.physicsLinkCount,
    `${name}: link strength diagnostics do not cover all physics links`);
}

const browser = await chromium.launch({ headless: true, executablePath: CHROME });
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 });
  page.setDefaultTimeout(120_000);
  page.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text()); });
  page.on('pageerror', error => pageErrors.push(error.message));
  const response = await page.goto(BASE, { waitUntil: 'networkidle' });
  require(response?.status() === 200, `viewer HTTP ${response?.status()}`);
  await page.waitForFunction(() => window.__ATMKG_PHASE5__);
  await page.evaluate(() => window.__ATMKG_PHASE5__.switchEngine('g6'));

  async function observe() {
    return page.evaluate(() => {
      const gate = window.__ATMKG_PHASE5__;
      const snapshot = gate.model.snapshot();
      return {
        nodes: snapshot.nodes.length,
        relationships: snapshot.relationships.length,
        renderedNodes: gate.adapter.graph.getNodeData().length,
        renderedRelationships: gate.adapter.graph.getEdgeData().length,
        simulationCreationCount: gate.adapter.physics.creationCount,
        physics: gate.adapter.physics.diagnostics(),
        adapterErrors: gate.adapter.diagnostics().technicalErrors
      };
    });
  }

  for (const [name, expected] of Object.entries(EXPECTED)) {
    await page.evaluate(preset => window.__ATMKG_PHASE5__.loadPreset(preset), name);
    const observed = await observe();
    results.initial[name] = observed;
    require(observed.nodes === expected.nodes && observed.relationships === expected.relationships,
      `${name}: GraphDTO topology count mismatch`);
    require(observed.nodes === observed.renderedNodes
      && observed.relationships === observed.renderedRelationships,
    `${name}: rendered topology differs from GraphDTO`);
    require(observed.simulationCreationCount === 1, `${name}: simulation was recreated inside one adapter`);
    require(observed.physics.lastReconcile.initialCollisionViolations === 0,
      `${name}: initial collision violations=${observed.physics.lastReconcile.initialCollisionViolations}`);
    require(observed.adapterErrors.length === 0, `${name}: ${observed.adapterErrors.join(' | ')}`);
    assertComposition(name, observed);
  }
  require(results.initial.r001.physics.linkStrength.uniqueCount > 1,
    'r001: native topology-aware strength did not produce multiple strengths');
  require(results.initial.r001.physics.linkStrength.distribution['0.55'] === undefined,
    'r001: obsolete fixed link strength 0.55 is still present');

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
    await page.mouse.move(graphBox.x + routeViewport[0] + step * 12,
      graphBox.y + routeViewport[1] + step * 5);
    await page.waitForTimeout(60);
  }
  await page.mouse.up();
  await page.waitForTimeout(300);
  results.drag.r001 = await page.evaluate(id => {
    const adapter = window.__ATMKG_PHASE5__.adapter;
    const node = adapter.physics.getNode(id);
    return {
      fixed: node.fx != null && node.fy != null,
      fixedNodeCount: adapter.physics.diagnostics().fixedNodeCount,
      observation: adapter.diagnostics().dragObservation
    };
  }, routeId);
  require(results.drag.r001.fixed, 'r001 drag: fixed node did not stay pinned');
  require(results.drag.r001.fixedNodeCount === 1,
    `r001 drag: fixed node count=${results.drag.r001.fixedNodeCount}`);

  async function expand(name, kind) {
    await page.evaluate(preset => window.__ATMKG_PHASE5__.loadPreset(preset), name);
    await page.evaluate(targetKind => window.__ATMKG_PHASE5__.selectNodeByKind(targetKind), kind);
    const before = await observe();
    await page.evaluate(() => window.__ATMKG_PHASE5__.expandSelected());
    const after = await observe();
    const expansion = await page.evaluate(() => window.__ATMKG_PHASE5__.adapter.diagnostics().lastExpansion);
    const observed = { before, after, expansion };
    results.expansions[name] = observed;
    require(expansion.addedNodes > 0 && expansion.addedEdges > 0,
      `${name}: expansion did not add topology`);
    require(expansion.oldIdentityPreserved === true, `${name}: old simulation node identity changed`);
    require(expansion.oldStateReset === false, `${name}: old simulation node state was reset`);
    require(expansion.anchorPinned === true && expansion.anchorDisplacement === 0,
      `${name}: expansion anchor drift=${expansion.anchorDisplacement}`);
    require(after.nodes === after.renderedNodes && after.relationships === after.renderedRelationships,
      `${name}: expanded rendered topology differs from GraphDTO`);
    require(after.physics.lastReconcile.initialCollisionViolations === 0,
      `${name}: expansion initial collision violations=${after.physics.lastReconcile.initialCollisionViolations}`);
    require(after.physics.lastReconcile.newLinkInitialDistanceRatio.count > 0,
      `${name}: new link initial distance was not recorded`);
    require(after.physics.fixedNodeCount === 1,
      `${name}: expansion fixed node count=${after.physics.fixedNodeCount}`);
    require(after.adapterErrors.length === 0, `${name}: ${after.adapterErrors.join(' | ')}`);
    assertComposition(`${name} expansion`, after);
  }

  await expand('z001', 'Runway');
  await expand('r001', 'NavigationAid');

  require(consoleErrors.length === 0, `console errors: ${consoleErrors.join(' | ')}`);
  require(pageErrors.length === 0, `page errors: ${pageErrors.join(' | ')}`);
  results.environment = {
    consoleErrors,
    pageErrors,
    viewerUrl: BASE,
    userAgent: await page.evaluate(() => navigator.userAgent)
  };
  await mkdir('test-results/g6-physics-composition', { recursive: true });
  await writeFile('test-results/g6-physics-composition/results.json', JSON.stringify(results, null, 2));
  if (failures.length) throw new Error(failures.join('\n'));

  console.log('G6_PHYSICS_COMPOSITION_OK');
  console.log(`r001_physics_links=${results.initial.r001.physics.physicsLinkCount}`);
  console.log(`r001_degree_distribution=${JSON.stringify(results.initial.r001.physics.physicalDegreeDistribution)}`);
  console.log(`r001_strength_distribution=${JSON.stringify(results.initial.r001.physics.linkStrength.distribution)}`);
  for (const name of ['z001', 'r001']) {
    const item = results.expansions[name];
    console.log(`${name}_initial_collision_violations=${item.after.physics.lastReconcile.initialCollisionViolations}`);
    console.log(`${name}_new_link_initial_distance_ratio=${JSON.stringify(item.after.physics.lastReconcile.newLinkInitialDistanceRatio)}`);
    console.log(`${name}_old_displacement=${JSON.stringify({
      mean: item.expansion.meanOldDisplacement,
      max: item.expansion.maxOldDisplacement
    })}`);
    console.log(`${name}_settled_edge_ratio=${JSON.stringify(item.after.physics.settledEdgeLengthRatio)}`);
  }
  console.log('console_errors=0');
  console.log('page_errors=0');
} finally {
  await browser.close();
}
