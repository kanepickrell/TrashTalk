import assert from 'node:assert/strict';
import { test } from 'node:test';

import {
  applyCleanup,
  ContestState,
  daysUntilLapse,
  decayed,
  hasLapsed,
  SegmentState,
  strengthFor,
  UNOWNED,
} from './contest';
import { TUNING } from './tuning';

const DAY = 86_400_000;
const NOW = 1_800_000_000_000;

const fresh = (): ContestState => ({ score: 0, siegeProgress: 0, lastActivityAt: 0 });

function held(by: string, score: number, strength = 1, teamId: string | null = null): SegmentState {
  return {
    ownerId: by,
    ownerTeamId: teamId,
    ownerScore: score,
    strength,
    lastScoredAt: NOW,
  };
}

function cleanup(over: Partial<Parameters<typeof applyCleanup>[0]['cleanup']> = {}) {
  return {
    userId: 'rival',
    teamId: null,
    value: 100,
    at: NOW,
    countsForTerritory: true,
    cleanupsHere: 1,
    ...over,
  };
}

// --------------------------------------------------------------- decay

test('a score halves over one half-life', () => {
  const after = decayed(100, NOW, NOW + TUNING.contest.decayHalfLifeDays * DAY);
  assert.ok(Math.abs(after - 50) < 0.001, `got ${after}`);
});

test('decay is continuous, not stepped', () => {
  // Nobody's holding should evaporate overnight on a boundary they cannot see.
  const a = decayed(100, NOW, NOW + 6.9 * DAY);
  const b = decayed(100, NOW, NOW + 7.1 * DAY);
  assert.ok(a > b, 'should be monotonically decreasing');
  assert.ok(a - b < 2, `a day either side should be a small change, was ${a - b}`);
});

// ------------------------------------------------------------ claiming

test('a verified cleanup claims an unowned block at strength 1', () => {
  const out = applyCleanup({
    segment: { ...UNOWNED },
    challenger: fresh(),
    cleanup: cleanup(),
    now: NOW,
  });
  assert.equal(out.claimed, true);
  assert.equal(out.segment.ownerId, 'rival');
  assert.equal(out.segment.strength, 1);
});

test('an unverified cleanup scores but claims nothing', () => {
  // This is the whole point of trust levels: the street still gets cleaned,
  // the points are still earned, but territory needs evidence.
  const out = applyCleanup({
    segment: { ...UNOWNED },
    challenger: fresh(),
    cleanup: cleanup({ countsForTerritory: false }),
    now: NOW,
  });
  assert.equal(out.claimed, false);
  assert.equal(out.segment.ownerId, null);
});

// ------------------------------------------------------------- holding

test('the owner cleaning their own block gains strength, not a siege', () => {
  const out = applyCleanup({
    segment: held('owner', 100),
    challenger: fresh(),
    cleanup: cleanup({ userId: 'owner', cleanupsHere: 6 }),
    now: NOW,
  });
  assert.equal(out.flipped, false);
  assert.equal(out.segment.ownerId, 'owner');
  assert.equal(out.segment.strength, strengthFor(6));
  assert.ok(out.segment.ownerScore > 100);
});

test('a crewmate reinforces rather than besieges', () => {
  const out = applyCleanup({
    segment: held('owner', 100, 2, 'crew_a'),
    challenger: fresh(),
    cleanup: cleanup({ userId: 'mate', teamId: 'crew_a' }),
    now: NOW,
  });
  assert.equal(out.flipped, false);
  assert.equal(out.segment.ownerId, 'owner');
  assert.ok(out.segment.ownerScore > 100, 'the crew should have added to it');
});

// -------------------------------------------------------------- sieges

test('a flip needs the siege AND the margin, not either alone', () => {
  // One enormous haul, way past the margin, against a strength-3 holder.
  const out = applyCleanup({
    segment: held('owner', 100, 3),
    challenger: fresh(),
    cleanup: cleanup({ value: 10_000 }),
    now: NOW,
  });
  assert.equal(out.flipped, false, 'margin alone must not flip a defended block');
  assert.equal(out.challenger.siegeProgress, 1);
});

test('siege progress persists between visits and eventually flips', () => {
  let segment = held('owner', 100, 3);
  let challenger = fresh();
  let flips = 0;

  for (let visit = 0; visit < 3; visit++) {
    const out = applyCleanup({
      segment,
      challenger,
      cleanup: cleanup({ value: 60 }),
      now: NOW + visit * DAY,
    });
    segment = out.segment;
    challenger = out.challenger;
    if (out.flipped) flips++;
  }

  assert.equal(flips, 1, 'should flip exactly once, on the third visit');
  assert.equal(segment.ownerId, 'rival');
  assert.equal(segment.strength, 1, 'a newly taken block starts weak');
});

test('a siege that never out-scores the owner never flips', () => {
  let segment = held('owner', 1000, 2);
  let challenger = fresh();

  for (let visit = 0; visit < 10; visit++) {
    const out = applyCleanup({
      segment,
      challenger,
      cleanup: cleanup({ value: 10 }),
      now: NOW + visit * 60_000,
    });
    segment = out.segment;
    challenger = out.challenger;
    assert.equal(out.flipped, false, `flipped on visit ${visit} without the margin`);
  }
  assert.equal(segment.ownerId, 'owner');
});

test('the margin is a clear margin, not a tie', () => {
  // Exactly equal scores must not flip, or ownership flickers on rounding.
  const out = applyCleanup({
    segment: held('owner', 100, 1),
    challenger: { score: 0, siegeProgress: 1, lastActivityAt: NOW },
    cleanup: cleanup({ value: 100 }),
    now: NOW,
  });
  assert.equal(out.flipped, false, 'an exact tie must not take the block');
});

// ------------------------------------------------------------ lapsing

test('an abandoned holding lapses, a tended one does not', () => {
  const tended = held('owner', 500);
  assert.equal(hasLapsed(tended, NOW + DAY), false);

  // Far enough out that 500 has decayed below the floor.
  const longAfter = NOW + 365 * DAY;
  assert.equal(hasLapsed(tended, longAfter), true);
});

test('the lapse countdown agrees with the lapse check', () => {
  const segment = held('owner', 500);
  const days = daysUntilLapse(segment, NOW);
  assert.ok(days > 0, 'a fresh holding should have time left');

  const justBefore = NOW + (days - 0.5) * DAY;
  const justAfter = NOW + (days + 0.5) * DAY;
  assert.equal(hasLapsed(segment, justBefore), false, 'lapsed early');
  assert.equal(hasLapsed(segment, justAfter), true, 'did not lapse on time');
});

test('an unowned segment never lapses and has no countdown', () => {
  assert.equal(hasLapsed(UNOWNED, NOW + 1000 * DAY), false);
  assert.equal(daysUntilLapse(UNOWNED, NOW), 0);
});

// ------------------------------------------------------------ strength

test('strength climbs with repeat cleanups and caps', () => {
  assert.equal(strengthFor(0), 1);
  assert.equal(strengthFor(TUNING.contest.cleanupsPerStrength), 2);
  assert.equal(strengthFor(10_000), TUNING.contest.maxStrength);
});
