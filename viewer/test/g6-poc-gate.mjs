import { chromium } from 'playwright-core';
import { mkdir, writeFile } from 'node:fs/promises';

const BASE = process.env.ATMKG_VIEWER_URL || 'http://127.0.0.1:18080/viewer/';
const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const EXPECTED = {
  z001: { nodes: 3, relationships: 2, selectKind: 'Airport' },
  r001: { nodes: 14, relationships: 28, selectKind: 'Route' },
  as0001: { nodes: 5, relationships: 4, selectKind: 'Airspace' }
};
const failures = [];
const consoleErrors = [];
const pageErrors = [];
const results = { scenarios: {}, drag: {}, labels: {}, parallelEdges: {}, expansion: {}, config: {} };
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
  await mkdir('test-results/g6-poc', { recursive: true });

  for (const [name, expected] of Object.entries(EXPECTED)) {
    await page.evaluate(preset => window.__ATMKG_PHASE5__.loadPreset(preset), name);
    await page.evaluate(kind => window.__ATMKG_PHASE5__.selectNodeByKind(kind), expected.selectKind);
    const observed = await page.evaluate(() => {
      const gate = window.__ATMKG_PHASE5__;
      const graph = gate.adapter.graph;
      const snapshot = gate.model.snapshot();
      const points = graph.getNodeData().map(node => ({ id: node.id, point: graph.getElementPosition(node.id) }));
      let minimumCenterDistance = Infinity;
      for (let i = 0; i < points.length; i += 1) {
        for (let j = i + 1; j < points.length; j += 1) {
          minimumCenterDistance = Math.min(minimumCenterDistance,
            Math.hypot(points[i].point[0] - points[j].point[0], points[i].point[1] - points[j].point[1]));
        }
      }
      const selected = snapshot.state.selectedNodeId;
      const selectedLabel = graph.context.element.getElement(selected).getShape('label').style.visibility;
      return {
        nodes: snapshot.nodes.length,
        relationships: snapshot.relationships.length,
        minimumCenterDistance,
        selectedState: graph.getElementState(selected),
        selectedLabel,
        diagnostic: gate.adapter.diagnostics()
      };
    });
    results.scenarios[name] = observed;
    require(observed.nodes === expected.nodes && observed.relationships === expected.relationships,
      `${name} graph count mismatch`);
    require(observed.minimumCenterDistance >= 45.5, `${name} collision distance=${observed.minimumCenterDistance}`);
    require(observed.selectedState.includes('selected') && observed.selectedLabel === 'visible',
      `${name} selected node/label not visible`);
    await page.screenshot({ path: `test-results/g6-poc/${name}.png`, fullPage: true });
  }

  await page.evaluate(() => window.__ATMKG_PHASE5__.loadPreset('r001'));
  const routeId = await page.evaluate(() => window.__ATMKG_PHASE5__.selectNodeByKind('Route'));
  results.config = await page.evaluate(() => {
    const diagnostic = window.__ATMKG_PHASE5__.adapter.diagnostics();
    return {
      layout: diagnostic.layout,
      config: diagnostic.config,
      behaviorTypes: diagnostic.behaviors.map(behavior => behavior.type),
      dragTrigger: diagnostic.behaviors.find(behavior => behavior.type === 'drag-element-force')?.trigger ?? null,
      transform: diagnostic.transforms[0]
    };
  });
  require(results.config.layout === 'persistent-d3-force', 'layout is not persistent-d3-force');
  require(results.config.behaviorTypes.includes('drag-element'), 'external-force drag-element missing');
  require(results.config.dragTrigger === null, 'drag requires an unexpected shortcut key');
  require(results.config.behaviorTypes.includes('auto-adapt-label'), 'auto-adapt-label missing');
  require(results.config.transform.mode === 'bundle', 'parallel edges are not bundled');

  async function dragRoute(fixed, horizontal) {
    await page.evaluate(value => window.__ATMKG_PHASE5__.setG6Fixed(value), fixed);
    const box = await page.locator('#graph').boundingBox();
    const before = await page.evaluate(id => {
      const graph = window.__ATMKG_PHASE5__.adapter.graph;
      const neighbors = graph.getNeighborNodesData(id).map(node => node.id);
      return {
        viewport: graph.getViewportByCanvas(graph.getElementPosition(id)),
        neighbors: Object.fromEntries(neighbors.map(neighbor => [neighbor, graph.getElementPosition(neighbor)]))
      };
    }, routeId);
    await page.mouse.move(box.x + before.viewport[0], box.y + before.viewport[1]);
    await page.waitForTimeout(150);
    await page.mouse.down();
    await page.waitForTimeout(150);
    for (let step = 1; step <= 10; step += 1) {
      await page.mouse.move(
        box.x + before.viewport[0] + horizontal * step / 10,
        box.y + before.viewport[1] + 50 * step / 10
      );
      await page.waitForTimeout(80);
    }
    const during = await page.evaluate(({ id, neighborIds }) => {
      const graph = window.__ATMKG_PHASE5__.adapter.graph;
      return {
        node: graph.getElementPosition(id),
        neighbors: Object.fromEntries(neighborIds.map(neighbor => [neighbor, graph.getElementPosition(neighbor)]))
      };
    }, { id: routeId, neighborIds: Object.keys(before.neighbors) });
    await page.mouse.up();
    const released = await page.evaluate(id => window.__ATMKG_PHASE5__.adapter.graph.getElementPosition(id), routeId);
    await page.waitForTimeout(1000);
    const settled = await page.evaluate(id => window.__ATMKG_PHASE5__.adapter.graph.getElementPosition(id), routeId);
    const neighborDistances = Object.entries(before.neighbors).map(([id, point]) =>
      Math.hypot(during.neighbors[id][0] - point[0], during.neighbors[id][1] - point[1]));
    return {
      fixed,
      neighborMeanMovement: neighborDistances.reduce((sum, value) => sum + value, 0) / neighborDistances.length,
      neighborMaxMovement: Math.max(...neighborDistances),
      releaseDrift: Math.hypot(settled[0] - released[0], settled[1] - released[1])
    };
  }
  results.drag.fixedFalse = await dragRoute(false, 120);
  results.drag.fixedTrue = await dragRoute(true, -120);
  require(results.drag.fixedFalse.neighborMeanMovement > 5, 'fixed=false drag did not move neighbors in real time');
  require(results.drag.fixedTrue.neighborMeanMovement > 5, 'fixed=true drag did not move neighbors in real time');
  require(results.drag.fixedFalse.releaseDrift > 5, 'fixed=false node did not continue balancing after release');
  require(results.drag.fixedTrue.releaseDrift < 1, 'fixed=true node did not stay fixed after release');

  results.labels = await page.evaluate(async () => {
    const graph = window.__ATMKG_PHASE5__.adapter.graph;
    const selected = window.__ATMKG_PHASE5__.model.snapshot().state.selectedNodeId;
    const edges = graph.getEdgeData();
    const unrelated = edges.find(edge => edge.source !== selected && edge.target !== selected);
    const visibility = edges.map(edge => graph.context.element.getElement(edge.id).getShape('label').style.visibility);
    const beforeOpacity = graph.getElementRenderStyle(unrelated.id).labelOpacity;
    graph.emit('edge:pointerover', { target: { id: unrelated.id } });
    await new Promise(resolve => setTimeout(resolve, 50));
    const hoverOpacity = graph.getElementRenderStyle(unrelated.id).labelOpacity;
    graph.emit('edge:pointerleave', { target: { id: unrelated.id } });
    return {
      hiddenByAutoAdapt: visibility.filter(value => value === 'hidden').length,
      visible: visibility.filter(value => value === 'visible').length,
      beforeOpacity,
      hoverOpacity
    };
  });
  require(results.labels.hiddenByAutoAdapt > 0, 'auto-adapt-label did not hide any conflicting labels');
  require(results.labels.beforeOpacity === 0 && results.labels.hoverOpacity === 1,
    'relationship hover label did not become visible');

  await page.evaluate(() => window.__ATMKG_PHASE5__.loadPreset('z001'));
  results.parallelEdges = await page.evaluate(async () => {
    const graph = window.__ATMKG_PHASE5__.adapter.graph;
    const edge = graph.getEdgeData()[0];
    graph.addEdgeData([{ id: 'g6-poc-parallel-edge', source: edge.source, target: edge.target, data: { type: 'POC_PARALLEL' } }]);
    await graph.draw();
    const first = graph.getElementRenderStyle(edge.id);
    const second = graph.getElementRenderStyle('g6-poc-parallel-edge');
    return { firstOffset: first.curveOffset, secondOffset: second.curveOffset, arrows: [first.endArrow, second.endArrow] };
  });
  require(results.parallelEdges.firstOffset === 14 && results.parallelEdges.secondOffset === -14,
    'parallel bundle offsets are not separated');
  require(results.parallelEdges.arrows.every(Boolean), 'parallel edge direction arrows missing');

  await page.evaluate(() => window.__ATMKG_PHASE5__.loadPreset('z001'));
  await page.evaluate(() => window.__ATMKG_PHASE5__.selectNodeByKind('Runway'));
  await page.evaluate(() => window.__ATMKG_PHASE5__.expandSelected());
  results.expansion = await page.evaluate(() => ({
    ...window.__ATMKG_PHASE5__.adapter.diagnostics().lastExpansion,
    nodes: window.__ATMKG_PHASE5__.model.snapshot().nodes.length,
    relationships: window.__ATMKG_PHASE5__.model.snapshot().relationships.length
  }));
  require(results.expansion.nodes === 5 && results.expansion.relationships === 4, 'native one-hop expansion count mismatch');
  await page.screenshot({ path: 'test-results/g6-poc/expansion.png', fullPage: true });

  require(consoleErrors.length === 0, `console errors: ${consoleErrors.join(' | ')}`);
  require(pageErrors.length === 0, `page errors: ${pageErrors.join(' | ')}`);
  results.environment = { consoleErrors, pageErrors, viewerUrl: BASE, userAgent: await page.evaluate(() => navigator.userAgent) };
  await writeFile('test-results/g6-poc/results.json', JSON.stringify(results, null, 2));
  if (failures.length) throw new Error(failures.join('\n'));

  console.log('G6_BROWSER_POC_OK');
  console.log('persistent_d3_force=PASS');
  console.log('external_force_drag_neighbors_follow=PASS');
  console.log('fixed_false_release_balance=PASS');
  console.log('fixed_true_stays_fixed=PASS');
  console.log('collision_visual_radius=PASS');
  console.log('auto_adapt_label=PASS');
  console.log('parallel_edges_bundle=PASS');
  console.log('persistent_addData_draw=PASS');
} finally {
  await browser.close();
}
