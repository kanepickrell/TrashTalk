/**
 * Map matching, and the join from a matched edge to a TrashTalk segment.
 *
 * Ported from tools/valhalla/match_track.py, which is where the join was
 * worked out and proved. Keep the two in step: the Python is the reference
 * and matchTrack.test.ts is the proof they still agree.
 *
 * The naive version does not work. Valhalla returns an OSM way id per matched
 * edge and it is tempting to treat that as the segment id. It is not one:
 * osmnx splits ways at intersections, so 317 segments carry 148 distinct way
 * ids and 67 of those ways span several segments -- Haskin Drive is one way
 * across twelve blocks. A way id narrows the answer to twelve candidates.
 *
 * So the join is two steps: the way id narrows, then the matched edge's own
 * geometry chooses between what is left.
 */

import network from './network.json';

/** Beyond this, the match is not attributed to any segment at all. */
export const MAX_OFFSET_M = 40;

const EARTH_RADIUS_M = 6_371_000;

export interface Fix {
  lat: number;
  lng: number;
  at?: number;
}

export interface NetworkSegment {
  id: string;
  name: string;
  osmWayIds: number[];
  coords: [number, number][];
}

export interface ResolvedEdge {
  wayId: number | null;
  /** The street name Valhalla reported, which is not always one we carry. */
  matchedName: string | null;
  segmentId: string | null;
  segmentName: string | null;
  metresOff: number;
  narrowedByWayId: boolean;
  candidates: number;
}

type Point = readonly [number, number];

export function haversineM(a: Point, b: Point): number {
  const p1 = (a[0] * Math.PI) / 180;
  const p2 = (b[0] * Math.PI) / 180;
  const dp = p2 - p1;
  const dl = ((b[1] - a[1]) * Math.PI) / 180;
  const h =
    Math.sin(dp / 2) ** 2 + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) ** 2;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(h));
}

/**
 * Metres from p to the line segment ab, in a local flat approximation.
 *
 * Flat is fine and deliberate: these distances are tens of metres inside one
 * neighbourhood, where the error from ignoring curvature is far below the GPS
 * noise the whole thing is built to tolerate.
 */
export function pointToSegmentM(p: Point, a: Point, b: Point): number {
  const mx = 111_320 * Math.cos((p[0] * Math.PI) / 180);
  const my = 110_540;

  const px = p[1] * mx;
  const py = p[0] * my;
  const ax = a[1] * mx;
  const ay = a[0] * my;
  const bx = b[1] * mx;
  const by = b[0] * my;

  const dx = bx - ax;
  const dy = by - ay;
  if (dx === 0 && dy === 0) return Math.hypot(px - ax, py - ay);

  const t = Math.max(
    0,
    Math.min(1, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)),
  );
  return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
}

export function distanceToPolylineM(p: Point, poly: readonly Point[]): number {
  if (poly.length === 0) return Number.POSITIVE_INFINITY;
  if (poly.length === 1) return haversineM(p, poly[0]!);

  let best = Number.POSITIVE_INFINITY;
  for (let i = 0; i < poly.length - 1; i++) {
    const d = pointToSegmentM(p, poly[i]!, poly[i + 1]!);
    if (d < best) best = d;
  }
  return best;
}

/** Valhalla encodes shapes at six decimal places, not Google's five. */
export function decodePolyline6(encoded: string): Point[] {
  const coords: Point[] = [];
  let index = 0;
  let lat = 0;
  let lng = 0;

  while (index < encoded.length) {
    for (const first of [true, false]) {
      let shift = 0;
      let result = 0;
      let b: number;
      do {
        b = encoded.charCodeAt(index++) - 63;
        result |= (b & 0x1f) << shift;
        shift += 5;
      } while (b >= 0x20);
      const delta = result & 1 ? ~(result >> 1) : result >> 1;
      if (first) lat += delta;
      else lng += delta;
    }
    coords.push([lat / 1e6, lng / 1e6]);
  }
  return coords;
}

export const SEGMENTS: NetworkSegment[] = (
  network.segments as NetworkSegment[]
).map((s) => ({
  id: s.id,
  name: s.name,
  osmWayIds: s.osmWayIds ?? [],
  coords: s.coords as [number, number][],
}));

