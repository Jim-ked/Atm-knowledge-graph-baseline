import { chromium } from 'playwright-core';
import { mkdir, writeFile } from 'node:fs/promises';

const BASE = process.env.ATMKG_VIEWER_URL || 'http://127.0.0.1:18080/viewer/';
const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const output = 'test-results/g6-visual-geometry';
const consoleErrors = [];
const pageErrors = [];
const observations = {};

const browser = await chromium.launch({ headless: true, executablePath: CHROME });
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 });
  page.setDefaultTimeout(120_000);
  page.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text()); });
  page.on('pageerror', error => pageErrors.push(error.message));
  await mkdir(output, { recursive: true });
  await page.goto(BASE, { waitUntil: 'networkidle' });
  await page.waitForFunction(() => window.__ATMKG_PHASE5__);

  async function record(name) {
    observations[name] = await page.evaluate(() => {
      const gate = window.__ATMKG_PHASE5__;
      const graph = gate.adapter.graph;
      return {
        modelNodes: gate.model.snapshot().nodes.length,
        modelRelationships: gate.model.snapshot().relationships.length,
        renderedNodes: graph.getNodeData().length,
        renderedRelationships: graph.getEdgeData().length,
        visualConfig: gate.adapter.diagnostics().config
      };
    });
    await page.screenshot({ path: `${output}/${name}.png`, fullPage: true });
  }

  await page.evaluate(() => window.__ATMKG_PHASE5__.loadPreset('r001'));
  observations.caption = await page.evaluate(async () => {
    const graph = window.__ATMKG_PHASE5__.adapter.graph;
    const node = graph.getNodeData().find(item => item.data.caption !== item.data.displayCaption)
      ?? graph.getNodeData()[0];
    const short = node.data.displayCaption;
    const uniqueCount = new Set(graph.getNodeData().map(item => item.data.displayCaption)).size;
    graph.emit('node:pointerover', { target: { id: node.id } });
    await new Promise(resolve => setTimeout(resolve, 50));
    const hover = graph.getElementRenderStyle(node.id).labelText;
    graph.emit('node:pointerleave', { target: { id: node.id } });
    await new Promise(resolve => setTimeout(resolve, 50));
    return {
      nodeCount: graph.getNodeData().length,
      uniqueCount,
      short,
      hover,
      full: node.data.caption
    };
  });
  if (observations.caption.uniqueCount !== observations.caption.nodeCount ||
      observations.caption.hover !== observations.caption.full) {
    throw new Error('node caption uniqueness or hover caption failed');
  }
  await record('r001-ordinary');

  await page.evaluate(() => window.__ATMKG_PHASE5__.loadPath());
  await record('r001-path');

  await page.evaluate(() => window.__ATMKG_PHASE5__.loadPreset('r001'));
  const routeId = await page.evaluate(() => window.__ATMKG_PHASE5__.selectNodeByKind('Route'));
  const box = await page.locator('#graph').boundingBox();
  const point = await page.evaluate(id => {
    const graph = window.__ATMKG_PHASE5__.adapter.graph;
    return graph.getViewportByCanvas(graph.getElementPosition(id));
  }, routeId);
  await page.mouse.move(box.x + point[0], box.y + point[1]);
  await page.mouse.down();
  for (let step = 1; step <= 8; step += 1) {
    await page.mouse.move(box.x + point[0] + step * 11, box.y + point[1] + step * 5);
    await page.waitForTimeout(50);
  }
  await page.mouse.up();
  await page.waitForTimeout(300);
  await record('r001-after-drag');

  await page.evaluate(() => window.__ATMKG_PHASE5__.loadPreset('r001'));
  await page.evaluate(() => window.__ATMKG_PHASE5__.selectNodeByKind('NavigationAid'));
  await page.evaluate(() => window.__ATMKG_PHASE5__.expandSelected());
  await record('r001-after-expand');

  await page.evaluate(() => window.__ATMKG_PHASE5__.loadPreset('r001'));
  await page.evaluate(() => window.__ATMKG_PHASE5__.addG6GeometryDemo());
  observations.geometryDemo = await page.evaluate(() => {
    const graph = window.__ATMKG_PHASE5__.adapter.graph;
    return ['g6-demo-parallel-1', 'g6-demo-parallel-2', 'g6-demo-reverse', 'g6-demo-loop']
      .map(id => {
        const edge = graph.getEdgeData(id);
        const style = graph.getElementRenderStyle(id);
        return {
          id,
          type: edge.type,
          geometryKind: edge.data.geometryKind,
          curveOffset: style.curveOffset ?? null,
          loopPlacement: style.loopPlacement ?? null,
          loopDist: style.loopDist ?? null,
          endArrowSize: style.endArrowSize,
          labelAutoRotate: style.labelAutoRotate
        };
      });
  });
  await record('r001-parallel-bidirectional-loop');

  observations.environment = { consoleErrors, pageErrors, viewerUrl: BASE };
  await writeFile(`${output}/results.json`, JSON.stringify(observations, null, 2));
  if (consoleErrors.length || pageErrors.length) {
    throw new Error([...consoleErrors, ...pageErrors].join('\n'));
  }
  console.log('G6_VISUAL_GEOMETRY_POC_READY');
  console.log('screenshots=5');
  console.log('console_errors=0');
} finally {
  await browser.close();
}
