// Orthogonal edge routing that steers around node rectangles.
//
// The canvas is reduced to a sparse grid whose lines are the (inflated) borders of nearby
// nodes, the channels between them and the edge's own end points. A* then searches that
// grid for the shortest path, penalising bends, without ever entering a node.

export type Pt = { x: number; y: number };
export type Rect = { x: number; y: number; w: number; h: number };
/** 0 = right, 1 = down, 2 = left, 3 = up */
export type Dir = 0 | 1 | 2 | 3;

const DX = [1, 0, -1, 0];
const DY = [0, 1, 0, -1];

/** Gap kept between an edge and any node. */
const MARGIN = 18;
/** Extra cost of a bend, in pixels of length. */
const BEND_COST = 40;
/** How far around the source/target box nodes still contribute grid lines. */
const REGION_PAD = 240;
/** Safety cap on explored states so a pathological layout cannot freeze dragging. */
const MAX_EXPANSIONS = 60000;

type Box = { l: number; t: number; r: number; b: number };

const inflate = (r: Rect, m: number): Box => ({ l: r.x - m, t: r.y - m, r: r.x + r.w + m, b: r.y + r.h + m });
const strictlyInside = (p: Pt, o: Box) => p.x > o.l && p.x < o.r && p.y > o.t && p.y < o.b;

/** Does the axis-aligned segment a→b pass through the interior of o? */
function crosses(a: Pt, b: Pt, o: Box): boolean {
  if (a.y === b.y) {
    if (a.y <= o.t || a.y >= o.b) return false;
    const lo = Math.min(a.x, b.x);
    const hi = Math.max(a.x, b.x);
    return hi > o.l && lo < o.r;
  }
  if (a.x <= o.l || a.x >= o.r) return false;
  const lo = Math.min(a.y, b.y);
  const hi = Math.max(a.y, b.y);
  return hi > o.t && lo < o.b;
}

/** Sorted unique coordinates plus the midpoint of every gap wide enough to be a channel. */
function axis(values: number[]): number[] {
  const s = [...new Set(values.map((v) => Math.round(v)))].sort((a, b) => a - b);
  const out: number[] = [];
  for (let i = 0; i < s.length; i++) {
    out.push(s[i]);
    if (i + 1 < s.length && s[i + 1] - s[i] > MARGIN) out.push(Math.round((s[i] + s[i + 1]) / 2));
  }
  return out;
}

class MinHeap {
  private k: number[] = [];
  private v: number[] = [];
  get size() {
    return this.k.length;
  }
  push(key: number, val: number) {
    const k = this.k;
    const v = this.v;
    let i = k.length;
    k.push(key);
    v.push(val);
    while (i > 0) {
      const p = (i - 1) >> 1;
      if (k[p] <= key) break;
      k[i] = k[p];
      v[i] = v[p];
      i = p;
    }
    k[i] = key;
    v[i] = val;
  }
  pop(): number {
    const k = this.k;
    const v = this.v;
    const top = v[0];
    const lk = k.pop()!;
    const lv = v.pop()!;
    const n = k.length;
    if (n > 0) {
      let i = 0;
      for (;;) {
        const l = 2 * i + 1;
        if (l >= n) break;
        const c = l + 1 < n && k[l + 1] < k[l] ? l + 1 : l;
        if (k[c] >= lk) break;
        k[i] = k[c];
        v[i] = v[c];
        i = c;
      }
      k[i] = lk;
      v[i] = lv;
    }
    return top;
  }
}

/**
 * Route from `src` (leaving in `srcDir`) to `dst` (whose handle faces `dstDir`, so the edge
 * arrives moving opposite to it). Returns the polyline, or null when no route exists.
 */
