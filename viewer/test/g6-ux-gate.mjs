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
const results = { initial: {}, expansions: {}, environment: {} };
const require = (condition, message) => { if (!condition) failures.push(message); };

function sameIds(left, right) {
  const sortedLeft = [...left].sort();
  const sortedRight = [...right].sort();
  return sortedLeft.length === sortedRight.length && sortedLeft.every((id, index) => id === sortedRight[index]);
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

  async function observeTopologyAndOverlap() {
    return page.evaluate(() => {
      const gate = window.__ATMKG_PHASE5__;
      const graph = gate.adapter.graph;
      const model = gate.model.snapshot();
      const renderNodes = graph.getNodeData();
      const renderRelationships = graph.getEdgeData();
      const visual = renderNodes.map(node => {
        const [x, y] = graph.getElementPosition(node.id);
        const size = Number(graph.getElementRenderStyle(node.id).size ?? gate.adapter.config.nodeSize);
        return { id: node.id, x, y, size };
      });
      let minimumGap = Infinity;
      for (let i = 0; i < visual.length; i += 1) {
        for (let j = i + 1; j < visual.length; j += 1) {
          const distance = Math.hypot(visual[i].x - visual[j].x, visual[i].y - visual[j].y);
          minimumGap = Math.min(minimumGap, distance - (visual[i].size + visual[j].size) / 2);
        }
      }
      return {
        modelNodeIds: model.nodes.map(node => node.id),
        modelRelationshipIds: model.relationships.map(relationship => relationship.id),
        renderNodeIds: renderNodes.map(node => node.id),
        renderRelationshipIds: renderRelationships.map(relationship => relationship.id),
        minimumGap: Number.isFinite(minimumGap) ? minimumGap : null
      };
    });
  }

  for (const [name, expected] of Object.entries(EXPECTED)) {
    await page.evaluate(preset => window.__ATMKG_PHASE5__.loadPreset(preset), name);
    const observed = await observeTopologyAndOverlap();
    results.initial[name] = observed;
    require(observed.modelNodeIds.length === expected.nodes, `${name}: initial node count mismatch`);
    require(observed.modelRelationshipIds.length === expected.relationships, `${name}: initial relationship count mismatch`);
    require(sameIds(observed.modelNodeIds, observed.renderNodeIds), `${name}: rendered node topology changed`);
    require(sameIds(observed.modelRelationshipIds, observed.renderRelationshipIds),
      `${name}: rendered relationship topology changed`);
    require(observed.minimumGap === null || observed.minimumGap >= -0.5,
      `${name}: hard node overlap gap=${observed.minimumGap}`);
  }

  async function expand(name, kind, expectedAddedNodes, expectedAddedRelationships) {
    await page.evaluate(preset => window.__ATMKG_PHASE5__.loadPreset(preset), name);
    await page.evaluate(targetKind => window.__ATMKG_PHASE5__.selectNodeByKind(targetKind), kind);
    await page.evaluate(() => window.__ATMKG_PHASE5__.expandSelected());
    const observed = await observeTopologyAndOverlap();
    const expansion = await page.evaluate(() => window.__ATMKG_PHASE5__.adapter.diagnostics().lastExpansion);
    results.expansions[name] = { ...observed, expansion };
    require(expansion.addedNodes === expectedAddedNodes, `${name}: expanded node count mismatch`);
    require(expansion.addedEdges === expectedAddedRelationships, `${name}: expanded relationship count mismatch`);
    require(expansion.anchorPinned === true, `${name}: expansion anchor was not fixed`);
    require(expansion.anchorDisplacement === 0, `${name}: expansion anchor drift=${expansion.anchorDisplacement}`);
    require(sameIds(observed.modelNodeIds, observed.renderNodeIds), `${name}: expanded node topology changed`);
    require(sameIds(observed.modelRelationshipIds, observed.renderRelationshipIds),
      `${name}: expanded relationship topology changed`);
    require(observed.minimumGap === null || observed.minimumGap >= -0.5,
      `${name}: expanded graph has hard overlap gap=${observed.minimumGap}`);
  }

  await expand('z001', 'Runway', 2, 2);
  await expand('r001', 'NavigationAid', 2, 2);

  require(consoleErrors.length === 0, `console errors: ${consoleErrors.join(' | ')}`);
  require(pageErrors.length === 0, `page errors: ${pageErrors.join(' | ')}`);
  results.environment = {
    consoleErrors,
    pageErrors,
    viewerUrl: BASE,
    userAgent: await page.evaluate(() => navigator.userAgent)
  };
  await mkdir('test-results/g6-ux', { recursive: true });
  await writeFile('test-results/g6-ux/results.json', JSON.stringify(results, null, 2));
  if (failures.length) throw new Error(failures.join('\n'));

  console.log('G6_UX_TECHNICAL_OK');
  console.log('topology_unchanged=PASS');
  console.log('expansion_anchor_drift=0');
  console.log('hard_node_overlap=NONE');
  console.log('expansion_additions=PASS');
  console.log('console_errors=0');
} finally {
  await browser.close();
}
