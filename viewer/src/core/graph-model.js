const clone = value => structuredClone(value);

function requireId(value, label) {
  if (typeof value !== 'string' || value.length === 0) throw new Error(`${label} must be a non-empty string`);
  return value;
}

function normalizeNode(node) {
  if (!node || typeof node !== 'object') throw new Error('node must be an object');
  return {
    id: requireId(node.id, 'node id'),
    labels: Array.isArray(node.labels) ? [...node.labels] : [],
    kind: node.kind ?? null,
    caption: node.caption ?? node.id,
    properties: clone(node.properties ?? {})
  };
}

function normalizeRelationship(relationship) {
  if (!relationship || typeof relationship !== 'object') throw new Error('relationship must be an object');
  return {
    id: requireId(relationship.id, 'relationship id'),
    source: requireId(relationship.source, 'relationship source'),
    target: requireId(relationship.target, 'relationship target'),
    type: relationship.type ?? '',
    properties: clone(relationship.properties ?? {})
  };
}

export class GraphModel {
  #schemaVersion = '1';
  #nodes = new Map();
  #relationships = new Map();
  #meta = {};
  #selectedNodeId = null;
  #expandedNodeIds = new Set();
  #highlightedRelationshipIds = new Set();
  #listeners = new Set();

  constructor(graphDto) {
    if (graphDto) this.replace(graphDto);
  }

  replace(graphDto) {
    const normalized = this.#normalize(graphDto);
    this.#schemaVersion = normalized.schemaVersion;
    this.#nodes = normalized.nodes;
    this.#relationships = normalized.relationships;
    this.#meta = normalized.meta;
    if (!this.#nodes.has(this.#selectedNodeId)) this.#selectedNodeId = null;
    this.#expandedNodeIds = new Set([...this.#expandedNodeIds].filter(id => this.#nodes.has(id)));
    this.#highlightedRelationshipIds = new Set(
      [...this.#highlightedRelationshipIds].filter(id => this.#relationships.has(id))
    );
    this.#emit('replace');
  }

  merge(graphDto) {
    const current = this.snapshot();
    const incomingNodes = new Map(current.nodes.map(node => [node.id, node]));
    for (const node of graphDto.nodes ?? []) incomingNodes.set(node.id, node);
    const incomingRelationships = new Map(current.relationships.map(rel => [rel.id, rel]));
    for (const relationship of graphDto.relationships ?? []) incomingRelationships.set(relationship.id, relationship);
    this.replace({
      schemaVersion: graphDto.schemaVersion ?? current.schemaVersion,
      nodes: [...incomingNodes.values()],
      relationships: [...incomingRelationships.values()],
      meta: { ...current.meta, ...(graphDto.meta ?? {}) }
    });
  }

  applyPatch(patch = {}) {
    const nodes = new Map(this.#nodes);
    const relationships = new Map(this.#relationships);
    for (const node of patch.upsertNodes ?? []) {
      const normalized = normalizeNode(node);
      nodes.set(normalized.id, normalized);
    }
    const removedNodes = new Set(patch.removeNodeIds ?? []);
    for (const id of removedNodes) nodes.delete(id);
    for (const relationship of patch.upsertRelationships ?? []) {
      const normalized = normalizeRelationship(relationship);
      relationships.set(normalized.id, normalized);
    }
    for (const id of patch.removeRelationshipIds ?? []) relationships.delete(id);
    for (const [id, relationship] of relationships) {
      if (removedNodes.has(relationship.source) || removedNodes.has(relationship.target)) relationships.delete(id);
    }
    this.replace({
      schemaVersion: this.#schemaVersion,
      nodes: [...nodes.values()],
      relationships: [...relationships.values()],
      meta: this.#meta
    });
  }

  select(nodeId) {
    if (nodeId !== null && !this.#nodes.has(nodeId)) throw new Error(`unknown node id: ${nodeId}`);
    this.#selectedNodeId = nodeId;
    this.#emit('selection');
  }

  markExpanded(nodeId) {
    if (!this.#nodes.has(nodeId)) throw new Error(`unknown node id: ${nodeId}`);
    this.#expandedNodeIds.add(nodeId);
    this.#emit('expanded');
  }

  highlightPath(relationshipIds = []) {
    this.#highlightedRelationshipIds = new Set(
      relationshipIds.filter(id => this.#relationships.has(id))
    );
    this.#emit('highlight');
  }

  snapshot() {
    return clone({
      schemaVersion: this.#schemaVersion,
      nodes: [...this.#nodes.values()],
      relationships: [...this.#relationships.values()],
      meta: this.#meta,
      state: {
        selectedNodeId: this.#selectedNodeId,
        expandedNodeIds: [...this.#expandedNodeIds],
        highlightedRelationshipIds: [...this.#highlightedRelationshipIds]
      }
    });
  }

  subscribe(listener) {
    this.#listeners.add(listener);
    return () => this.#listeners.delete(listener);
  }

  #normalize(graphDto) {
    if (!graphDto || typeof graphDto !== 'object') throw new Error('GraphDTO must be an object');
    const nodes = new Map();
    for (const raw of graphDto.nodes ?? []) {
      const node = normalizeNode(raw);
      if (nodes.has(node.id)) throw new Error(`duplicate node id: ${node.id}`);
      nodes.set(node.id, node);
    }
    const relationships = new Map();
    for (const raw of graphDto.relationships ?? []) {
      const relationship = normalizeRelationship(raw);
      if (relationships.has(relationship.id)) throw new Error(`duplicate relationship id: ${relationship.id}`);
      if (!nodes.has(relationship.source) || !nodes.has(relationship.target)) {
        throw new Error(`relationship ${relationship.id} has missing endpoint`);
      }
      relationships.set(relationship.id, relationship);
    }
    return {
      schemaVersion: String(graphDto.schemaVersion ?? '1'),
      nodes,
      relationships,
      meta: clone(graphDto.meta ?? {})
    };
  }

  #emit(reason) {
    const snapshot = this.snapshot();
    for (const listener of this.#listeners) listener(snapshot, reason);
  }
}