export function routeOrthogonal(src: Pt, srcDir: Dir, dst: Pt, dstDir: Dir, nodes: Rect[]): Pt[] | null {
  const s0 = { x: Math.round(src.x + DX[srcDir] * MARGIN), y: Math.round(src.y + DY[srcDir] * MARGIN) };
  const e0 = { x: Math.round(dst.x + DX[dstDir] * MARGIN), y: Math.round(dst.y + DY[dstDir] * MARGIN) };

  // Nodes squeezed so tightly that a stub end lies inside them cannot be avoided; ignore them.
  const obstacles = nodes.map((n) => inflate(n, MARGIN)).filter((o) => !strictlyInside(s0, o) && !strictlyInside(e0, o));

  const region: Box = {
    l: Math.min(s0.x, e0.x) - REGION_PAD,
    t: Math.min(s0.y, e0.y) - REGION_PAD,
    r: Math.max(s0.x, e0.x) + REGION_PAD,
    b: Math.max(s0.y, e0.y) + REGION_PAD,
  };
  const xsRaw = [s0.x, e0.x, region.l, region.r];
  const ysRaw = [s0.y, e0.y, region.t, region.b];
  for (const o of obstacles) {
    if (o.r < region.l || o.l > region.r || o.b < region.t || o.t > region.b) continue;
    xsRaw.push(o.l, o.r);
    ysRaw.push(o.t, o.b);
  }
  const xs = axis(xsRaw);
  const ys = axis(ysRaw);
  const nx = xs.length;
  const ny = ys.length;

  const sx = xs.indexOf(s0.x);
  const sy = ys.indexOf(s0.y);
  const ex = xs.indexOf(e0.x);
  const ey = ys.indexOf(e0.y);
  const goal = ey * nx + ex;
  const arriveDir = (dstDir + 2) % 4;

  // Cell blocked = inside a node. Computed lazily.
  const blocked = new Int8Array(nx * ny); // 0 unknown, 1 free, 2 blocked
  const isFree = (cell: number) => {
    if (blocked[cell] === 0) {
      const p = { x: xs[cell % nx], y: ys[(cell / nx) | 0] };
      blocked[cell] = obstacles.some((o) => strictlyInside(p, o)) ? 2 : 1;
    }
    return blocked[cell] === 1;
  };

  const states = nx * ny * 4;
  const g = new Float64Array(states).fill(Infinity);
  const parent = new Int32Array(states).fill(-1);
  const closed = new Uint8Array(states);
  const heap = new MinHeap();
  const h = (cell: number) => Math.abs(xs[cell % nx] - e0.x) + Math.abs(ys[(cell / nx) | 0] - e0.y);

  const start = (sy * nx + sx) * 4 + srcDir;
  g[start] = 0;
  heap.push(h(sy * nx + sx), start);

  let found = -1;
  let expansions = 0;
  while (heap.size > 0) {
    const st = heap.pop();
    if (closed[st]) continue;
    closed[st] = 1;
    const cell = st >> 2;
    const d = st & 3;
    if (cell === goal) {
      found = st;
      break;
    }
    if (++expansions > MAX_EXPANSIONS) break;
    const cx = cell % nx;
    const cy = (cell / nx) | 0;
    const a = { x: xs[cx], y: ys[cy] };
    for (let nd = 0; nd < 4; nd++) {
      if (nd === (d + 2) % 4) continue; // no U-turns
      const qx = cx + DX[nd];
      const qy = cy + DY[nd];
      if (qx < 0 || qy < 0 || qx >= nx || qy >= ny) continue;
      const ncell = qy * nx + qx;
      if (!isFree(ncell)) continue;
      const b = { x: xs[qx], y: ys[qy] };
      if (obstacles.some((o) => crosses(a, b, o))) continue;
      let cost = g[st] + Math.abs(b.x - a.x) + Math.abs(b.y - a.y) + (nd !== d ? BEND_COST : 0);
      if (ncell === goal && nd !== arriveDir) cost += BEND_COST; // must still turn into the handle
      const ns = ncell * 4 + nd;
      if (cost < g[ns]) {
        g[ns] = cost;
        parent[ns] = st;
        heap.push(cost + h(ncell), ns);
      }
    }
  }
  if (found < 0) return null;

  const pts: Pt[] = [dst];
  for (let st = found; st >= 0; st = parent[st]) {
    const cell = st >> 2;
    pts.push({ x: xs[cell % nx], y: ys[(cell / nx) | 0] });
  }
  pts.push(src);
  pts.reverse();
  return simplify(pts);
}

/** Drop duplicate and collinear points. */
function simplify(pts: Pt[]): Pt[] {
  const out: Pt[] = [];
  for (const p of pts) {
    const last = out[out.length - 1];
    if (last && last.x === p.x && last.y === p.y) continue;
    if (out.length >= 2) {
      const prev = out[out.length - 2];
      if ((prev.x === last.x && last.x === p.x) || (prev.y === last.y && last.y === p.y)) out.pop();
    }
    out.push(p);
  }
  return out;
}

/** SVG path through the points with rounded corners. */
export function roundedPath(pts: Pt[], radius = 8): string {
  if (pts.length === 0) return '';
  let d = `M ${pts[0].x},${pts[0].y}`;
  for (let i = 1; i < pts.length - 1; i++) {
    const p = pts[i - 1];
    const c = pts[i];
    const n = pts[i + 1];
    const r = Math.min(radius, Math.hypot(c.x - p.x, c.y - p.y) / 2, Math.hypot(n.x - c.x, n.y - c.y) / 2);
    const ax = c.x - Math.sign(c.x - p.x) * r;
    const ay = c.y - Math.sign(c.y - p.y) * r;
    const bx = c.x + Math.sign(n.x - c.x) * r;
    const by = c.y + Math.sign(n.y - c.y) * r;
    d += ` L ${ax},${ay} Q ${c.x},${c.y} ${bx},${by}`;
  }
  const last = pts[pts.length - 1];
  return `${d} L ${last.x},${last.y}`;
}

/** Point halfway along the polyline (for labels). */
export function midpoint(pts: Pt[]): Pt {
  let total = 0;
  for (let i = 1; i < pts.length; i++) total += Math.abs(pts[i].x - pts[i - 1].x) + Math.abs(pts[i].y - pts[i - 1].y);
  let rest = total / 2;
  for (let i = 1; i < pts.length; i++) {
    const a = pts[i - 1];
    const b = pts[i];
    const len = Math.abs(b.x - a.x) + Math.abs(b.y - a.y);
    if (rest <= len && len > 0) {
      const t = rest / len;
      return { x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t };
    }
    rest -= len;
  }
  return pts[0];
}
