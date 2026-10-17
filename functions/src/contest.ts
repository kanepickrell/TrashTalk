/**
 * Who holds a block, and what it takes to take it.
 *
 * Territory here is won by cleaning, not by arriving. That is the whole
 * difference from the running games this borrows its shape from: in those,
 * passing through is the act. Here passing through is nothing, and the score
 * on a segment is the harm actually removed from it.
 *
 * Three rules do the work:
 *
 *   - Scores decay, so holding a block means coming back to it.
 *   - A challenger needs a clear margin, not a tie, so ownership does not
 *     flicker on rounding.
 *   - A siege has to be ground through, and its progress persists, so taking
 *     a well-held block is a campaign rather than a lucky afternoon.
 */

import { TUNING } from './tuning';

export interface SegmentState {
  ownerId: string | null;
  ownerTeamId: string | null;
  ownerScore: number;
  strength: number;
  /** Epoch millis of the owner's last scoring cleanup. */
  lastScoredAt: number;
}

export interface ContestState {
  /** The challenger's accumulated score on this segment. */
  score: number;
  /** Siege progress toward the holder's strength. */
  siegeProgress: number;
  lastActivityAt: number;
}

export const UNOWNED: SegmentState = {
  ownerId: null,
  ownerTeamId: null,
  ownerScore: 0,
  strength: 0,
  lastScoredAt: 0,
};

/**
 * What a score is worth now, having been earned at [earnedAt].
 *
 * Exponential decay on a half-life: a cleanup is worth half as much after a
 * fortnight, a quarter after a month. Continuous rather than stepped, so
 * nobody's holding evaporates overnight on a boundary they could not see.
 */
export function decayed(score: number, earnedAt: number, now: number): number {
  if (score <= 0 || earnedAt <= 0) return 0;
  const days = (now - earnedAt) / 86_400_000;
  if (days <= 0) return score;
  return score * Math.pow(0.5, days / TUNING.contest.decayHalfLifeDays);
}

/** Strength earned by repeatedly cleaning a block you already hold. */
export function strengthFor(cleanupsByOwner: number): number {
  const earned = Math.floor(cleanupsByOwner / TUNING.contest.cleanupsPerStrength);
  return Math.max(1, Math.min(TUNING.contest.maxStrength, earned + 1));
}

export interface CleanupInput {
  userId: string;
  teamId: string | null;
  /** Points this cleanup earned, which is also what it contributes here. */
  value: number;
  at: number;
  /**
   * Whether the evidence was good enough for this to move territory.
   *
   * Unverified cleanups still score points and still clean the street. They
   * just do not take ground, because ground is the competitive part and the
   * competitive part needs evidence.
   */
  countsForTerritory: boolean;
  /** Cleanups this user has now logged on this segment, including this one. */
  cleanupsHere: number;
}

export interface ContestOutcome {
  segment: SegmentState;
  challenger: ContestState;
  /** Set when ownership changed hands. */
  flipped: boolean;
  /** Set when an unowned segment was claimed. */
  claimed: boolean;
  /** Progress toward a flip, for the UI to show: 0..1. */
  siegeFraction: number;
}

/**
 * Applies one cleanup to a segment's contest state.
 *
 * Pure, so it can be tested without a database and run inside a transaction
 * without surprises.
 */
