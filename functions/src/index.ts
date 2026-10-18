/**
 * Server-side scoring.
 *
 * The client used to compute points and write them, along with the segment's
 * new density and its own running total. That made every score a claim the
 * client made about itself, which is fine until anything is competitive.
 *
 * Now the client writes one document — `submissions/{id}` — describing what it
 * did, and this decides what that was worth. The client keeps its local
 * ScoreEngine as an immediate preview, because a submission that scores in
 * silence reads as broken; the number it shows is a guess, and `results/{id}`
 * is the answer.
 *
 * Everything happens in one transaction keyed on the submission id, so a
 * client that retries — which the offline queue does by design — cannot score
 * the same walk twice.
 */

import { getApp } from 'firebase-admin/app';
import { initializeApp } from 'firebase-admin/app';
import { FieldValue, getFirestore, Transaction } from 'firebase-admin/firestore';
import { onDocumentCreated } from 'firebase-functions/v2/firestore';
import { onSchedule } from 'firebase-functions/v2/scheduler';
import { logger } from 'firebase-functions';

import { Fix, validateCoverage, validateTrace } from './antiCheat';
import { applyCleanup, ContestState, hasLapsed, SegmentState, UNOWNED } from './contest';
import { Counts, pieces, score } from './scoring';
import { TUNING } from './tuning';

try {
  getApp();
} catch {
  initializeApp();
}

const db = getFirestore();

const SUBMISSIONS = 'submissions';
const RESULTS = 'results';
const USERS = 'users';
const COLLECTIONS = 'collections';
const SEGMENT_STATE = 'segment_state';
const SEGMENT_CONTEST = 'segment_contest';

/** One street's worth of a submission. */
interface SubmittedSegment {
  segmentId: string;
  segmentName?: string;
  counts: Counts;
  lat: number;
  lng: number;
  minutes?: number;
}

interface Submission {
  userId: string;
  walkId?: string;
  segments: SubmittedSegment[];
  /** The recorded track, when there was one. A quick log has none. */
  track?: Fix[];
  /**
   * The recorder's own clock for the walk. Only ever lengthens how long the
   * walk is judged to have taken, and only for the minimum-duration floor --
   * see validateTrace.
   */
  walkStartedAt?: number;
  walkEndedAt?: number;
  evidencePath?: string | null;
  /** Storage paths of per-tag spot checks taken during the walk. */
  spotChecks?: string[];
  createdAt?: number;
}

interface SegmentAward {
  segmentId: string;
  segmentName: string;
  points: number;
  pieces: number;
  wasFirstSurvey: boolean;
  claimed: boolean;
  flipped: boolean;
  siegeFraction: number;
}

/**
 * Scores a submission.
 *
 * Note what is NOT trusted from the client: points, ownership, the segment's
 * density, and whether anything was a first survey. All of those are read or
 * derived here. What is trusted is the shape of the claim — which segments,
 * which materials, how many — and that is what evidence and anti-cheat exist
 * to check.
 */
export const scoreSubmission = onDocumentCreated(
  {
    document: `${SUBMISSIONS}/{submissionId}`,
    region: 'us-central1',
    memory: '512MiB',
  },
  async (event) => {
    const submissionId = event.params.submissionId;
    const submission = event.data?.data() as Submission | undefined;
    if (!submission) return;

    const resultRef = db.collection(RESULTS).doc(submissionId);

    try {
      const result = await db.runTransaction(async (tx) =>
        processSubmission(tx, submissionId, submission),
      );
      // Trust and claims are logged, not just points, because "did the
      // evidence count" is the question you actually have after a walk and
      // the points alone cannot answer it. Reading it back out of Firestore
      // to find out is a poor substitute for the function saying so.
      const scored = result as {
        pointsAwarded: number;
        trustLevel?: string;
        awards?: { claimed: boolean }[];
      };
      logger.info('scored', {
        submissionId,
        points: scored.pointsAwarded,
        trustLevel: scored.trustLevel,
        claimed: scored.awards?.filter((a) => a.claimed).length ?? 0,
        segments: scored.awards?.length ?? 0,
      });
    } catch (err) {
      // A failure here must still produce a result document, or the client
      // waits forever on an answer that is never coming.
      logger.error('scoring failed', { submissionId, err });
      await resultRef.set(
        {
          submissionId,
          status: 'ERROR',
          reason: 'Something went wrong scoring this cleanup. It has been kept.',
          scoredAt: FieldValue.serverTimestamp(),
        },
        { merge: true },
      );
    }
  },
);

