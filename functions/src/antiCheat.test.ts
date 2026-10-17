import assert from 'node:assert/strict';
import { test } from 'node:test';

import { Fix, validateCoverage, validateTrace } from './antiCheat';
import { TUNING } from './tuning';

const START = 1_800_000_000_000;

/**
 * A believable walk: [metres] covered over [seconds], with GPS noise.
 *
 * Noise matters. A synthetic track of perfectly spaced points would pass
 * checks that a real one fails, which would make these tests agree with
 * nothing.
 */
function walk(metres: number, seconds: number, over: Partial<Fix> = {}): Fix[] {
  const steps = 30;
  const fixes: Fix[] = [];
  // A degree of longitude is 111.32 km at the equator but only 96.9 km at
  // 29.5N. Using the equatorial figure made every generated walk 13% shorter
  // than asked for, which is how this helper first "failed" a perfectly good
  // 4 km/h walk.
  const metresPerDegreeLng = 111_320 * Math.cos((29.5 * Math.PI) / 180);
  const perStep = metres / steps / metresPerDegreeLng;
  for (let i = 0; i <= steps; i++) {
    fixes.push({
      lat: 29.5 + (i % 2) * 0.000002,
      lng: -98.45 + i * perStep,
      at: START + (i * seconds * 1000) / steps,
      accuracyMetres: 8,
      ...over,
    });
  }
  return fixes;
}

test('an ordinary walk passes', () => {
  // 1 km in 15 minutes: 4 km/h, which is what walking looks like.
  const v = validateTrace(walk(1000, 900));
  assert.equal(v.ok, true, v.rejection?.reason);
  assert.ok(Math.abs(v.distanceMetres - 1000) < 50, `got ${v.distanceMetres} m`);
  assert.equal(v.durationSeconds, 900);
});

test('a slow walk with long stops passes', () => {
  // Stopping constantly to pick things up is the activity, not an anomaly.
  const v = validateTrace(walk(400, 3600));
  assert.equal(v.ok, true, v.rejection?.reason);
});

test('driving is rejected', () => {
  // 10 km in 15 minutes: 40 km/h.
  const v = validateTrace(walk(10_000, 900));
  assert.equal(v.ok, false);
  assert.equal(v.rejection?.code, 'TOO_FAST');
  assert.match(v.rejection!.reason, /km\/h/);
});

test('a GPS teleport is rejected and says how far', () => {
  const fixes = walk(500, 600);
  fixes[15]!.lng += 0.02; // about 1.9 km sideways
  const v = validateTrace(fixes);
  assert.equal(v.ok, false);
  assert.equal(v.rejection?.code, 'IMPOSSIBLE_JUMP');
  assert.match(v.rejection!.reason, /GPS jumped \d+ m/);
});

test('fixes too vague to place are rejected', () => {
  const v = validateTrace(walk(500, 600, { accuracyMetres: 120 }));
  assert.equal(v.ok, false);
  assert.equal(v.rejection?.code, 'TOO_VAGUE');
});

test('a mock location is rejected', () => {
  const fixes = walk(500, 600);
  fixes[10]!.isMock = true;
  const v = validateTrace(fixes);
  assert.equal(v.ok, false);
  assert.equal(v.rejection?.code, 'MOCK_LOCATION');
});

test('time running backwards is rejected', () => {
  const fixes = walk(500, 600);
  fixes[20]!.at = fixes[19]!.at - 60_000;
  const v = validateTrace(fixes);
  assert.equal(v.ok, false);
  assert.equal(v.rejection?.code, 'TIME_WENT_BACKWARDS');
});

test('a walk too short to be a walk is rejected', () => {
  assert.equal(validateTrace(walk(10, 5)).rejection?.code, 'TOO_SHORT');
});

test('no track and a short track are told apart', () => {
  // They used to share one message, which sent somebody whose location was
  // never reaching the app off to walk further.
  assert.equal(validateTrace([]).rejection?.code, 'NO_TRACK');
  // One fix is a position, not a route. walk() always emits 31, so take one.
  assert.equal(validateTrace(walk(100, 60).slice(0, 1)).rejection?.code, 'NO_TRACK');

  const short = validateTrace(walk(10, 5)).rejection;
  assert.equal(short?.code, 'TOO_SHORT');
  // The message should say how short, and how short is long enough.
  assert.match(short?.reason ?? '', /\d+ seconds/);
  assert.match(short?.reason ?? '', /at least 30/);
});

test('GPS drift while standing still does not read as sprinting', () => {
  // The reason speed is measured over the whole walk rather than between
  // consecutive fixes: a couple of metres of wander between two fixes a
  // second apart implies a sprint, and rejecting on that would throw out
  // honest walks for standing beside a building.
  //
  // Modelled as a slow correlated drift, which is how GPS actually behaves.
  // Independent per-fix jitter is not: it random-walks hundreds of metres in
  // two minutes and would imply 16 km/h standing still. The recorder's 5 m
  // distance gate means the server never sees the raw stream either.
  const fixes: Fix[] = [];
  let driftLat = 0;
  let driftLng = 0;
  for (let i = 0; i <= 120; i++) {
    driftLat += (Math.random() - 0.5) * 0.0000015;
    driftLng += (Math.random() - 0.5) * 0.0000015;
    fixes.push({
      lat: 29.5 + driftLat,
      lng: -98.45 + driftLng,
      at: START + i * 1000,
      accuracyMetres: 10,
    });
  }
  const v = validateTrace(fixes);
  assert.equal(v.ok, true, v.rejection?.reason);
});

test('every rejection reads as plain language, never a code', () => {
  const cases = [
    validateTrace(walk(10_000, 900)),
    validateTrace(walk(500, 600, { accuracyMetres: 120 })),
    validateTrace([]),
  ];
  for (const v of cases) {
    const reason = v.rejection!.reason;
    assert.match(reason, /^[A-Z]/, `not a sentence: ${reason}`);
    assert.match(reason, /\.$/, `no full stop: ${reason}`);
    assert.doesNotMatch(reason, /_|[A-Z]{4,}/, `leaked a code: ${reason}`);
  }
});

// ----------------------------------------------------------- coverage

test('covering blocks faster than they can be walked is rejected', () => {
  const r = validateCoverage(60, 300); // 60 blocks in five minutes
  assert.ok(r, 'should have been rejected');
  assert.equal(r!.code, 'SEGMENTS_TOO_FAST');
});

test('a realistic number of blocks passes', () => {
  // Eight blocks in an hour is a thorough, unhurried walk.
  assert.equal(validateCoverage(8, 3600), null);
});

test('the coverage cap matches the tuning, not a hard-coded number', () => {
  const minutes = 10;
  const atCap = TUNING.trace.maxNewSegmentsPerMinute * minutes;
  assert.equal(validateCoverage(atCap, minutes * 60), null, 'at the cap should pass');
  assert.ok(validateCoverage(atCap + 10, minutes * 60), 'past it should fail');
});
