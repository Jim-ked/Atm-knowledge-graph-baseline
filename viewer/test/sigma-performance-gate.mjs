import { chromium } from 'playwright-core';
import { mkdir, writeFile } from 'node:fs/promises';

const BASE = process.env.ATMKG_VIEWER_URL || 'http://127.0.0.1:18080/viewer/?debug=true';
const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const failures = [];
const consoleErrors = [];
const pageErrors = [];
const results = { operations: {}, expand: {}, environment: {} };
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

  async function reset() {
    return page.evaluate(() => window.__ATMKG_PHASE5__.resetPerformanceDiagnostics());
  }
  async function delta() {
    return page.evaluate(() => window.__ATMKG_PHASE5__.performanceDiagnostics());
  }
  function compact(current) {
    const grouped = new Map();
    for (const item of current.measures) {
      const entry = grouped.get(item.name) ?? { count: 0, totalMs: 0, maxMs: 0 };
      entry.count += 1; entry.totalMs += item.duration; entry.maxMs = Math.max(entry.maxMs, item.duration);
      grouped.set(item.name, entry);
    }
    return {
      counters: current.counters,
      measures: Object.fromEntries([...grouped].map(([name, value]) => [name, {
        count: value.count, totalMs: Math.round(value.totalMs * 100) / 100, maxMs: Math.round(value.maxMs * 100) / 100
      }]))
    };
  }
  async function record(name, action) {
    await reset();
    const started = await page.evaluate(() => performance.now());
    const actionResult = await action();
    const ended = await page.evaluate(() => performance.now());
    const current = await delta();
    const physics = await page.evaluate(() => window.__ATMKG_PHASE5__.adapter?.diagnostics?.().physics ?? null);
    const summary = compact(current);
    results.operations[name] = { durationMs: ended - started, ...summary, physics };
    return { ...current, actionResult, summary, physics };
  }

  await record('loadZ001', async () => page.evaluate(() => window.__ATMKG_PHASE5__.loadPreset('z001')));
  const z001Load = results.operations.loadZ001.physics;
  require(z001Load?.provider === 'native-d3-force', 'Z001 did not use native D3 physics');
  require(z001Load.displayRelationshipCount === z001Load.physicsLinkCount,
    'Z001 display/physics counts unexpectedly differ');
  require(z001Load.lastReconcile.initialCollisionViolations === 0, 'Z001 initial collision violation');
  const node = await page.evaluate(() => window.__ATMKG_PHASE5__.adapter.graph.nodes()[0]);
  const click = await record('clickNode', () => page.evaluate(id => {
    const adapter = window.__ATMKG_PHASE5__.adapter;
    adapter.renderer.emit('clickNode', { node: id, event: {} });
  }, node));
  require(click.counters.layoutPolicy === 0, 'clickNode unexpectedly ran layout');
  require(click.counters.selectionNotifications === 1, `clickNode notifications=${click.counters.selectionNotifications}`);
  require(click.counters.rendererRefresh <= 2, `clickNode refresh storm=${click.counters.rendererRefresh}`);
  require(click.counters.reducerPrepare <= 2, `clickNode reducer prepare=${click.counters.reducerPrepare}`);
  const repeatedSelection = await record('repeatSameNodeSelection', () => page.evaluate(id =>
    window.__ATMKG_PHASE5__.adapter.applySelection(id), node));
  require(repeatedSelection.counters.rendererRefresh === 0,
    `idempotent node selection refreshed=${repeatedSelection.counters.rendererRefresh}`);

  const hover = await record('hoverNode', () => page.evaluate(id => {
    const adapter = window.__ATMKG_PHASE5__.adapter;
    adapter.renderer.emit('enterNode', { node: id, event: {} });
    adapter.renderer.emit('leaveNode', { node: id, event: {} });
  }, node));
  require(hover.counters.layoutPolicy === 0, 'hoverNode unexpectedly ran layout');

  await page.evaluate(() => window.__ATMKG_PHASE5__.loadPreset('z001'));
  const edge = await page.evaluate(() => window.__ATMKG_PHASE5__.adapter.graph.edges()[0]);
  const edgeClick = await record('clickEdge', () => page.evaluate(id => {
    window.__ATMKG_PHASE5__.adapter.renderer.emit('clickEdge', { edge: id, event: {} });
  }, edge));
  require(edgeClick.counters.layoutPolicy === 0, 'clickEdge unexpectedly ran layout');
  require(edgeClick.counters.selectionNotifications === 1, `clickEdge notifications=${edgeClick.counters.selectionNotifications}`);
  require(edgeClick.counters.rendererRefresh <= 2, `clickEdge refresh storm=${edgeClick.counters.rendererRefresh}`);
  await page.evaluate(() => window.__ATMKG_PHASE5__.loadPreset('z001'));

  const drag = await record('dragNodeOneSecond', () => page.evaluate(async id => {
    const gate = window.__ATMKG_PHASE5__;
    const adapter = gate.adapter;
    const before = adapter.graph.getNodeAttributes(id);
    const viewport = adapter.renderer.graphToViewport({ x: before.x, y: before.y });
    adapter.renderer.emit('downNode', { node: id, event: {
      preventSigmaDefault() {}, original: { preventDefault() {} }
    } });
    const started = performance.now();
    let events = 0;
    while (performance.now() - started < 1000) {
      events += 1;
      adapter.captor.emit('mousemovebody', {
        x: viewport.x + events, y: viewport.y + events * 0.5,
        preventSigmaDefault() {}
      });
      await new Promise(resolve => setTimeout(resolve, 16));
    }
    adapter.up();
    return { syntheticEvents: events };
  }, node));
  require(drag.counters.layoutPolicy === 0, 'drag unexpectedly ran layout');
  require(drag.counters.rendererRefresh >= drag.counters.dragFrames,
    `drag refresh count lower than drag frames: ${drag.counters.rendererRefresh}/${drag.counters.dragFrames}`);
  require(drag.counters.rendererRefresh <= drag.counters.dragFrames + 1,
    `drag refresh exceeds frame budget: ${drag.counters.rendererRefresh}/${drag.counters.dragFrames}`);
  results.operations.dragNodeOneSecond.synthetic = drag.actionResult?.syntheticEvents;

  await page.evaluate(() => window.__ATMKG_PHASE5__.loadPreset('z001'));
  const expand = await record('expandZ001', () => page.evaluate(async () => {
    const gate = window.__ATMKG_PHASE5__;
    await gate.selectNodeByKind('Runway');
    await gate.expandSelected();
  }));
  results.expand.z001 = { ...expand.summary, physics: expand.physics };
  require(expand.counters.graphModelMerge === 1, `expand GraphModel merge=${expand.counters.graphModelMerge}`);
  require(expand.counters.d3Force === 1 && expand.counters.noverlap === 0,
    'expand did not show one native D3 run without default Noverlap');
  require(expand.counters.rendererRefresh <= 3, `Z001 expand refresh=${expand.counters.rendererRefresh}`);

  const as0001 = await record('loadAS0001', () => page.evaluate(() => window.__ATMKG_PHASE5__.loadPreset('as0001')));
  require(as0001.physics?.provider === 'native-d3-force', 'AS0001 did not use native D3 physics');
  require(as0001.physics?.displayRelationshipCount >= as0001.physics?.physicsLinkCount,
    'AS0001 physics/display relationship counts are invalid');

  const rebalance = await record('rebalance', () => page.evaluate(async () => {
    await window.__ATMKG_PHASE5__.adapter.rebalance();
    await window.__ATMKG_PHASE5__.adapter.layoutReady;
  }));
  require(rebalance.counters.layoutPolicy === 1, 'rebalance did not execute one layout policy');

  const fit = await record('fitViewport', () => page.evaluate(() => window.__ATMKG_PHASE5__.adapter.fit()));
  require(fit.counters.layoutPolicy === 0, 'fit unexpectedly ran layout');
  require(fit.counters.cameraOperations >= 1, 'fit did not perform a camera operation');

  await page.evaluate(() => window.__ATMKG_PHASE5__.loadPreset('r001'));
  const rNode = await page.evaluate(() => window.__ATMKG_PHASE5__.adapter.graph.nodes()[0]);
  const rClick = await record('r001ClickNode', () => page.evaluate(id => {
    window.__ATMKG_PHASE5__.adapter.renderer.emit('clickNode', { node: id, event: {} });
  }, rNode));
  require(rClick.counters.selectionNotifications === 1, `R001 click notifications=${rClick.counters.selectionNotifications}`);
  require(rClick.counters.rendererRefresh <= 2, `R001 click refresh=${rClick.counters.rendererRefresh}`);
  require(rClick.physics?.displayRelationshipCount >= rClick.physics?.physicsLinkCount,
    'R001 display/physics counts are invalid');
  require(rClick.physics?.linkStrength?.uniqueCount > 1, 'R001 native link strengths are unexpectedly uniform');
  const rDrag = await record('r001DragOneSecond', () => page.evaluate(async id => {
    const adapter = window.__ATMKG_PHASE5__.adapter;
    const before = adapter.graph.getNodeAttributes(id);
    const viewport = adapter.renderer.graphToViewport({ x: before.x, y: before.y });
    adapter.renderer.emit('downNode', { node: id, event: {
      preventSigmaDefault() {}, original: { preventDefault() {} }
    } });
    const started = performance.now(); let events = 0;
    while (performance.now() - started < 1000) {
      events += 1;
      adapter.captor.emit('mousemovebody', {
        x: viewport.x + events, y: viewport.y + events * 0.5,
        preventSigmaDefault() {}
      });
      await new Promise(resolve => setTimeout(resolve, 16));
    }
    adapter.up();
    return { syntheticEvents: events };
  }, rNode));
  require(rDrag.counters.layoutPolicy === 0, 'R001 drag unexpectedly ran layout');
  require(rDrag.counters.rendererRefresh <= rDrag.counters.dragFrames + 1,
    `R001 drag refresh=${rDrag.counters.rendererRefresh}/${rDrag.counters.dragFrames}`);
  const rExpand = await record('expandR001', () => page.evaluate(async () => {
    const gate = window.__ATMKG_PHASE5__;
    await gate.selectNodeByKind('NavigationAid');
    await gate.expandSelected();
  }));
  results.expand.r001 = { ...rExpand.summary, physics: rExpand.physics };
  require(rExpand.counters.rendererRefresh <= 3, `R001 expand refresh=${rExpand.counters.rendererRefresh}`);
  require(rExpand.physics?.lastReconcile.initialCollisionViolations === 0,
    'R001 expand initial collision violation');

  results.comparisons = {};
  for (const scene of ['z001', 'r001']) {
    await page.evaluate(name => window.__ATMKG_PHASE5__.loadPreset(name), scene);
    const comparison = await record(`${scene}GraphologyForce`, () => page.evaluate(async () => {
      await window.__ATMKG_PHASE5__.adapter.setLayoutPolicy('GRAPHOLOGY_FORCE');
      await window.__ATMKG_PHASE5__.adapter.layoutReady;
    }));
    const shape = await page.evaluate(() => {
      const adapter = window.__ATMKG_PHASE5__.adapter;
      const graph = adapter.graph;
      const radius = id => Number(graph.getNodeAttribute(id, 'size')) * 2 + 10;
      let overlaps = 0;
      const nodes = graph.nodes();
      for (let i = 0; i < nodes.length; i += 1) for (let j = i + 1; j < nodes.length; j += 1) {
        const left = graph.getNodeAttributes(nodes[i]);
        const right = graph.getNodeAttributes(nodes[j]);
        if (Math.hypot(left.x - right.x, left.y - right.y) < radius(nodes[i]) + radius(nodes[j])) overlaps += 1;
      }
      const lengths = graph.edges().map(edge => {
        const [source, target] = graph.extremities(edge);
        const left = graph.getNodeAttributes(source); const right = graph.getNodeAttributes(target);
        return Math.hypot(left.x - right.x, left.y - right.y);
      }).sort((a, b) => a - b);
      return {
        displayRelationshipCount: graph.size,
        nodeCount: graph.order,
        overlapCount: overlaps,
        edgeLength: { p50: lengths[Math.max(0, Math.ceil(lengths.length * 0.5) - 1)] ?? null,
          p90: lengths[Math.max(0, Math.ceil(lengths.length * 0.9) - 1)] ?? null,
          max: lengths.at(-1) ?? null }
      };
    });
    results.comparisons[scene] = { counters: comparison.counters, shape };
  }

  await page.evaluate(() => window.__ATMKG_PHASE5__.loadPreset('z001'));
  const fa2Race = await record('fa2DestroyRace', () => page.evaluate(async () => {
    const adapter = window.__ATMKG_PHASE5__.adapter;
    adapter.setLayoutPolicy('FORCE_ATLAS2');
    await adapter.destroy();
    await new Promise(resolve => setTimeout(resolve, 500));
  }));
  require(fa2Race.counters.fa2WorkerCreate === 1 && fa2Race.counters.fa2WorkerStart === 1,
    'FA2 destroy race did not create/start a worker');
  require(fa2Race.counters.fa2WorkerKill === 1, `FA2 worker kill=${fa2Race.counters.fa2WorkerKill}`);
  await page.evaluate(() => window.__ATMKG_PHASE5__.loadPreset('z001'));

  results.environment = {
    consoleErrors, pageErrors,
    userAgent: await page.evaluate(() => navigator.userAgent)
  };
  require(consoleErrors.length === 0, `console errors: ${consoleErrors.join(' | ')}`);
  require(pageErrors.length === 0, `page errors: ${pageErrors.join(' | ')}`);
  await mkdir('test-results/sigma', { recursive: true });
  await writeFile('test-results/sigma/performance-results.json', JSON.stringify(results, null, 2));
  if (failures.length) throw new Error(failures.join('\n'));
  console.log('SIGMA_PERFORMANCE_DIAGNOSTICS_OK');
  console.log(JSON.stringify(results, null, 2));
} finally {
  await browser.close();
}