export function buildWayIndex(
  segments: readonly NetworkSegment[] = SEGMENTS,
): Map<number, NetworkSegment[]> {
  const index = new Map<number, NetworkSegment[]>();
  for (const seg of segments) {
    for (const way of seg.osmWayIds) {
      const bucket = index.get(way);
      if (bucket) bucket.push(seg);
      else index.set(way, [seg]);
    }
  }
  return index;
}

/**
 * Mean distance from the whole matched piece to a candidate segment.
 *
 * Averaged over the piece, not sampled at its midpoint, and that is the part
 * that makes the join correct rather than nearly correct. Consecutive blocks
 * of one street are collinear and share an endpoint, so a midpoint that
 * drifts a few metres sits just as near the next block along: on the twelve
 * blocks of Haskin Drive, midpoint-only put three of twelve on the wrong one.
 * Averaging uses the length of the match, which is the information that tells
 * one block from the next.
 */
function meanOffsetM(piece: readonly Point[], seg: NetworkSegment): number {
  let total = 0;
  for (const pt of piece) total += distanceToPolylineM(pt, seg.coords);
  return total / piece.length;
}

export interface MatchedEdge {
  way_id?: number;
  names?: string[];
  begin_shape_index?: number;
  end_shape_index?: number;
}

export interface TraceAttributesResponse {
  shape?: string;
  edges?: MatchedEdge[];
}

/** Maps each matched edge to a segment, or to null when nothing is near. */
export function resolveMatch(
  matched: TraceAttributesResponse,
  segments: readonly NetworkSegment[] = SEGMENTS,
  wayIndex?: Map<number, NetworkSegment[]>,
): ResolvedEdge[] {
  const shape = decodePolyline6(matched.shape ?? '');
  const byWay = wayIndex ?? buildWayIndex(segments);
  const out: ResolvedEdge[] = [];

  for (const edge of matched.edges ?? []) {
    const wayId = edge.way_id ?? null;
    const lo = edge.begin_shape_index ?? 0;
    const hi = edge.end_shape_index ?? lo;

    let piece = shape.slice(lo, hi + 1);
    if (piece.length === 0) piece = shape.slice(lo, lo + 1);
    if (piece.length === 0) continue;

    // Step 1: the way id narrows the field. Falling back to the whole network
    // is for ways we do not carry, which happens at the pilot's edges -- a
    // walk that steps one street outside should still resolve if it is near
    // something of ours, and resolve to nothing if it is not.
    const narrowed = wayId === null ? undefined : byWay.get(wayId);
    const candidates = narrowed ?? segments;

    // Step 2: geometry chooses between them.
    let best: NetworkSegment | null = null;
    let bestDist = Number.POSITIVE_INFINITY;
    for (const seg of candidates) {
      const d = meanOffsetM(piece, seg);
      if (d < bestDist) {
        bestDist = d;
        best = seg;
      }
    }
    if (!best) continue;

    const placed = bestDist <= MAX_OFFSET_M;
    out.push({
      wayId,
      matchedName: edge.names?.[0] ?? null,
      segmentId: placed ? best.id : null,
      segmentName: placed ? best.name : null,
      metresOff: Math.round(bestDist * 10) / 10,
      narrowedByWayId: narrowed !== undefined,
      candidates: candidates.length,
    });
  }
  return out;
}

/**
 * Asks Valhalla to match a track.
 *
 * map_snap rather than edge_walk: a recorded walk carries GPS noise and will
 * not lie on the graph, which is the assumption edge_walk makes.
 */
export async function traceAttributes(
  track: readonly Fix[],
  url: string,
  timeoutMs = 20_000,
): Promise<TraceAttributesResponse> {
  const body = {
    shape: track.map((p) => ({ lat: p.lat, lon: p.lng })),
    costing: 'pedestrian',
    shape_match: 'map_snap',
    filters: {
      attributes: [
        'edge.way_id',
        'edge.names',
        'edge.begin_shape_index',
        'edge.end_shape_index',
        'shape',
      ],
      action: 'include',
    },
  };

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const resp = await fetch(`${url}/trace_attributes`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      signal: controller.signal,
    });
    if (!resp.ok) {
      throw new Error(`valhalla ${resp.status} ${await resp.text()}`);
    }
    return (await resp.json()) as TraceAttributesResponse;
  } finally {
    clearTimeout(timer);
  }
}