async function processSubmission(
  tx: Transaction,
  submissionId: string,
  submission: Submission,
) {
  const now = Date.now();
  const resultRef = db.collection(RESULTS).doc(submissionId);

  // --- idempotency -------------------------------------------------------
  // The queue retries anything it could not distinguish from a failure, so
  // this has to be safe to run twice. Every read happens before any write,
  // which Firestore requires.
  const existing = await tx.get(resultRef);
  if (existing.exists) {
    logger.info('already scored, ignoring', { submissionId });
    return existing.data() as { pointsAwarded: number };
  }

  const userRef = db.collection(USERS).doc(submission.userId);
  const userSnap = await tx.get(userRef);
  const user = userSnap.data() ?? {};
  const streakDays = (user.streakDays as number) ?? 0;
  const teamId = (user.teamId as string | null) ?? null;

  const segments = submission.segments ?? [];

  // --- anti-cheat --------------------------------------------------------
  // Two fixes, not one: a single point is a position, not a route, and there
  // is nothing in it to validate. Gating on `> 0` meant one fix was treated
  // worse than none -- a quick log with no track skipped these checks and
  // scored, while a recorded walk that produced a single fix was rejected
  // outright, taking its pickups with it. Submitting less data must not beat
  // submitting a little.
  //
  // A stationary cleanup produces exactly that single fix, because the
  // recorder stores a point only once you have moved five metres, which is
  // the right way to stop a still phone writing its own noise down as
  // distance. Standing at one corner filling a bag is real work and the
  // pickups carry their own positions, so it is scored on those.
  //
  // This opens no door that was not already open: a submission with no track
  // at all has always taken this path.
  const track = submission.track ?? [];
  if (track.length > 1) {
    const elapsedSeconds =
      submission.walkStartedAt && submission.walkEndedAt
        ? (submission.walkEndedAt - submission.walkStartedAt) / 1000
        : undefined;
    const verdict = validateTrace(track, elapsedSeconds);
    if (!verdict.ok) {
      return reject(tx, resultRef, submissionId, verdict.rejection!.reason);
    }
    const coverage = validateCoverage(segments.length, verdict.durationSeconds);
    if (coverage) {
      return reject(tx, resultRef, submissionId, coverage.reason);
    }
  }

  // --- evidence ----------------------------------------------------------
  // Until 4E.3 computes real trust levels, a submission with a photo counts
  // for territory and one without does not. That is the honest version of
  // the rule: unverified work scores points and cleans the street, but does
  // not take ground.
  //
  // A spot check counts the same as the haul shot, and for a better reason:
  // the haul photo shows a full bag, which says the work happened somewhere,
  // while a spot check shows one piece in place at a recorded position and
  // time. Either is enough to take ground; neither is required to score.
  //
  // Refusing a prompt therefore costs territory, never points. Somebody who
  // picks up forty pieces and photographs none of them has still cleaned the
  // street and is still credited for it -- they just cannot claim it.
  const spotChecks = submission.spotChecks ?? [];
  const hasEvidence = Boolean(submission.evidencePath) || spotChecks.length > 0;
  const trustLevel = hasEvidence ? 'partial' : 'unverified';

  // --- read every segment's current state before writing anything --------
  const stateRefs = segments.map((s) => db.collection(SEGMENT_STATE).doc(s.segmentId));
  const contestRefs = segments.map((s) =>
    db.collection(SEGMENT_CONTEST).doc(`${s.segmentId}_${submission.userId}`),
  );
  const stateSnaps = await Promise.all(stateRefs.map((r) => tx.get(r)));
  const contestSnaps = await Promise.all(contestRefs.map((r) => tx.get(r)));

  // --- score each segment ------------------------------------------------
  const awards: SegmentAward[] = [];
  let totalPoints = 0;

  for (let i = 0; i < segments.length; i++) {
    const submitted = segments[i]!;
    const stateSnap = stateSnaps[i]!;
    const contestSnap = contestSnaps[i]!;
    const state = stateSnap.data() ?? {};

    // The density and survey count come from the server's record, never from
    // the client -- they are what the difficulty and coverage bonuses are
    // computed from, so a client that could set them could set its own score.
    const litterIndex = (state.litterIndex as number) ?? 0;
    const surveyCount = (state.surveyCount as number) ?? 0;
    const wasFirstSurvey = surveyCount === 0;

    const breakdown = score({
      counts: submitted.counts,
      litterIndex,
      streakDays,
      isFirstSurvey: wasFirstSurvey,
    });
    totalPoints += breakdown.total;

    const segmentPieces = pieces(submitted.counts);

    // --- the contest ----------------------------------------------------
    const before: SegmentState = {
      ownerId: (state.ownerId as string | null) ?? null,
      ownerTeamId: (state.ownerTeamId as string | null) ?? null,
      ownerScore: (state.ownerScore as number) ?? 0,
      strength: (state.strength as number) ?? 0,
      lastScoredAt: (state.lastScoredAt as number) ?? 0,
    };
    const contestBefore: ContestState = {
      score: (contestSnap.data()?.score as number) ?? 0,
      siegeProgress: (contestSnap.data()?.siegeProgress as number) ?? 0,
      lastActivityAt: (contestSnap.data()?.lastActivityAt as number) ?? 0,
    };
    const cleanupsHere = ((contestSnap.data()?.cleanups as number) ?? 0) + 1;

    const outcome = applyCleanup({
      segment: before,
      challenger: contestBefore,
      cleanup: {
        userId: submission.userId,
        teamId,
        value: breakdown.total,
        at: now,
        countsForTerritory: hasEvidence,
        cleanupsHere,
      },
      now,
    });

    // --- writes ----------------------------------------------------------
    // Density falls in proportion to the share of the segment's estimated
    // load that was actually removed.
    const estimated = Math.max(1, (state.estimatedPieces as number) ?? 20);
    const cleared = Math.min(1, Math.max(0, segmentPieces / estimated));
    const nextIndex = Math.min(1, Math.max(0, litterIndex * (1 - 0.75 * cleared)));

    tx.set(
      stateRefs[i]!,
      {
        litterIndex: nextIndex,
        surveyCount: FieldValue.increment(1),
        cleanupCount: FieldValue.increment(1),
        piecesRemoved: FieldValue.increment(segmentPieces),
        lastSurveyedAt: now,
        ownerId: outcome.segment.ownerId,
        ownerTeamId: outcome.segment.ownerTeamId,
        ownerScore: outcome.segment.ownerScore,
        strength: outcome.segment.strength,
        lastScoredAt: outcome.segment.lastScoredAt,
        // Who holds it, in words, and since when. The map needs a name to
        // put on a block and a date to say how long it has been held, and
        // neither can be derived from an id the client has no way to look
        // up -- segment_state is readable, the users collection is not.
        ownerName:
          outcome.segment.ownerId === null
            ? null
            : outcome.segment.ownerId === submission.userId
              ? ((user.displayName as string) ?? 'Collector')
              : (state.ownerName as string | undefined) ?? null,
        ...(outcome.claimed || outcome.flipped
          ? { ownerSince: now }
          : outcome.segment.ownerId === null
            ? { ownerSince: null }
            : {}),
      },
      { merge: true },
    );

    tx.set(
      contestRefs[i]!,
      {
        segmentId: submitted.segmentId,
        userId: submission.userId,
        score: outcome.challenger.score,
        siegeProgress: outcome.challenger.siegeProgress,
        lastActivityAt: outcome.challenger.lastActivityAt,
        cleanups: cleanupsHere,
      },
      { merge: true },
    );

    // The observation itself, which is the dataset.
    tx.set(db.collection(COLLECTIONS).doc(`${submissionId}_${submitted.segmentId}`), {
      submissionId,
      userId: submission.userId,
      userName: (user.displayName as string) ?? 'Collector',
      teamId,
      segmentId: submitted.segmentId,
      segmentName: submitted.segmentName ?? '',
      lat: submitted.lat,
      lng: submitted.lng,
      counts: submitted.counts,
      minutes: submitted.minutes ?? 0,
      createdAt: now,
      points: breakdown.total,
      wasFirstSurvey,
      trustLevel,
      evidencePath: submission.evidencePath ?? null,
    });

    awards.push({
      segmentId: submitted.segmentId,
      segmentName: submitted.segmentName ?? '',
      points: breakdown.total,
      pieces: segmentPieces,
      wasFirstSurvey,
      claimed: outcome.claimed,
      flipped: outcome.flipped,
      siegeFraction: outcome.siegeFraction,
    });
  }

  // --- the user's running totals -----------------------------------------
  //
  // The streak is computed here rather than sent by the client. It feeds the
  // scoring multiplier, so a client that could set it could inflate every
  // future award by up to 1.6x -- which is a quieter and more durable cheat
  // than overstating a single haul.
  const nextStreak = streakAfter(
    (user.lastCollectionAt as number) ?? 0,
    streakDays,
    now,
  );

  tx.set(
    userRef,
    {
      totalPoints: FieldValue.increment(totalPoints),
      weeklyPoints: FieldValue.increment(totalPoints),
      streakDays: nextStreak,
      lastCollectionAt: now,
    },
    { merge: true },
  );

  const result = {
    submissionId,
    status: 'SCORED',
    pointsAwarded: totalPoints,
    trustLevel,
    awards,
    scoredAt: FieldValue.serverTimestamp(),
  };
  tx.set(resultRef, result);
  return result;
}

