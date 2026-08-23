import {
  forceCollide,
  forceLink,
  forceManyBody,
  forceSimulation,
  forceX,
  forceY
} from 'd3-force';

export const RESTART_PRESETS = Object.freeze({
  'browser-like': 1,
  gentle: 0.25
});

export const BROWSER_REFERENCE_FORCE_CONFIG = Object.freeze({
  name: 'browser-reference',
  velocityDecay: 0.4,
  manyBodyStrength: -400,
  centerStrength: 0.03,
  nodeGap: 25,
  linkGap: 90
});

const DEFAULT_FORCE_CONFIG = Object.freeze({
  name: 'current',
  nodeSize: 46,
  visualNodeSize: 40,
  nodeGap: 9,
  linkDistance: 100,
  linkStrength: 0.55,
  manyBodyStrength: -160,
  collideStrength: 0.95,
  collideIterations: 3,
  centerStrength: 0.08,
  velocityDecay: 0.35,
  alphaDecay: 0.045
});

const LINK_BASE_GAP = 60;
const COLLISION_PADDING = 12;
const AXIS_STRENGTH = 0.03;
const PLACEMENT_SECTORS = 32;

const finite = (value, fallback) => Number.isFinite(Number(value)) ? Number(value) : fallback;
const rounded = value => Math.round(value * 1_000_000) / 1_000_000;

function pairKey(source, target) {
  return source < target ? `${source}\u0000${target}` : `${target}\u0000${source}`;
}

function percentile(values, fraction) {
  if (values.length === 0) return null;
  const sorted = [...values].sort((left, right) => left - right);
  return sorted[Math.max(0, Math.ceil(sorted.length * fraction) - 1)];
}

