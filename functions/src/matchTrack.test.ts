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
  /** Force the reported way id, for the shared-way case. */
  wayId?: number,
): TraceAttributesResponse {
  const shape: Point[] = [];
  const edges = [];
  for (const seg of segs) {
    const begin = shape.length;
    shape.push(...noisyWalk(seg, rand, sigmaDeg));
    edges.push({
      way_id: wayId ?? seg.osmWayIds[0],
      names: [seg.name],
      begin_shape_index: begin,
      end_shape_index: shape.length - 1,
    });
  }
  return { shape: encodePolyline6(shape), edges };
}

/** The 8 m of noise the Python test uses, expressed in degrees. */
const SIGMA_8M = 8 / 111_320;

function lengthM(seg: NetworkSegment): number {
  let total = 0;
  for (let i = 0; i < seg.coords.length - 1; i++) {
    const [la1, lo1] = seg.coords[i]!;
    const [la2, lo2] = seg.coords[i + 1]!;
    total += Math.hypot(
      (la2 - la1) * 110_574,
      (lo2 - lo1) * 111_320 * Math.cos((la1 * Math.PI) / 180),
    );
  }
  return total;
}

test('the network asset loaded', () => {
  // Deliberately not a fixed count. The survey area grows when the bbox in
  // generate_street_network.py does, and a hard-coded number turns every
  // legitimate expansion into a failing test that says nothing useful.
  assert.ok(SEGMENTS.length > 0, 'network.json has no segments');
  assert.ok(
    SEGMENTS.every((s) => s.id && s.coords.length >= 2),
    'every segment needs an id and a drawable geometry',
  );
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
  let worstWay = -1;
  for (const [way, bucket] of counts) {
    if (bucket.length > worst.length) {
      worst = bucket;
      worstWay = way;
    }
  }
  assert.ok(
    worst.length >= 10,
    `expected a long shared way, got ${worst.length}`,
  );

  // Report the shared way for every block, which is what Valhalla does and
  // what makes this the hard case: the way id narrows to all of them at once
  // and geometry has to tell them apart.
  const rand = makeRandom(7);
  const resolved = resolveMatch(syntheticMatch(worst, rand, SIGMA_8M, worstWay));
  assert.equal(resolved.length, worst.length);

  for (let i = 0; i < worst.length; i++) {
    assert.equal(
      resolved[i]!.candidates,
      worst.length,
      'the way id should narrow to exactly the blocks of that way',
    );

    // Blocks shorter than the GPS noise are deliberately not asserted, and
    // this is a limit of the data rather than of the join. The network holds
    // 15 segments under 10 m -- stubs OSM leaves between two junctions a few
    // metres apart -- and under 8 m of noise such a block is smaller than the
    // error bar on knowing where you stood. It shares both endpoints with its
    // neighbours, so there is no geometry left to tell them apart, and a test
    // that demanded one is asserting something no resolver could deliver.
    if (lengthM(worst[i]!) < 2 * 8) continue;

    assert.equal(
      resolved[i]!.segmentId,
      worst[i]!.id,
      `block ${i} of ${worst[i]!.name} resolved to ${resolved[i]!.segmentName}`,
    );
  }
});

test('segments shorter than GPS noise are known about, not silently tolerated', () => {
  // A guard rather than an aspiration. These blocks are claimable territory
  // the size of a car, and a match near one is a coin flip between it and
  // whatever it abuts. If a regenerated network suddenly carries many more,
  // that is worth noticing before it shows up as points on the wrong street.
  const tiny = SEGMENTS.filter((s) => lengthM(s) < 10);
  assert.ok(
    tiny.length <= 25,
    `${tiny.length} segments under 10 m; the join cannot separate these`,
  );
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