/**
 * The streak after a cleanup today.
 *
 * Counted in local days rather than 24-hour blocks: somebody who goes out at
 * 9am on Saturday and 7pm on Sunday has been out two days running, and telling
 * them otherwise over a 34-hour gap would be both wrong and infuriating.
 */
function streakAfter(lastAt: number, current: number, now: number): number {
  if (lastAt <= 0) return 1;
  const dayOf = (t: number) => Math.floor(t / 86_400_000);
  const gap = dayOf(now) - dayOf(lastAt);
  if (gap === 0) return Math.max(current, 1);
  if (gap === 1) return current + 1;
  return 1;
}

function reject(
  tx: Transaction,
  resultRef: FirebaseFirestore.DocumentReference,
  submissionId: string,
  reason: string,
) {
  logger.warn('rejected', { submissionId, reason });
  const result = {
    submissionId,
    status: 'REJECTED',
    pointsAwarded: 0,
    reason,
    scoredAt: FieldValue.serverTimestamp(),
  };
  tx.set(resultRef, result);
  return result;
}

/**
 * Returns abandoned blocks to nobody.
 *
 * Someone who cleaned a street once a year ago is not its guardian, and a map
 * full of ghost ownership tells a reader nothing about who is actually doing
 * the work.
 */
export const expireHoldings = onSchedule(
  { schedule: 'every day 03:00', timeZone: 'America/Chicago', region: 'us-central1' },
  async () => {
    const now = Date.now();
    const held = await db.collection(SEGMENT_STATE).where('ownerId', '!=', null).get();

    let lapsed = 0;
    const batch = db.batch();
    for (const doc of held.docs) {
      const d = doc.data();
      const state: SegmentState = {
        ownerId: (d.ownerId as string | null) ?? null,
        ownerTeamId: (d.ownerTeamId as string | null) ?? null,
        ownerScore: (d.ownerScore as number) ?? 0,
        strength: (d.strength as number) ?? 0,
        lastScoredAt: (d.lastScoredAt as number) ?? 0,
      };
      if (hasLapsed(state, now)) {
        batch.set(doc.ref, { ...UNOWNED, lapsedAt: now }, { merge: true });
        lapsed++;
      }
    }
    if (lapsed > 0) await batch.commit();
    logger.info('expireHoldings', { checked: held.size, lapsed, halfLifeDays: TUNING.contest.decayHalfLifeDays });
  },
);
