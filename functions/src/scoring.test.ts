/**
 * Pins the TypeScript scoring against the Kotlin it was ported from.
 *
 * The fixtures are not hand-written. They are emitted by
 * ScoringParityFixtures.kt running the real ScoreEngine, because a
 * transcription slip in a hand-copied expected value reads exactly like
 * agreement and would hide the drift this test exists to catch.
 *
 * Regenerate after retuning any rule:
 *   ./gradlew :app:testDebugUnitTest --tests '*ScoringParityFixtures*'
 *
 *   node --test lib/scoring.test.js
 */

import assert from 'node:assert/strict';
import { test } from 'node:test';

import fixtures from './scoring.fixtures.json';
import { difficultyMultiplier, pointsFor, score, streakMultiplier } from './scoring';

interface Fixture {
  name: string;
  counts: Record<string, number>;
  litterIndex: number;
  streakDays: number;
  isFirstSurvey: boolean;
  expected: {
    basePoints: number;
    difficultyBonus: number;
    streakBonus: number;
    coverageBonus: number;
    harmBonus: number;
    total: number;
  };
}

// Through `unknown`: TypeScript infers a very precise literal type from the
// JSON (every absent material as `undefined`), which is not comparable to the
// loose Record the code actually consumes.
const cases = (fixtures as unknown as { cases: Fixture[] }).cases;

test('fixtures were actually generated', () => {
  assert.ok(cases.length >= 5, 'expected the Kotlin fixture set, got ' + cases.length);
});

for (const c of cases) {
  test(`parity with ScoreEngine.kt: ${c.name}`, () => {
    const got = score({
      counts: c.counts,
      litterIndex: c.litterIndex,
      streakDays: c.streakDays,
      isFirstSurvey: c.isFirstSurvey,
    });

    for (const key of Object.keys(c.expected) as Array<keyof Fixture['expected']>) {
      assert.equal(
        got[key],
        c.expected[key],
        `${c.name}: ${key} was ${got[key]}, Kotlin says ${c.expected[key]}`,
      );
    }
  });
}

test('the breakdown sums to the total it shows the user', () => {
  for (const c of cases) {
    const b = score({
      counts: c.counts,
      litterIndex: c.litterIndex,
      streakDays: c.streakDays,
      isFirstSurvey: c.isFirstSurvey,
    });
    const parts =
      b.basePoints + b.difficultyBonus + b.streakBonus + b.coverageBonus + b.harmBonus;
    assert.equal(parts, b.total, `${c.name}: parts ${parts} != total ${b.total}`);
  }
});

test('harm outranks mass', () => {
  // A plastic bag is 5.5 g against paper at 18 g. The bag still wins.
  assert.ok(pointsFor('PLASTIC_BAG') > pointsFor('PAPER'));
});

test('the streak multiplier caps at thirty days', () => {
  assert.equal(streakMultiplier(30), streakMultiplier(99));
  assert.ok(streakMultiplier(10) < streakMultiplier(30));
});

test('difficulty runs from 1x to 1.5x', () => {
  assert.equal(difficultyMultiplier(0), 1);
  assert.equal(difficultyMultiplier(1), 1.5);
});

test('unknown litter types are ignored, not guessed at', () => {
  // A newer client could send a material this deployment has never heard of.
  // Scoring it as something else would invent an award.
  const known = score({ counts: { CIGARETTE: 2 }, litterIndex: 0, streakDays: 0, isFirstSurvey: false });
  const withJunk = score({
    counts: { CIGARETTE: 2, SPACE_DEBRIS: 99 },
    litterIndex: 0,
    streakDays: 0,
    isFirstSurvey: false,
  });
  assert.equal(withJunk.total, known.total);
});
