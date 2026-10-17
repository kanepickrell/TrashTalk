/**
 * Pins the TypeScript join against tools/valhalla/test_resolve.py.
 *
 * Same four cases, same construction: synthetic matches built from the real
 * network with GPS-like noise, checked that the resolver puts them back where
 * they came from. Valhalla does not need to be running, which is the point --
 * the join can be trusted before a tile build has finished, and in CI where
 * there is no Valhalla at all.
 *
 * The case that matters is the third. Being wrong here does not look like an
 * error, it looks like a pickup credited to the next block along.
 *
 *   node --test lib/matchTrack.test.js
 */

import assert from 'node:assert/strict';
import { test } from 'node:test';

import {
  MAX_OFFSET_M,
  SEGMENTS,
  type NetworkSegment,
  type TraceAttributesResponse,
  decodePolyline6,
  resolveMatch,
} from './matchTrack';

type Point = [number, number];

/** Mirrors the encoder in test_resolve.py, so the decoder is tested for real. */
function encodePolyline6(coords: readonly Point[]): string {
  const out: string[] = [];
  let plat = 0;
  let plng = 0;
  for (const [lat, lng] of coords) {
    const ilat = Math.round(lat * 1e6);
    const ilng = Math.round(lng * 1e6);
    for (const delta of [ilat - plat, ilng - plng]) {
      let v = delta < 0 ? ~(delta << 1) : delta << 1;
      while (v >= 0x20) {
        out.push(String.fromCharCode((0x20 | (v & 0x1f)) + 63));
        v >>= 5;
      }
      out.push(String.fromCharCode(v + 63));
    }
    plat = ilat;
    plng = ilng;
  }
  return out.join('');
}

/** Deterministic noise: a seeded LCG, so a failure is reproducible. */
function makeRandom(seed: number): () => number {
  let s = seed >>> 0;
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0;
    return s / 0x1_0000_0000;
  };
}

function gauss(rand: () => number, sigma: number): number {
  const u = Math.max(rand(), 1e-12);
  return sigma * Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * rand());
}

/** Walks a segment's geometry, adding noise, as a recorded track would. */
function noisyWalk(
  seg: NetworkSegment,
  rand: () => number,
  sigmaDeg: number,
): Point[] {
  const pts: Point[] = [];
  for (let i = 0; i < seg.coords.length - 1; i++) {
    const [la1, lo1] = seg.coords[i]!;
    const [la2, lo2] = seg.coords[i + 1]!;
    for (let k = 0; k < 4; k++) {
      const f = k / 4;
      pts.push([
        la1 + (la2 - la1) * f + gauss(rand, sigmaDeg),
        lo1 + (lo2 - lo1) * f + gauss(rand, sigmaDeg),
      ]);
    }
  }
  pts.push(seg.coords[seg.coords.length - 1]!);
  return pts;
}

/** Builds a trace_attributes response as Valhalla would return one. */
function syntheticMatch(
  segs: readonly NetworkSegment[],
  rand: () => number,
  sigmaDeg: number,
): TraceAttributesResponse {
  const shape: Point[] = [];
  const edges = [];
  for (const seg of segs) {
    const begin = shape.length;
    shape.push(...noisyWalk(seg, rand, sigmaDeg));
    edges.push({
      way_id: seg.osmWayIds[0],
      names: [seg.name],
      begin_shape_index: begin,
      end_shape_index: shape.length - 1,
    });
  }
  return { shape: encodePolyline6(shape), edges };
}

/** The 8 m of noise the Python test uses, expressed in degrees. */
const SIGMA_8M = 8 / 111_320;

test('the network asset loaded', () => {
  assert.equal(SEGMENTS.length, 317);
});

test('polyline6 survives a round trip', () => {
  const coords: Point[] = [
    [29.494212, -98.455437],
    [29.49419, -98.459125],
    [29.491, -98.4612],
  ];
  const back = decodePolyline6(encodePolyline6(coords));
  assert.equal(back.length, coords.length);
  for (let i = 0; i < coords.length; i++) {
    assert.ok(Math.abs(back[i]![0] - coords[i]![0]) < 1e-6);
    assert.ok(Math.abs(back[i]![1] - coords[i]![1]) < 1e-6);
  }
});

test('distinct streets resolve under 8 m of noise', () => {
  const rand = makeRandom(20250214);
  // One segment from each of twelve different streets.
  const seen = new Set<string>();
  const picked: NetworkSegment[] = [];
  for (const seg of SEGMENTS) {
    if (seg.name && !seen.has(seg.name) && seg.osmWayIds.length > 0) {
      seen.add(seg.name);
      picked.push(seg);
    }
    if (picked.length === 12) break;
  }
  assert.equal(picked.length, 12);

  const resolved = resolveMatch(syntheticMatch(picked, rand, SIGMA_8M));
  assert.equal(resolved.length, 12);
  for (let i = 0; i < 12; i++) {
    assert.equal(
      resolved[i]!.segmentId,
      picked[i]!.id,
      `${picked[i]!.name} resolved to ${resolved[i]!.segmentName}`,
    );
  }
});

test('every block of the worst shared way resolves to its own block', () => {
  // The case the midpoint approach got wrong: one OSM way, twelve collinear
  // blocks sharing endpoints. A way id narrows to twelve; geometry must pick.
  const counts = new Map<number, NetworkSegment[]>();
  for (const seg of SEGMENTS) {
    for (const way of seg.osmWayIds) {
      const bucket = counts.get(way) ?? [];
      bucket.push(seg);
      counts.set(way, bucket);
    }
  }
  let worst: NetworkSegment[] = [];
  for (const bucket of counts.values()) {
    if (bucket.length > worst.length) worst = bucket;
  }
  assert.ok(worst.length >= 12, `expected a 12-segment way, got ${worst.length}`);

  const rand = makeRandom(7);
  const resolved = resolveMatch(syntheticMatch(worst, rand, SIGMA_8M));
  assert.equal(resolved.length, worst.length);
  for (let i = 0; i < worst.length; i++) {
    assert.equal(resolved[i]!.candidates >= 12, true);
    assert.equal(
      resolved[i]!.segmentId,
      worst[i]!.id,
      `block ${i} of ${worst[i]!.name} resolved to ${resolved[i]!.segmentName}`,
    );
  }
});

test('an off-network track stays unplaced rather than guessing', () => {
  // Attributing a pickup to whichever block happened to be nearest would put
  // litter on streets nobody walked, which is worse than recording nothing.
  const far: NetworkSegment = {
    id: 'elsewhere',
    name: 'Somewhere Else',
    osmWayIds: [999_999_999],
    coords: [
      [29.6, -98.6],
      [29.601, -98.601],
    ],
  };
  const rand = makeRandom(99);
  const resolved = resolveMatch(syntheticMatch([far], rand, SIGMA_8M));
  assert.equal(resolved.length, 1);
  assert.equal(resolved[0]!.segmentId, null);
  assert.equal(resolved[0]!.narrowedByWayId, false);
  assert.ok(resolved[0]!.metresOff > MAX_OFFSET_M);
});
