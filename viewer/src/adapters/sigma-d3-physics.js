import {
  forceCollide,
  forceLink,
  forceManyBody,
  forceSimulation,
  forceX,
  forceY
} from 'd3-force';

export const SIGMA_LAYOUT_UNITS_PER_SIZE = 2;
export const SIGMA_COLLISION_PADDING = 10;
export const SIGMA_LINK_BASE_GAP = 72;

const GOLDEN_ANGLE = Math.PI * (3 - Math.sqrt(5));
const PLACEMENT_SECTORS = 32;
const AXIS_STRENGTH = 0.025;
const MANY_BODY_STRENGTH = -100;
const FULL_TICKS = 180;
const INCREMENTAL_TICKS = 72;

const rounded = value => Math.round(value * 1_000_000) / 1_000_000;

function pairKey(source, target) {
  return source < target ? `${source}\u0000${target}` : `${target}\u0000${source}`;
}

function percentile(values, fraction) {
  if (values.length === 0) return null;
  const sorted = [...values].sort((left, right) => left - right);
  return sorted[Math.max(0, Math.ceil(sorted.length * fraction) - 1)];
}

function summary(values) {
  if (values.length === 0) return { count: 0, min: null, p50: null, p90: null, max: null };
  return {
    count: values.length,
    min: rounded(Math.min(...values)),
    p50: rounded(percentile(values, 0.5)),
    p90: rounded(percentile(values, 0.9)),
    max: rounded(Math.max(...values))
  };
}

function distribution(values) {
  const result = {};
  for (const value of values) {
    const key = String(rounded(value));
    result[key] = (result[key] ?? 0) + 1;
  }
  return result;
}

function finite(value, fallback) {
  const number = Number(value);
  return Number.isFinite(number) ? number : fallback;
}

function nullableFinite(value) {
  return value == null ? null : finite(value, null);
}

export function sigmaLayoutRadius(visualSize) {
  return Math.max(1, finite(visualSize, 1)) * SIGMA_LAYOUT_UNITS_PER_SIZE;
}

export class SigmaD3Physics {
  nodeById = new Map();
  activeNodes = [];
  physicsLinks = [];
  creationCount = 0;
  stopped = true;
  lastMode = 'KEEP';
  lastReconcile = {
    createdNodes: 0,
    reusedNodes: 0,
    initialCollisionViolations: 0,
    newLinkInitialDistanceRatio: summary([]),
    oldNodeMeanDisplacement: 0,
    oldNodeMaxDisplacement: 0
  };

  constructor({ width = 1000, height = 700 } = {}) {
    this.width = finite(width, 1000);
    this.height = finite(height, 700);
    this.linkForce = forceLink([])
      .id(node => node.id)
      .distance(link => this.preferredDistance(link.source, link.target))
      .iterations(2);
    // Native d3-force strength and bias are intentionally retained.
    // Source: https://d3js.org/d3-force/link
    this.simulation = forceSimulation([]).stop()
      .velocityDecay(0.35)
      .alphaDecay(0.045)
      .force('link', this.linkForce)
      .force('charge', forceManyBody().strength(MANY_BODY_STRENGTH).distanceMin(18).distanceMax(1000))
      // Collision radius is in the same layout coordinate system as x/y.
      // Source: https://d3js.org/d3-force/collide
      .force('collide', forceCollide(node => this.collisionRadius(node)).strength(0.95).iterations(3))
      .force('x', forceX(this.width / 2).strength(AXIS_STRENGTH))
      .force('y', forceY(this.height / 2).strength(AXIS_STRENGTH));
    this.creationCount += 1;
  }