function distribution(values) {
  const result = {};
  for (const value of values) {
    const key = String(rounded(value));
    result[key] = (result[key] ?? 0) + 1;
  }
  return result;
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

function diameter(value, fallback) {
  const size = value?.visualSize ?? value?.visualNodeSize ?? value?.style?.size ?? fallback;
  if (Array.isArray(size)) {
    const values = size.map(Number).filter(Number.isFinite);
    return values.length ? Math.max(...values) : fallback;
  }
  return finite(size, fallback);
}

export class PersistentForceSimulation {
  nodeById = new Map();
  activeNodes = [];
  physicsLinks = [];
  creationCount = 0;
  stopped = true;
  lastReconcile = {
    createdNodes: 0,
    reusedNodes: 0,
    initialCollisionViolations: 0,
    newLinkInitialDistanceRatio: summary([])
  };

  constructor(options = {}, onTick = () => {}) {
    this.width = finite(options.width, 800);
    this.height = finite(options.height, 600);
    this.config = { ...DEFAULT_FORCE_CONFIG, ...options };
    this.simulation = forceSimulation([]).stop();
    this.creationCount += 1;
    this.simulation.on('tick.adapter', onTick);
    this.simulation.on('end.lifecycle', () => { this.stopped = true; });
    this.#applyForces();
  }

  reconcile(nodes, relationships, { anchorId = null } = {}) {
    const rawById = new Map(nodes.map(node => [node.id, node]));
    const anchor = this.nodeById.get(anchorId);
    const newIds = new Set(nodes.filter(node => !this.nodeById.has(node.id)).map(node => node.id));
    const oldMotion = new Map();
    const occupied = [...this.activeNodes];
    let createdNodes = 0;
    let reusedNodes = 0;

    this.activeNodes = nodes.map((node, index) => {
      const existing = this.nodeById.get(node.id);
      if (existing) {
        oldMotion.set(node.id, {
          x: existing.x, y: existing.y, vx: existing.vx, vy: existing.vy,
          fx: existing.fx, fy: existing.fy
        });
        existing.visualRadius = this.#visualRadius(node);
        reusedNodes += 1;
        return existing;
      }

      const visualRadius = this.#visualRadius(node);
      const created = anchor
        ? this.#placeNearAnchor(node.id, visualRadius, anchor, occupied, createdNodes)
        : this.#placeInitialNode(node.id, visualRadius, occupied, index, nodes.length);
      this.nodeById.set(node.id, created);
      occupied.push(created);
      createdNodes += 1;
      return created;
    });

    const activeIds = new Set(this.activeNodes.map(node => node.id));
    const uniquePairs = new Set();
    this.physicsLinks = [];
    for (const relationship of relationships) {
      if (!activeIds.has(relationship.source) || !activeIds.has(relationship.target)) continue;
      const key = pairKey(relationship.source, relationship.target);
      if (uniquePairs.has(key)) continue;
      uniquePairs.add(key);
      this.physicsLinks.push({ key, source: relationship.source, target: relationship.target });
    }

    this.simulation.nodes(this.activeNodes);
    this.simulation.force('link').links(this.physicsLinks);
    for (const [id, motion] of oldMotion) Object.assign(this.nodeById.get(id), motion);

    const initialCollisionViolations = this.#collisionViolations(newIds);
    const newLinkRatios = this.physicsLinks
      .filter(link => newIds.has(link.source.id) || newIds.has(link.target.id))
      .map(link => this.#edgeLength(link) / this.preferredDistance(link.source, link.target));
    this.lastReconcile = {
      createdNodes,
      reusedNodes,
      initialCollisionViolations,
      newLinkInitialDistanceRatio: summary(newLinkRatios)
    };
    return {
      createdNodes,
      reusedNodes,
      physicsLinks: this.physicsLinks.length,
      initialCollisionViolations,
      newLinkInitialDistanceRatio: this.lastReconcile.newLinkInitialDistanceRatio
    };
  }

  configure(options = {}) {
    this.config = { ...this.config, ...options };
    for (const node of this.activeNodes) {
      node.visualRadius = this.#visualRadius(node);
    }
    this.#applyForces();
  }

  useForcePreset(name) {
    if (name === 'browser-reference') {
      this.configure({ ...BROWSER_REFERENCE_FORCE_CONFIG });
      return;
    }
    if (name !== 'current') throw new Error(`unknown force preset: ${name}`);
    this.configure({ ...DEFAULT_FORCE_CONFIG });
  }

  restart(preset = 'browser-like') {
    const alpha = RESTART_PRESETS[preset];
    if (alpha === undefined) throw new Error(`unknown restart preset: ${preset}`);
    this.stopped = false;
    this.simulation.alphaTarget(0).alpha(alpha).restart();
    return alpha;
  }

  settle(iterations = 300) {
    this.simulation.alphaTarget(0).alpha(1).stop().tick(iterations);
    this.stopped = true;
  }

  beginInteraction() {
    this.stopped = false;
    this.simulation.alpha(Math.max(this.simulation.alpha(), 0.3)).alphaTarget(0.3).restart();
  }

  endInteraction() {
    this.simulation.alphaTarget(0);
  }

  pin(id, x, y) {
    const node = this.#requiredNode(id);
    node.fx = finite(x, node.x);
    node.fy = finite(y, node.y);
  }

  movePinned(id, dx, dy) {
    const node = this.#requiredNode(id);
    node.fx = finite(node.fx, node.x) + finite(dx, 0);
    node.fy = finite(node.fy, node.y) + finite(dy, 0);
  }

  unpin(id) {
    const node = this.#requiredNode(id);
    node.fx = null;
    node.fy = null;
  }

  getNode(id) {
    return this.nodeById.get(id);
  }

  preferredDistance(source, target) {
    return this.#nodeVisualRadius(source) + this.#nodeVisualRadius(target) + LINK_BASE_GAP;
  }

  collisionRadiusFor(node) {
    return this.#nodeVisualRadius(node) + COLLISION_PADDING;
  }

  snapshotPositions(ids = this.activeNodes.map(node => node.id)) {
    return new Map(ids.flatMap(id => {
      const node = this.nodeById.get(id);
      return node ? [[id, [node.x, node.y]]] : [];
    }));
  }

  diagnostics() {
    const degrees = new Map(this.activeNodes.map(node => [node.id, 0]));
    for (const link of this.physicsLinks) {
      degrees.set(link.source.id, (degrees.get(link.source.id) ?? 0) + 1);
      degrees.set(link.target.id, (degrees.get(link.target.id) ?? 0) + 1);
    }
    const degreeValues = [...degrees.values()];
    const linkForce = this.simulation.force('link');
    const strengthAccessor = linkForce.strength();
    const strengths = this.physicsLinks.map((link, index, links) => strengthAccessor(link, index, links));
    const preferredDistances = this.physicsLinks.map(link => this.preferredDistance(link.source, link.target));
    const collisionRadii = this.activeNodes.map(node => this.collisionRadiusFor(node));
    const visualRadii = this.activeNodes.map(node => this.#nodeVisualRadius(node));
    const settledRatios = this.physicsLinks.map(link =>
      this.#edgeLength(link) / this.preferredDistance(link.source, link.target));
    const degreeDistribution = {};
    for (const degree of degreeValues) degreeDistribution[degree] = (degreeDistribution[degree] ?? 0) + 1;
    return {
      forceComposition: {
        link: 'd3.forceLink/native-default-strength',
        center: null,
        x: 'd3.forceX',
        y: 'd3.forceY'
      },
      axisStrength: AXIS_STRENGTH,
      physicsLinkCount: this.physicsLinks.length,
      physicalDegreeDistribution: degreeDistribution,
      linkStrength: {
        ...summary(strengths),
        uniqueCount: new Set(strengths.map(rounded)).size,
        distribution: distribution(strengths)
      },
      geometry: {
        visualRadius: visualRadii.length && new Set(visualRadii.map(rounded)).size === 1 ? rounded(visualRadii[0]) : null,
        visualRadiusRange: summary(visualRadii),
        collisionRadius: collisionRadii.length && new Set(collisionRadii.map(rounded)).size === 1
          ? rounded(collisionRadii[0]) : null,
        collisionRadiusRange: summary(collisionRadii),
        preferredLinkDistance: preferredDistances.length && new Set(preferredDistances.map(rounded)).size === 1
          ? rounded(preferredDistances[0]) : null,
        preferredLinkDistanceRange: summary(preferredDistances),
        linkBaseGap: LINK_BASE_GAP,
        collisionPadding: COLLISION_PADDING
      },
      settledEdgeLengthRatio: summary(settledRatios),
      fixedNodeCount: this.activeNodes.filter(node => node.fx != null && node.fy != null).length,
      lastReconcile: structuredClone(this.lastReconcile)
    };
  }

  stop() {
    this.stopped = true;
    this.simulation.stop();
  }

  #applyForces() {
    const link = this.simulation.force('link') ?? forceLink([]).id(node => node.id);
    link.id(node => node.id)
      .distance(edge => this.preferredDistance(edge.source, edge.target))
      .iterations(2);
    this.simulation
      .velocityDecay(this.config.velocityDecay)
      .alphaDecay(this.config.alphaDecay)
      .force('link', link)
      .force('charge', forceManyBody().strength(this.config.manyBodyStrength).distanceMin(16).distanceMax(900))
      .force('collide', forceCollide(node => this.collisionRadiusFor(node))
        .strength(this.config.collideStrength)
        .iterations(this.config.collideIterations))
      .force('center', null)
      .force('x', forceX(this.width / 2).strength(AXIS_STRENGTH))
      .force('y', forceY(this.height / 2).strength(AXIS_STRENGTH));
    if (this.activeNodes.length) link.links(this.physicsLinks);
  }

  #placeNearAnchor(id, visualRadius, anchor, occupied, ordinal) {
    const provisional = { id, visualRadius };
    const preferred = this.preferredDistance(anchor, provisional);
    const baseAngle = this.#emptiestAngle(anchor, preferred, occupied);
    const radialStep = Math.max(this.collisionRadiusFor(provisional) * 2, preferred * 0.5);
    for (let ring = 0; ring < 8; ring += 1) {
      const radius = preferred + ring * radialStep;
      for (let step = 0; step < PLACEMENT_SECTORS; step += 1) {
        const offset = (step + ordinal * 7) % PLACEMENT_SECTORS;
        const angle = baseAngle + offset * Math.PI * 2 / PLACEMENT_SECTORS;
        const candidate = {
          id,
          visualRadius,
          x: anchor.x + Math.cos(angle) * radius,
          y: anchor.y + Math.sin(angle) * radius,
          vx: 0,
          vy: 0
        };
        if (!this.#collides(candidate, occupied)) return candidate;
      }
    }
    const fallbackRadius = preferred + 8 * radialStep;
    return {
      id,
      visualRadius,
      x: anchor.x + Math.cos(baseAngle) * fallbackRadius,
      y: anchor.y + Math.sin(baseAngle) * fallbackRadius,
      vx: 0,
      vy: 0
    };
  }

  #placeInitialNode(id, visualRadius, occupied, index, count) {
    const baseRadius = Math.min(this.width, this.height) * 0.18;
    const collisionDiameter = (visualRadius + COLLISION_PADDING) * 2;
    for (let ring = 0; ring < 8; ring += 1) {
      const radius = baseRadius + ring * collisionDiameter;
      for (let step = 0; step < PLACEMENT_SECTORS; step += 1) {
        const angle = ((index + step) * Math.PI * 2) / Math.max(1, count, PLACEMENT_SECTORS);
        const candidate = {
          id,
          visualRadius,
          x: this.width / 2 + Math.cos(angle) * radius,
          y: this.height / 2 + Math.sin(angle) * radius,
          vx: 0,
          vy: 0
        };
        if (!this.#collides(candidate, occupied)) return candidate;
      }
    }
    return {
      id,
      visualRadius,
      x: this.width / 2 + index * collisionDiameter,
      y: this.height / 2,
      vx: 0,
      vy: 0
    };
  }

  #emptiestAngle(anchor, radius, occupied = this.activeNodes) {
    const otherNodes = occupied.filter(node => node !== anchor);
    if (otherNodes.length === 0) return 0;
    let bestAngle = 0;
    let bestClearance = -Infinity;
    for (let sector = 0; sector < PLACEMENT_SECTORS; sector += 1) {
      const angle = sector * Math.PI * 2 / PLACEMENT_SECTORS;
      const candidate = {
        x: anchor.x + Math.cos(angle) * radius,
        y: anchor.y + Math.sin(angle) * radius
      };
      const clearance = Math.min(...otherNodes.map(node =>
        Math.hypot(node.x - candidate.x, node.y - candidate.y) - this.collisionRadiusFor(node)));
      if (clearance > bestClearance) {
        bestAngle = angle;
        bestClearance = clearance;
      }
    }
    return bestAngle;
  }

  #collides(candidate, occupied) {
    return occupied.some(node =>
      Math.hypot(node.x - candidate.x, node.y - candidate.y)
        < this.collisionRadiusFor(node) + this.collisionRadiusFor(candidate));
  }

  #collisionViolations(newIds) {
    let violations = 0;
    for (let index = 0; index < this.activeNodes.length; index += 1) {
      for (let other = index + 1; other < this.activeNodes.length; other += 1) {
        const left = this.activeNodes[index];
        const right = this.activeNodes[other];
        if (!newIds.has(left.id) && !newIds.has(right.id)) continue;
        if (Math.hypot(left.x - right.x, left.y - right.y)
            < this.collisionRadiusFor(left) + this.collisionRadiusFor(right)) violations += 1;
      }
    }
    return violations;
  }

  #visualRadius(node) {
    const storedRadius = Number(node?.visualRadius);
    if (Number.isFinite(storedRadius) && storedRadius > 0) return storedRadius;
    return diameter(node, this.config.visualNodeSize) / 2;
  }

  #nodeVisualRadius(node) {
    return finite(node?.visualRadius, this.config.visualNodeSize / 2);
  }

  #edgeLength(link) {
    return Math.hypot(link.target.x - link.source.x, link.target.y - link.source.y);
  }

  #requiredNode(id) {
    const node = this.nodeById.get(id);
    if (!node) throw new Error(`unknown simulation node: ${id}`);
    return node;
  }
}
