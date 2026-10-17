/**
 * The scoring rules, server side.
 *
 * This is a port of app/src/main/java/com/trashtalk/domain/ScoreEngine.kt and
 * it has to stay a faithful one. The client still runs the Kotlin version to
 * show a preview the instant somebody submits -- a cleanup that scores in
 * silence while a function runs feels broken -- so the two implementations are
 * both live, and any drift between them shows up to users as a number that
 * changes after the celebration.
 *
 * Hence `scoring.test.ts`, which pins the arithmetic against values computed
 * from the Kotlin. If a rule is retuned, it is retuned in both places and the
 * test is updated deliberately.
 *
 * The client's number is a preview. This one is the award.
 */

/** Materials, with the harm weighting that drives everything else. */
export const LITTER = {
  PLASTIC_BAG: { grams: 5.5, harm: 1.0, singleUsePlastic: true },
  FILM_WRAPPER: { grams: 2.5, harm: 0.95, singleUsePlastic: true },
  FOAM: { grams: 3.0, harm: 0.95, singleUsePlastic: true },
  CIGARETTE: { grams: 0.3, harm: 0.9, singleUsePlastic: false },
  FOOD_PACKAGING: { grams: 12.0, harm: 0.85, singleUsePlastic: true },
  PLASTIC_BOTTLE: { grams: 20.0, harm: 0.8, singleUsePlastic: true },
  GLASS: { grams: 180.0, harm: 0.3, singleUsePlastic: false },
  ALUMINUM_CAN: { grams: 14.0, harm: 0.25, singleUsePlastic: false },
  PAPER: { grams: 18.0, harm: 0.15, singleUsePlastic: false },
  OTHER: { grams: 25.0, harm: 0.5, singleUsePlastic: false },
} as const;

export type LitterType = keyof typeof LITTER;

export function isLitterType(value: string): value is LitterType {
  return Object.prototype.hasOwnProperty.call(LITTER, value);
}

/**
 * Points for one piece.
 *
 * Harm, not mass: a plastic bag beats a newspaper that weighs three times as
 * much, because the bag is what ends up inside an animal. Truncated rather
 * than rounded, matching Kotlin's `.toInt()`.
 */
export function pointsFor(type: LitterType): number {
  return Math.trunc(10 + LITTER[type].harm * 15);
}

const COVERAGE_BONUS = 50;
const MAX_STREAK_DAYS = 30;

/** Dirtier blocks are worth more, up to 1.5x on a segment at full index. */
export function difficultyMultiplier(litterIndex: number): number {
  return 1 + litterIndex * 0.5;
}

/** Two percent a day, capped at thirty days. */
export function streakMultiplier(streakDays: number): number {
  return 1 + Math.min(streakDays, MAX_STREAK_DAYS) * 0.02;
}

export interface Counts {
  [type: string]: number;
}

export interface Breakdown {
  basePoints: number;
  difficultyBonus: number;
  streakBonus: number;
  coverageBonus: number;
  harmBonus: number;
  total: number;
  difficultyMultiplier: number;
  streakMultiplier: number;
  lines: Array<[string, number]>;
}

/**
 * Kotlin's `roundToInt`, which rounds half away from zero.
 *
 * JavaScript's `Math.round` rounds half toward positive infinity, so -0.5
 * becomes -0 there and -1 in Kotlin. Every input here is non-negative, so the
 * two agree -- but relying on that silently is how a port drifts, and the
 * difference would appear as an award that is one point off.
 */
function roundToInt(value: number): number {
  return Math.sign(value) * Math.round(Math.abs(value));
}

export function pieces(counts: Counts): number {
  return Object.values(counts).reduce((sum, n) => sum + n, 0);
}

export function score(args: {
  counts: Counts;
  litterIndex: number;
  streakDays: number;
  isFirstSurvey: boolean;
}): Breakdown {
  const { counts, litterIndex, streakDays, isFirstSurvey } = args;

  let base = 0;
  let singleUse = 0;
  for (const [type, n] of Object.entries(counts)) {
    if (!isLitterType(type)) continue;
    base += pointsFor(type) * n;
    if (LITTER[type].singleUsePlastic) singleUse += n;
  }

  const difficulty = difficultyMultiplier(litterIndex);
  const streak = streakMultiplier(streakDays);

  const difficultyBonus = roundToInt(base * (difficulty - 1));
  const streakBonus = roundToInt((base + difficultyBonus) * (streak - 1));
  const coverageBonus = isFirstSurvey ? COVERAGE_BONUS : 0;

  // An extra nudge toward the single-use plastics the project is actually about.
  const harmBonus = singleUse * 2;

  const total = base + difficultyBonus + streakBonus + coverageBonus + harmBonus;

  const lines: Array<[string, number]> = [[`${pieces(counts)} pieces collected`, base]];
  if (difficultyBonus > 0) {
    lines.push([`Segment difficulty (litter index ${Math.round(litterIndex * 100)}%)`, difficultyBonus]);
  }
  if (streakBonus > 0) lines.push([`${streakDays}-day streak`, streakBonus]);
  if (coverageBonus > 0) lines.push(['First survey of this segment', coverageBonus]);
  if (harmBonus > 0) lines.push(['Single-use plastic removed', harmBonus]);

  return {
    basePoints: base,
    difficultyBonus,
    streakBonus,
    coverageBonus,
    harmBonus,
    total,
    difficultyMultiplier: difficulty,
    streakMultiplier: streak,
    lines,
  };
}