  reconcile(graph, { mode = 'FULL_QUERY', anchorId = null } = {}) {
    // A full query is a new physics workspace. Do not let a prior drag/expand
    // leak node objects, velocities, or pin state into the new snapshot.
    if (mode === 'FULL_QUERY') {
      this.nodeById.clear();
      this.activeNodes = [];
      this.physicsLinks = [];
      this.linkForce.links([]);
      this.simulation.nodes([]);
    }
    const existingPositions = new Map(this.activeNodes.map(node => [node.id, { x: node.x, y: node.y }]));
    const previousNodes = new Set(this.activeNodes.map(node => node.id));
    const occupied = [];
    const anchor = this.nodeById.get(anchorId);
    const graphNodes = graph.nodes();
    let createdNodes = 0;
    let reusedNodes = 0;

    this.activeNodes = graphNodes.map((id, index) => {
      const attributes = graph.getNodeAttributes(id);
      const layoutRadius = sigmaLayoutRadius(attributes.size);
      const existing = this.nodeById.get(id);
      if (existing && mode !== 'FULL_QUERY') {
        existing.visualSize = finite(attributes.size, 1);
        existing.layoutRadius = layoutRadius;
        if (attributes.fixed === true) {
          existing.fx = existing.x;
          existing.fy = existing.y;
        }
        occupied.push(existing);
        reusedNodes += 1;
        return existing;
      }

      const node = existing ?? { id };
      node.visualSize = finite(attributes.size, 1);
      node.layoutRadius = layoutRadius;
      node.vx = 0;
      node.vy = 0;
      node.fx = attributes.fixed === true ? finite(attributes.x, null) : null;
      node.fy = attributes.fixed === true ? finite(attributes.y, null) : null;
      const position = mode === 'INCREMENTAL_EXPAND' && anchor
        ? this.#placeNearAnchor(node, anchor, occupied, createdNodes)
        : this.#placeFull(node, occupied, index, graphNodes.length);
      node.x = position.x;
      node.y = position.y;
      if (node.fx != null && node.fy != null) { node.fx = node.x; node.fy = node.y; }
      this.nodeById.set(id, node);
      occupied.push(node);
      if (!previousNodes.has(id)) createdNodes += 1;
      else reusedNodes += 1;
      return node;
    });

    const activeIds = new Set(graphNodes);
    const pairs = new Set();
    this.physicsLinks = [];
    graph.forEachEdge((edge, attributes, source, target) => {
      if (source === target || !activeIds.has(source) || !activeIds.has(target)) return;
      const key = pairKey(source, target);
      if (pairs.has(key)) return;
      pairs.add(key);
      this.physicsLinks.push({ key, source, target });
    });
    this.simulation.nodes(this.activeNodes);
    this.linkForce.links(this.physicsLinks);

    const newIds = new Set(this.activeNodes.filter(node => !previousNodes.has(node.id)).map(node => node.id));
    const newRatios = this.physicsLinks
      .filter(link => newIds.has(link.source.id) || newIds.has(link.target.id))
      .map(link => this.#edgeLength(link) / this.preferredDistance(link.source, link.target));
    this.lastMode = mode;
    this.lastReconcile = {
      createdNodes,
      reusedNodes,
      initialCollisionViolations: this.#collisionViolations(mode === 'FULL_QUERY' ? activeIds : newIds),
      newLinkInitialDistanceRatio: summary(newRatios),
      oldNodeMeanDisplacement: 0,
      oldNodeMaxDisplacement: 0
    };
    this.beforeSettlePositions = existingPositions;
    return structuredClone(this.lastReconcile);
  }

  settle(iterations = null, alpha = null) {
    const incremental = this.lastMode === 'INCREMENTAL_EXPAND';
    const count = iterations ?? (incremental ? INCREMENTAL_TICKS : FULL_TICKS);
    const heat = alpha ?? (incremental ? 0.3 : 1);
    // Static finite ticks follow d3-force simulation.nodes/tick semantics.
    // Source: https://d3js.org/d3-force/simulation
    this.simulation.alphaTarget(0).alpha(heat).restart().stop().tick(count);
    this.stopped = true;
    const displacements = this.activeNodes.flatMap(node => {
      const before = this.beforeSettlePositions?.get(node.id);
      return before ? [Math.hypot(node.x - before.x, node.y - before.y)] : [];
    });
    this.lastReconcile.oldNodeMeanDisplacement = displacements.length
      ? displacements.reduce((sum, value) => sum + value, 0) / displacements.length : 0;
    this.lastReconcile.oldNodeMaxDisplacement = displacements.length ? Math.max(...displacements) : 0;
    return count;
  }

  writeGraphPositions(graph) {
    graph.updateEachNodeAttributes((id, attributes) => {
      const node = this.nodeById.get(id);
      if (!node) return attributes;
      return {
        ...attributes,
        x: node.x,
        y: node.y,
        fixed: node.fx != null && node.fy != null
      };
    }, { attributes: ['x', 'y', 'fixed'] });
  }

  pin(id, x, y) {
    const node = this.#requiredNode(id);
    node.x = finite(x, node.x);
    node.y = finite(y, node.y);
    node.fx = node.x;
    node.fy = node.y;
  }

  unpin(id) {
    const node = this.#requiredNode(id);
    node.fx = null;
    node.fy = null;
  }

  updatePosition(id, x, y, { fixed = false } = {}) {
    const node = this.#requiredNode(id);
    node.x = finite(x, node.x);
    node.y = finite(y, node.y);
    node.vx = 0;
    node.vy = 0;
    if (fixed) { node.fx = node.x; node.fy = node.y; }
  }

  getNode(id) {
    return this.nodeById.get(id);
  }

  coordinateSnapshot() {
    return this.activeNodes.map(node => ({
      id: node.id,
      x: finite(node.x, null),
      y: finite(node.y, null),
      vx: finite(node.vx, null),
      vy: finite(node.vy, null),
      fx: nullableFinite(node.fx),
      fy: nullableFinite(node.fy),
      fixed: node.fx != null && node.fy != null
    }));
  }

  preferredDistance(source, target) {
    return source.layoutRadius + target.layoutRadius + SIGMA_LINK_BASE_GAP;
  }

  collisionRadius(node) {
    return node.layoutRadius + SIGMA_COLLISION_PADDING;
  }

  diagnostics(graph) {
    const degrees = new Map(this.activeNodes.map(node => [node.id, 0]));
    for (const link of this.physicsLinks) {
      degrees.set(link.source.id, (degrees.get(link.source.id) ?? 0) + 1);
      degrees.set(link.target.id, (degrees.get(link.target.id) ?? 0) + 1);
    }
    const degreeValues = [...degrees.values()];
    const strength = this.linkForce.strength();
    const strengths = this.physicsLinks.map((link, index, links) => strength(link, index, links));
    const visualSizes = this.activeNodes.map(node => node.visualSize);
    const layoutRadii = this.activeNodes.map(node => node.layoutRadius);
    const collisionRadii = this.activeNodes.map(node => this.collisionRadius(node));
    const settledRatios = this.physicsLinks.map(link =>
      this.#edgeLength(link) / this.preferredDistance(link.source, link.target));
    return {
      provider: 'native-d3-force',
      mode: this.lastMode,
      simulationCreationCount: this.creationCount,
      physicsLinkCount: this.physicsLinks.length,
      displayRelationshipCount: graph?.size ?? 0,
      physicalDegreeDistribution: distribution(degreeValues),
      linkStrength: { ...summary(strengths), uniqueCount: new Set(strengths.map(rounded)).size, distribution: distribution(strengths) },
      geometry: {
        visualSize: summary(visualSizes),
        layoutRadius: summary(layoutRadii),
        collisionRadius: summary(collisionRadii),
        layoutUnitsPerVisualSize: SIGMA_LAYOUT_UNITS_PER_SIZE,
        collisionPadding: SIGMA_COLLISION_PADDING,
        linkBaseGap: SIGMA_LINK_BASE_GAP
      },
      settledEdgeLengthRatio: summary(settledRatios),
      fixedNodeCount: this.activeNodes.filter(node => node.fx != null && node.fy != null).length,
      lastReconcile: structuredClone(this.lastReconcile)
    };
  }

  stop() {
    this.simulation.stop();
    this.stopped = true;
  }

  reset() {
    this.simulation.stop();
    this.stopped = true;
    this.linkForce.links([]);
    this.simulation.nodes([]);
    this.nodeById.clear();
    this.activeNodes = [];
    this.physicsLinks = [];
    this.beforeSettlePositions = new Map();
    this.lastMode = 'KEEP';
  }

  #placeFull(node, occupied, index, count) {
    const minimum = this.collisionRadius(node) * 2;
    for (let attempt = 0; attempt < 256; attempt += 1) {
      const ordinal = index + attempt;
      const radius = 70 + Math.sqrt(ordinal + 1) * Math.max(42, minimum);
      const angle = ordinal * GOLDEN_ANGLE;
      const candidate = {
        x: this.width / 2 + Math.cos(angle) * radius,
        y: this.height / 2 + Math.sin(angle) * radius
      };
      if (!this.#collides(candidate, node, occupied)) return candidate;
    }
    return { x: this.width / 2 + index * minimum, y: this.height / 2 + count * minimum * 0.1 };
  }

  #placeNearAnchor(node, anchor, occupied, ordinal) {
    const preferred = this.preferredDistance(anchor, node);
    const minimum = this.collisionRadius(anchor) + this.collisionRadius(node);
    const baseRadius = Math.max(preferred, minimum);
    const baseAngle = this.#emptiestAngle(anchor, baseRadius, occupied);
    for (let ring = 0; ring < 8; ring += 1) {
      const radius = baseRadius + ring * Math.max(this.collisionRadius(node) * 2, preferred * 0.45);
      for (let step = 0; step < PLACEMENT_SECTORS; step += 1) {
        const offset = (step + ordinal * 7) % PLACEMENT_SECTORS;
        const angle = baseAngle + offset * Math.PI * 2 / PLACEMENT_SECTORS;
        const candidate = { x: anchor.x + Math.cos(angle) * radius, y: anchor.y + Math.sin(angle) * radius };
        if (!this.#collides(candidate, node, occupied)) return candidate;
      }
    }
    return { x: anchor.x + Math.cos(baseAngle) * baseRadius, y: anchor.y + Math.sin(baseAngle) * baseRadius };
  }

  #emptiestAngle(anchor, radius, occupied) {
    const others = occupied.filter(node => node !== anchor);
    if (others.length === 0) return 0;
    let bestAngle = 0;
    let bestClearance = -Infinity;
    for (let sector = 0; sector < PLACEMENT_SECTORS; sector += 1) {
      const angle = sector * Math.PI * 2 / PLACEMENT_SECTORS;
      const x = anchor.x + Math.cos(angle) * radius;
      const y = anchor.y + Math.sin(angle) * radius;
      const clearance = Math.min(...others.map(node =>
        Math.hypot(node.x - x, node.y - y) - this.collisionRadius(node)));
      if (clearance > bestClearance) { bestClearance = clearance; bestAngle = angle; }
    }
    return bestAngle;
  }

  #collides(candidate, node, occupied) {
    return occupied.some(other => Math.hypot(candidate.x - other.x, candidate.y - other.y)
      < this.collisionRadius(node) + this.collisionRadius(other));
  }

  #collisionViolations(relevantIds) {
    let violations = 0;
    for (let index = 0; index < this.activeNodes.length; index += 1) {
      for (let other = index + 1; other < this.activeNodes.length; other += 1) {
        const left = this.activeNodes[index];
        const right = this.activeNodes[other];
        if (!relevantIds.has(left.id) && !relevantIds.has(right.id)) continue;
        if (Math.hypot(left.x - right.x, left.y - right.y)
          < this.collisionRadius(left) + this.collisionRadius(right)) violations += 1;
      }
    }
    return violations;
  }

  #edgeLength(link) {
    return Math.hypot(link.target.x - link.source.x, link.target.y - link.source.y);
  }

  #requiredNode(id) {
    const node = this.nodeById.get(id);
    if (!node) throw new Error(`unknown Sigma physics node: ${id}`);
    return node;
  }
}
