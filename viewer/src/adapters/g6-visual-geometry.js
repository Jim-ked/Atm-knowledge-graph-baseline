export const G6_EDGE_GEOMETRY = Object.freeze({
  type: 'quadratic',
  labelAutoRotate: true,
  labelIsBillboard: true,
  endArrowSize: 5,
  endArrowType: 'vee',
  loopType: 'arc'
});

const characters = value => Array.from(String(value ?? ''));

function shorten(value, limit) {
  const text = characters(value);
  if (text.length <= limit) return text.join('');
  if (limit <= 1) return '…';
  const head = Math.ceil((limit - 1) / 2);
  const tail = limit - 1 - head;
  return `${text.slice(0, head).join('')}…${text.slice(-tail).join('')}`;
}

export function uniqueShortCaptions(nodes, limit = 7) {
  const baseById = new Map(nodes.map(node => [node.id, shorten(node.caption, limit)]));
  const counts = new Map();
  for (const caption of baseById.values()) counts.set(caption, (counts.get(caption) ?? 0) + 1);
  const used = new Set();
  const result = new Map();
  for (const node of [...nodes].sort((left, right) => left.id.localeCompare(right.id))) {
    const base = baseById.get(node.id);
    if (counts.get(base) === 1 && !used.has(base)) {
      result.set(node.id, base);
      used.add(base);
      continue;
    }
    let sequence = 1;
    let candidate;
    do {
      const suffix = `·${sequence}`;
      candidate = `${shorten(node.caption, Math.max(1, limit - characters(suffix).length))}${suffix}`;
      sequence += 1;
    } while (used.has(candidate));
    result.set(node.id, candidate);
    used.add(candidate);
  }
  return result;
}

export function relationshipCaptionForLength(value, edgeLength, fontSize = 7.5) {
  const length = Number(edgeLength);
  if (!Number.isFinite(length) || length < 50) return '';
  const text = characters(value);
  const characterWidth = Math.max(4, Number(fontSize) * 0.62);
  const available = Math.max(0, length - 24);
  if (text.length * characterWidth <= available) return text.join('');
  const limit = Math.floor(available / characterWidth);
  return limit >= 4 ? shorten(value, limit) : '';
}

export function annotateRelationshipGeometry(relationships) {
  const groups = new Map();
  for (const relationship of relationships) {
    if (relationship.source === relationship.target) continue;
    const endpoints = [relationship.source, relationship.target].sort();
    const key = JSON.stringify(endpoints);
    const group = groups.get(key) ?? [];
    group.push(relationship);
    groups.set(key, group);
  }
  return relationships.map(relationship => {
    if (relationship.source === relationship.target) {
      return { ...relationship, geometryKind: 'self-loop' };
    }
    const group = groups.get(JSON.stringify([relationship.source, relationship.target].sort()));
    if (group.length === 1) return { ...relationship, geometryKind: 'single' };
    const hasReverse = group.some(candidate =>
      candidate.source === relationship.target && candidate.target === relationship.source);
    return { ...relationship, geometryKind: hasReverse ? 'bidirectional' : 'parallel' };
  });
}
