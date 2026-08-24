export const DISPLAY_NODE_LIMITS = Object.freeze([100, 300, 500, 1000]);
export const DEFAULT_DISPLAY_NODE_LIMIT = 300;

function normalizeLimit(value) {
  const limit = Number(value);
  if (!DISPLAY_NODE_LIMITS.includes(limit)) throw new Error(`unsupported display node limit: ${value}`);
  return limit;
}

function clone(value) {
  return structuredClone(value);
}

export function toDisplayGraphDto(graphDto, nodeLimit = DEFAULT_DISPLAY_NODE_LIMIT) {
  if (!graphDto || typeof graphDto !== 'object') throw new Error('GraphDTO must be an object');
  const limit = normalizeLimit(nodeLimit);
  const allNodes = Array.isArray(graphDto.nodes) ? graphDto.nodes : [];
  const allRelationships = Array.isArray(graphDto.relationships) ? graphDto.relationships : [];
  const nodes = allNodes.slice(0, limit);
  const visibleNodeIds = new Set(nodes.map(node => node.id));
  const relationships = allRelationships.filter(relationship =>
    visibleNodeIds.has(relationship.source) && visibleNodeIds.has(relationship.target));
  const visibleRelationshipIds = new Set(relationships.map(relationship => relationship.id));
  const graphDtoForDisplay = {
    schemaVersion: String(graphDto.schemaVersion ?? '1'),
    nodes: clone(nodes),
    relationships: clone(relationships),
    meta: clone(graphDto.meta ?? {})
  };

  if (graphDto.state) {
    graphDtoForDisplay.state = {
      ...clone(graphDto.state),
      selectedNodeId: visibleNodeIds.has(graphDto.state.selectedNodeId)
        ? graphDto.state.selectedNodeId : null,
      expandedNodeIds: (graphDto.state.expandedNodeIds ?? []).filter(id => visibleNodeIds.has(id)),
      highlightedRelationshipIds: (graphDto.state.highlightedRelationshipIds ?? [])
        .filter(id => visibleRelationshipIds.has(id))
    };
  }

  return {
    graphDto: graphDtoForDisplay,
    nodeLimit: limit,
    displayCount: nodes.length,
    totalCount: allNodes.length,
    displayRelationshipCount: relationships.length,
    totalRelationshipCount: allRelationships.length,
    truncated: allNodes.length > limit
  };
}

export function preparePathGraphDto(graphDto, nodeLimit = DEFAULT_DISPLAY_NODE_LIMIT) {
  const display = toDisplayGraphDto(graphDto, nodeLimit);
  if (display.truncated) {
    return { ...display, accepted: false, graphDto: null, displayCount: 0, displayRelationshipCount: 0 };
  }
  return { ...display, accepted: true };
}

export function expansionDisplayDelta(beforeGraphDto, afterGraphDto,
                                      nodeLimit = DEFAULT_DISPLAY_NODE_LIMIT) {
  const before = toDisplayGraphDto(beforeGraphDto, nodeLimit);
  const after = toDisplayGraphDto(afterGraphDto, nodeLimit);
  const beforeNodeIds = new Set(before.graphDto.nodes.map(node => node.id));
  const beforeRelationshipIds = new Set(before.graphDto.relationships.map(relationship => relationship.id));
  return {
    ...after,
    beforeDisplayCount: before.displayCount,
    graphDto: {
      schemaVersion: after.graphDto.schemaVersion,
      nodes: after.graphDto.nodes.filter(node => !beforeNodeIds.has(node.id)),
      relationships: after.graphDto.relationships
        .filter(relationship => !beforeRelationshipIds.has(relationship.id)),
      meta: after.graphDto.meta
    }
  };
}
