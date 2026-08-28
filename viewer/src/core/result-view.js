const clone = value => structuredClone(value ?? {});
const compact = value => typeof value === 'object' && value !== null ? JSON.stringify(value) : String(value ?? '');

export class ResultState {
  constructor(response = null) { this.set(response); }
  set(response) {
    this.raw = clone(response);
    const graph = response?.nodes && response?.relationships ? response : response?.graph;
    this.graph = graph?.nodes && graph?.relationships ? clone(graph) : { schemaVersion: '1', nodes: [], relationships: [], meta: {} };
    this.rows = Array.isArray(response?.rows) ? clone(response.rows) : [];
    this.columns = Array.isArray(response?.columns) ? [...response.columns] : [];
    this.filters = { classIri: '', relationshipType: '' };
    this.view = this.defaultView();
    return this;
  }
  defaultView() { return this.graph.nodes.length || this.graph.relationships.length ? 'graph' : this.rows.length ? 'table' : 'graph'; }
  filteredGraph() {
    const { classIri, relationshipType } = this.filters;
    const nodes = this.graph.nodes.filter(node => !classIri || node.kind === classIri || node.labels?.includes(classIri));
    const ids = new Set(nodes.map(node => node.id));
    const relationships = this.graph.relationships.filter(rel => ids.has(rel.source) && ids.has(rel.target)
      && (!relationshipType || rel.type === relationshipType));
    return { ...clone(this.graph), nodes, relationships };
  }
  graphTableRows(graph = this.graph) {
    const nodeRows = graph.nodes.map(node => ({ type: '实体', identifier: node.caption ?? node.id,
      category: node.kind ?? node.labels?.[0] ?? '', source: '', target: '', properties: compact(node.properties) }));
    const byId = new Map(graph.nodes.map(node => [node.id, node.caption ?? node.id]));
    const relRows = graph.relationships.map(rel => ({ type: '关系', identifier: rel.type,
      category: '', source: byId.get(rel.source) ?? rel.source, target: byId.get(rel.target) ?? rel.target,
      properties: compact(rel.properties) }));
    return [...nodeRows, ...relRows];
  }
  tableRows() { return this.rows.length ? this.rows : this.graphTableRows(this.filteredGraph()); }
  tableColumns() { return this.columns.length ? this.columns : ['type', 'identifier', 'category', 'source', 'target', 'properties']; }
}

/** Small facade for callers that want state plus a selected presentation. */
export class ResultView extends ResultState {
  setView(view) {
    if (!['graph', 'table', 'raw'].includes(view)) throw new Error(`unknown result view: ${view}`);
    this.view = view;
    return this.view;
  }
}

export function renderTable(container, state, catalog = null) {
  const rows = state.tableRows();
  const columns = state.tableColumns();
  const table = document.createElement('table'); table.className = 'result-table';
  const head = document.createElement('thead'); const tr = document.createElement('tr');
  columns.forEach(column => { const th = document.createElement('th'); th.textContent = column; tr.append(th); }); head.append(tr); table.append(head);
  const body = document.createElement('tbody');
  rows.forEach(row => { const line = document.createElement('tr'); columns.forEach(column => { const td = document.createElement('td'); const value = row?.[column]; td.textContent = compact(value); line.append(td); }); body.append(line); });
  table.append(body); container.replaceChildren(table);
  return table;
}

export function renderRaw(container, state) { const pre = document.createElement('pre'); pre.className = 'raw-result'; pre.textContent = JSON.stringify(state.raw, null, 2); container.replaceChildren(pre); return pre; }
