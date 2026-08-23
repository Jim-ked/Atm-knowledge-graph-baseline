import { chromium } from 'playwright-core';
import { mkdir, writeFile } from 'node:fs/promises';

const BASE = process.env.ATMKG_VIEWER_URL || 'http://127.0.0.1:18080/viewer/';
const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const failures = [];
const consoleErrors = [];
const pageErrors = [];
const results = { sequences: {}, environment: {} };
const require = (condition, message) => { if (!condition) failures.push(message); };

const stripState = snapshot => ({
  schemaVersion: snapshot.schemaVersion,
  nodes: snapshot.nodes,
  relationships: snapshot.relationships,
  meta: snapshot.meta
});

function assertDestroyRecord(record, label) {
  require(record?.phase === 'destroy', `${label}: missing destroy transition`);
  require(record.runtime?.destroyed === true, `${label}: old adapter was not marked destroyed`);
  require(record.runtime?.mounted === false, `${label}: old adapter remained mounted`);
  require(record.runtime?.containerChildCount === 0, `${label}: old adapter did not clear container`);
  const resources = record.runtime?.resources ?? {};
  for (const key of ['graphAlive', 'cyAlive', 'rendererAlive', 'layoutSupervisorAlive',
    'fa2TimerActive', 'drawFrameActive', 'expansionTimerActive', 'dragFrameActive']) {
    require(resources[key] !== true, `${label}: leaked runtime resource ${key}`);
  }
  if (resources.physicsStopped != null) require(resources.physicsStopped === true,
    `${label}: physics was not stopped`);
}

const browser = await chromium.launch({ headless: true, executablePath: CHROME });
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 });
  page.setDefaultTimeout(180_000);
  page.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text()); });
  page.on('pageerror', error => pageErrors.push(error.message));
  const response = await page.goto(`${BASE}?debug=true`, { waitUntil: 'networkidle' });
  require(response?.status() === 200, `viewer HTTP ${response?.status()}`);
  await page.waitForFunction(() => window.__ATMKG_PHASE5__);
  await page.evaluate(() => window.__ATMKG_PHASE5__.loadPreset('r001'));
  const expected = await page.evaluate(() => {
    const snapshot = window.__ATMKG_PHASE5__.model.snapshot();
    return { schemaVersion: snapshot.schemaVersion, nodes: snapshot.nodes,
      relationships: snapshot.relationships, meta: snapshot.meta };
  });

  async function runSequence(name, engines) {
    await page.evaluate(() => window.__ATMKG_PHASE5__.resetRuntimeDiagnostics());
    for (const engine of engines) await page.evaluate(value => window.__ATMKG_PHASE5__.switchEngine(value), engine);
    const observed = await page.evaluate(() => {
      const snapshot = window.__ATMKG_PHASE5__.model.snapshot();
      return {
      snapshot: { schemaVersion: snapshot.schemaVersion, nodes: snapshot.nodes,
        relationships: snapshot.relationships, meta: snapshot.meta },
      runtime: window.__ATMKG_PHASE5__.runtimeDiagnostics(),
      currentEngine: window.__ATMKG_PHASE5__.engine
      };
    });
    results.sequences[name] = observed;
    require(JSON.stringify(observed.snapshot) === JSON.stringify(expected), `${name}: GraphDTO changed`);
    require(observed.currentEngine === engines.at(-1), `${name}: final engine mismatch`);
    const transitions = observed.runtime.transitions;
    const destroys = transitions.filter(item => item.phase === 'destroy');
    require(destroys.length >= engines.length - 1,
      `${name}: expected at least ${engines.length - 1} destroys, got ${destroys.length}`);
    destroys.forEach((record, index) => assertDestroyRecord(record, `${name} destroy#${index + 1}`));
    require(transitions.filter(item => item.phase === 'mount').every(item => item.runtime?.mounted === true),
      `${name}: mount record missing mounted runtime`);
  }

  await runSequence('g6_sigma_g6', ['g6', 'sigma', 'g6']);
  await runSequence('g6_cytoscape_g6', ['g6', 'cytoscape', 'g6']);
  await runSequence('sigma_cytoscape', ['sigma', 'cytoscape']);

  const selectedPath = await page.evaluate(async () => {
    const gate = window.__ATMKG_PHASE5__;
    await gate.loadPath();
    const path = gate.model.snapshot();
    const selected = await gate.selectNodeByKind('RouteNode');
    await gate.switchEngine('cytoscape');
    return {
      selected,
      selectedAfterSwitch: gate.model.snapshot().state.selectedNodeId,
      highlighted: path.state.highlightedRelationshipIds.length,
      topology: [path.nodes.length, path.relationships.length]
    };
  });
  results.selectedPath = selectedPath;
  require(selectedPath.highlighted > 0, 'path semantic IDs were not retained');
  require(selectedPath.selectedAfterSwitch === selectedPath.selected, 'selection semantic ID was not retained');

  require(consoleErrors.length === 0, `console errors: ${consoleErrors.join(' | ')}`);
  require(pageErrors.length === 0, `page errors: ${pageErrors.join(' | ')}`);
  results.environment = { consoleErrors, pageErrors, base: BASE };
  await mkdir('test-results/cytoscape', { recursive: true });
  await writeFile('test-results/cytoscape/runtime-isolation-results.json', JSON.stringify(results, null, 2));
  if (failures.length) throw new Error(failures.join('\n'));
  console.log('VIEWER_RUNTIME_ISOLATION_OK');
  console.log('g6_sigma_g6=PASS');
  console.log('g6_cytoscape_g6=PASS');
  console.log('sigma_cytoscape=PASS');
  console.log('semantic_ids_preserved=PASS');
  console.log('console_errors=0');
} finally {
  await browser.close();
}
