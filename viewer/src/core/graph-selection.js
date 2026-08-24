export function buildGraphElementStates({
  nodes = [],
  relationships = [],
  selectedNodeId = null,
  selectedRelationshipId = null,
  highlightedRelationshipIds = []
}) {
  const highlighted = new Set(highlightedRelationshipIds);
  const pathMode = highlighted.size > 0;
  const neighbors = new Set();
  const pathNodes = new Set();

  for (const relationship of relationships) {
    if (relationship.source === selectedNodeId) neighbors.add(relationship.target);
    if (relationship.target === selectedNodeId) neighbors.add(relationship.source);
    if (highlighted.has(relationship.id)) {
      pathNodes.add(relationship.source);
      pathNodes.add(relationship.target);
    }
  }

  const states = {};
  for (const node of nodes) {
    states[node.id] = node.id === selectedNodeId ? ['selected']
      : pathMode ? (pathNodes.has(node.id) ? ['path'] : ['inactive'])
        : selectedNodeId && neighbors.has(node.id) ? ['neighbor'] : [];
  }
  for (const relationship of relationships) {
    const relationshipStates = [];
    if (highlighted.has(relationship.id)) relationshipStates.push('highlighted');
    if (relationship.id === selectedRelationshipId) relationshipStates.push('selected');
    if (!pathMode && selectedNodeId
      && (relationship.source === selectedNodeId || relationship.target === selectedNodeId)) {
      relationshipStates.push('related');
    } else if (pathMode && relationshipStates.length === 0) {
      relationshipStates.push('inactive');
    }
    states[relationship.id] = relationshipStates;
  }
  return states;
}
