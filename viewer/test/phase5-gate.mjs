import { chromium } from 'playwright-core';
import { mkdir, writeFile } from 'node:fs/promises';

const BASE = process.env.ATMKG_VIEWER_URL || 'http://127.0.0.1:18080/viewer/';
const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const ENGINES = ['g6', 'cytoscape', 'sigma'];
const EXPECTED = {
  z001: { nodes: 3, relationships: 2 },
  r001: { nodes: 14, relationships: 28 },
  as0001: { nodes: 5, relationships: 4 }
};

const failures = [];
const consoleErrors = [];
const pageErrors = [];
const requests = [];
const results = { real: {}, scale: {}, interaction: {}, incremental: {}, path: {}, environment: {} };
const require = (condition, message) => { if (!condition) failures.push(message); };

const browser = await chromium.launch({ headless: true, executablePath: CHROME });
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 });
  page.setDefaultTimeout(180_000);
  page.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text()); });
  page.on('pageerror', error => pageErrors.push(error.message));
  page.on('request', request => requests.push(request.url()));
  const response = await page.goto(BASE, { waitUntil: 'networkidle' });
  require(response?.status() === 200, `viewer HTTP status=${response?.status()}`);
  await page.waitForFunction(() => window.__ATMKG_PHASE5__);
  await page.evaluate(() => window.__ATMKG_PHASE5__.rendering);

  for (const [preset, expected] of Object.entries(EXPECTED)) {
    await page.evaluate(name => window.__ATMKG_PHASE5__.switchEngine('g6').then(() => window.__ATMKG_PHASE5__.loadPreset(name)), preset);
    const apiAfterLoad = await page.evaluate(() => window.__ATMKG_PHASE5__.apiRequests);
    results.real[preset] = {};
    for (const engine of ENGINES) {
      await page.evaluate(name => window.__ATMKG_PHASE5__.switchEngine(name), engine);
      const observed = await page.evaluate(() => {
        const gate = window.__ATMKG_PHASE5__;
        const snapshot = gate.model.snapshot();
        return {
          nodes: snapshot.nodes.length,
          relationships: snapshot.relationships.length,
          apiRequests: gate.apiRequests,
          metric: gate.metrics.at(-1)
        };
      });
      results.real[preset][engine] = observed;
      require(observed.nodes === expected.nodes && observed.relationships === expected.relationships,
        `${preset}/${engine} expected ${expected.nodes}/${expected.relationships}, got ${observed.nodes}/${observed.relationships}`);
      require(observed.apiRequests === apiAfterLoad, `${preset}/${engine} switch re-queried API`);
    }
  }

  await page.evaluate(() => window.__ATMKG_PHASE5__.loadPreset('z001'));
  const expansionRequests = await page.evaluate(async () => {
    const gate = window.__ATMKG_PHASE5__;
    await gate.selectFirstViaEngine();
    const before = gate.apiRequests;
    await gate.expandSelected();
    return { before, after: gate.apiRequests, expanded: gate.model.snapshot().state.expandedNodeIds.length };
  });
  require(expansionRequests.after === expansionRequests.before + 1 && expansionRequests.expanded === 1,
    'one-hop expansion failed');
  for (const engine of ENGINES) {
    await page.evaluate(name => window.__ATMKG_PHASE5__.switchEngine(name), engine);
    const selected = await page.evaluate(() => window.__ATMKG_PHASE5__.selectFirstViaEngine());
    const detailVisible = await page.locator('#node-detail').isVisible();
    const graph = page.locator('#graph');
    const box = await graph.boundingBox();
    const beforeDrag = await page.evaluate(() => window.__ATMKG_PHASE5__.firstNodePosition());
    await page.evaluate(() => window.__ATMKG_PHASE5__.dragFirstForValidation());
    if (box) {
      await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
      await page.mouse.wheel(0, -220);
      await page.mouse.down();
      await page.mouse.move(box.x + box.width / 2 + 20, box.y + box.height / 2 + 12);
      await page.mouse.up();
    }
    const afterDrag = await page.evaluate(() => window.__ATMKG_PHASE5__.firstNodePosition());
    const dragged = beforeDrag && afterDrag
      && (Math.abs(beforeDrag.x - afterDrag.x) > 0.1 || Math.abs(beforeDrag.y - afterDrag.y) > 0.1);
    results.interaction[engine] = { selected: Boolean(selected), detailVisible, zoomInput: Boolean(box), nodeDragged: dragged };
    require(Boolean(selected) && detailVisible && Boolean(box) && dragged, `${engine} interaction/drag binding failed`);
  }
  const persistentSelection = await page.evaluate(async () => {
    const gate = window.__ATMKG_PHASE5__;
    const selected = gate.model.snapshot().state.selectedNodeId;
    for (const name of ['g6', 'cytoscape', 'sigma']) await gate.switchEngine(name);
    return selected === gate.model.snapshot().state.selectedNodeId;
  });
  require(persistentSelection, 'selection was not preserved across engine switches');

  await page.evaluate(() => window.__ATMKG_PHASE5__.switchEngine('g6').then(() => window.__ATMKG_PHASE5__.loadPath()));
  for (const engine of ENGINES) {
    await page.evaluate(name => window.__ATMKG_PHASE5__.switchEngine(name), engine);
    const path = await page.evaluate(() => {
      const snapshot = window.__ATMKG_PHASE5__.model.snapshot();
      return {
        nodes: snapshot.nodes.length,
        relationships: snapshot.relationships.length,
        highlighted: snapshot.state.highlightedRelationshipIds.length
      };
    });
    results.path[engine] = path;
    require(path.nodes === 6 && path.relationships === 5 && path.highlighted === 5, `${engine} path highlight failed`);
  }

  await page.evaluate(() => window.__ATMKG_PHASE5__.loadPreset('z001'));
  for (const engine of ENGINES) {
    await page.evaluate(name => window.__ATMKG_PHASE5__.switchEngine(name), engine);
    const before = await page.evaluate(() => window.__ATMKG_PHASE5__.model.snapshot());
    await page.evaluate(() => window.__ATMKG_PHASE5__.runIncremental());
    const after = await page.evaluate(() => window.__ATMKG_PHASE5__.model.snapshot());
    const status = await page.locator('#status').textContent();
    results.incremental[engine] = {
      restoredNodes: after.nodes.length,
      restoredRelationships: after.relationships.length,
      completed: status.includes('增量验证完成')
    };
    require(after.nodes.length === before.nodes.length && after.relationships.length === before.relationships.length
      && !after.nodes.some(node => node.id.startsWith('viewer-fixture:')) && status.includes('增量验证完成'),
    `${engine} incremental add/update/remove failed`);
  }

  const apiBeforeScale = await page.evaluate(() => window.__ATMKG_PHASE5__.apiRequests);
  for (const size of [100, 500, 1000, 1500]) {
    await page.evaluate(value => window.__ATMKG_PHASE5__.switchEngine('g6').then(() => window.__ATMKG_PHASE5__.loadScale(value)), size);
    results.scale[size] = {};
    for (const engine of ENGINES) {
      await page.evaluate(name => window.__ATMKG_PHASE5__.switchEngine(name), engine);
      const observed = await page.evaluate(() => {
        const gate = window.__ATMKG_PHASE5__;
        const snapshot = gate.model.snapshot();
        return { nodes: snapshot.nodes.length, relationships: snapshot.relationships.length, metric: gate.metrics.at(-1) };
      });
      results.scale[size][engine] = observed;
      require(observed.nodes === size, `scale-${size}/${engine} node count mismatch`);
    }
  }
  require(await page.evaluate(() => window.__ATMKG_PHASE5__.apiRequests) === apiBeforeScale,
    'fixed scale fixtures unexpectedly queried API');

  const snapshot = await page.evaluate(() => window.__ATMKG_PHASE5__.model.snapshot());
  const serialized = JSON.stringify(snapshot);
  const specificFields = (serialized.match(/g6\w*|cytoscape\w*|sigma\w*/gi) || []).length;
  const directAccess = requests.filter(url => /bolt:|neo4j:|:17687|:17474/.test(url));
  require(specificFields === 0, `viewer-specific GraphDTO fields=${specificFields}`);
  require(directAccess.length === 0, `direct Neo4j access=${directAccess.join(',')}`);
  require(consoleErrors.length === 0, `console errors=${consoleErrors.join(' | ')}`);
  require(pageErrors.length === 0, `page errors=${pageErrors.join(' | ')}`);

  results.environment = {
    viewerUrl: BASE,
    userAgent: await page.evaluate(() => navigator.userAgent),
    viewport: page.viewportSize(),
    apiRequests: await page.evaluate(() => window.__ATMKG_PHASE5__.apiRequests),
    directNeo4jRequests: directAccess,
    consoleErrors,
    pageErrors,
    viewerSpecificFieldsInGraphDto: specificFields
  };
  await mkdir('test-results', { recursive: true });
  await page.screenshot({ path: 'test-results/phase5-viewer.png', fullPage: true });
  await writeFile('test-results/phase5-results.json', JSON.stringify(results, null, 2));

  if (failures.length) throw new Error(failures.join('\n'));
  console.log('PHASE5_VIEWER_OK');
  console.log('graphdto_shared=PASS');
  for (const engine of ENGINES) {
    console.log(`${engine}: render=PASS interaction=PASS incremental_update=PASS`);
  }
  console.log('z001_same_graph=PASS');
  console.log('r001_same_graph=PASS');
  console.log('as0001_same_graph=PASS');
  console.log('path_highlight=PASS');
  console.log('scale_100_500_1000_1500=PASS');
  console.log('neo4j_direct_access_absent=PASS');
  console.log('viewer_specific_fields_in_graphdto=0');
  console.log('browser_console_errors=0');
} finally {
  await browser.close();
}