export function applyCleanup(args: {
  segment: SegmentState;
  challenger: ContestState;
  cleanup: CleanupInput;
  now: number;
}): ContestOutcome {
  const { cleanup, now } = args;
  const segment = { ...args.segment };
  const challenger = { ...args.challenger };

  // Everyone's standing is measured as of now, not as of whenever it was
  // last written.
  const ownerScoreNow = decayed(segment.ownerScore, segment.lastScoredAt, now);
  const challengerScoreNow = decayed(challenger.score, challenger.lastActivityAt, now);

  const added = cleanup.countsForTerritory ? cleanup.value : 0;
  challenger.score = challengerScoreNow + added;
  challenger.lastActivityAt = now;

  // --- the owner cleaning their own block ------------------------------
  if (segment.ownerId === cleanup.userId) {
    segment.ownerScore = ownerScoreNow + added;
    segment.lastScoredAt = now;
    segment.strength = strengthFor(cleanup.cleanupsHere);
    return {
      segment,
      challenger,
      flipped: false,
      claimed: false,
      siegeFraction: 0,
    };
  }

  // --- a crewmate reinforcing ------------------------------------------
  // A crew holds a block together; one member's work should not have to
  // besiege another's.
  const sameCrew =
    segment.ownerTeamId !== null &&
    cleanup.teamId !== null &&
    segment.ownerTeamId === cleanup.teamId;

  if (sameCrew) {
    segment.ownerScore = ownerScoreNow + added;
    segment.lastScoredAt = now;
    return {
      segment,
      challenger,
      flipped: false,
      claimed: false,
      siegeFraction: 0,
    };
  }

  // --- nobody holds it, or the holder has faded away --------------------
  const abandoned = ownerScoreNow < TUNING.contest.abandonBelowScore;
  if (segment.ownerId === null || abandoned) {
    if (added <= 0) {
      // Unverified work on an unclaimed block: scores, takes nothing.
      return {
        segment,
        challenger,
        flipped: false,
        claimed: false,
        siegeFraction: 0,
      };
    }
    return {
      segment: {
        ownerId: cleanup.userId,
        ownerTeamId: cleanup.teamId,
        ownerScore: added,
        strength: 1,
        lastScoredAt: now,
      },
      challenger: { ...challenger, siegeProgress: 0 },
      flipped: false,
      claimed: true,
      siegeFraction: 1,
    };
  }

  // --- a rival working a held block -------------------------------------
  if (added > 0) {
    challenger.siegeProgress = Math.min(
      segment.strength,
      challenger.siegeProgress + TUNING.contest.siegePerCleanup,
    );
  }

  const siegeComplete = challenger.siegeProgress >= segment.strength;
  const hasMargin = challenger.score > ownerScoreNow * TUNING.contest.flipMargin;

  // Both, not either. The margin alone would let one enormous haul take a
  // block somebody has tended for months; the siege alone would let a
  // persistent visitor take it without ever doing more work than the holder.
  if (siegeComplete && hasMargin) {
    return {
      segment: {
        ownerId: cleanup.userId,
        ownerTeamId: cleanup.teamId,
        ownerScore: challenger.score,
        strength: 1,
        lastScoredAt: now,
      },
      challenger: { ...challenger, siegeProgress: 0 },
      flipped: true,
      claimed: false,
      siegeFraction: 1,
    };
  }

  // Held, for now. Keep the owner's decayed score so it does not spring back
  // to whatever it was when last written.
  segment.ownerScore = ownerScoreNow;
  return {
    segment,
    challenger,
    flipped: false,
    claimed: false,
    siegeFraction:
      segment.strength > 0 ? challenger.siegeProgress / segment.strength : 0,
  };
}

/**
 * Whether an unattended segment should revert to unowned.
 *
 * Run nightly. Someone who cleaned a block once a year ago is not its
 * guardian, and a map full of ghost ownership tells nobody anything.
 */
export function hasLapsed(segment: SegmentState, now: number): boolean {
  if (segment.ownerId === null) return false;
  return decayed(segment.ownerScore, segment.lastScoredAt, now) <
    TUNING.contest.abandonBelowScore;
}

/** Days until an unattended holding lapses, for the countdown in the UI. */
export function daysUntilLapse(segment: SegmentState, now: number): number {
  if (segment.ownerId === null) return 0;
  const current = decayed(segment.ownerScore, segment.lastScoredAt, now);
  if (current < TUNING.contest.abandonBelowScore) return 0;
  const ratio = TUNING.contest.abandonBelowScore / current;
  return (Math.log(ratio) / Math.log(0.5)) * TUNING.contest.decayHalfLifeDays;
}
