/**
 * Does this track describe somebody walking?
 *
 * The client cannot be trusted to answer that — it is the thing being checked
 * — so none of this runs on the phone. The recorder collects the evidence
 * (accuracy per fix, Android's mock-location flag) and the server decides.
 *
 * The bar is deliberately "implausible", not "suspicious". A false rejection
 * costs a volunteer an hour of their Saturday and they do not come back; a
 * false acceptance costs some points in a pilot with no prizes. So every check
 * here rejects things that cannot happen, not things that look unusual, and
 * anything merely odd is left for evidence review to weigh instead.
 */

import { TUNING } from './tuning';

export interface Fix {
  lat: number;
  lng: number;
  /** Epoch milliseconds. */
  at: number;
  accuracyMetres: number;
  isMock?: boolean;
}

export type RejectionCode =
  | 'TOO_SHORT'
  | 'TIME_WENT_BACKWARDS'
  | 'IMPOSSIBLE_JUMP'
  | 'TOO_FAST'
  | 'TOO_VAGUE'
  | 'MOCK_LOCATION'
  | 'SEGMENTS_TOO_FAST';

export interface Rejection {
  code: RejectionCode;
  /** Shown to the user. Plain language, never a code or a raw number. */
  reason: string;
}

export interface TraceVerdict {
  ok: boolean;
  rejection?: Rejection;
  /** Metres along the track, after discarding nothing. */
  distanceMetres: number;
  durationSeconds: number;
}

const EARTH_RADIUS_M = 6_371_000;

export function haversineMetres(a: Fix, b: Fix): number {
  const p1 = (a.lat * Math.PI) / 180;
  const p2 = (b.lat * Math.PI) / 180;
  const dp = p2 - p1;
  const dl = ((b.lng - a.lng) * Math.PI) / 180;
  const h =
    Math.sin(dp / 2) ** 2 + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) ** 2;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(h));
}

/**
 * Checks a recorded track.
 *
 * Speed is measured over the whole walk rather than between consecutive
 * fixes. Two fixes a second apart with a few metres of GPS noise between them
 * imply a sprint, and rejecting on that would throw out honest walks for
 * standing still near a building. What cannot be explained by noise is
 * covering a kilometre in two minutes, and that only shows up in the total.
 */
export function validateTrace(fixes: Fix[]): TraceVerdict {
  const empty = { distanceMetres: 0, durationSeconds: 0 };

  if (fixes.length < 2) {
    return {
      ok: false,
      ...empty,
      rejection: {
        code: 'TOO_SHORT',
        reason: 'This walk was too short to record a route.',
      },
    };
  }

  const durationSeconds = (fixes[fixes.length - 1]!.at - fixes[0]!.at) / 1000;
  if (durationSeconds < TUNING.trace.minDurationSeconds) {
    return {
      ok: false,
      ...empty,
      rejection: {
        code: 'TOO_SHORT',
        reason: 'This walk was too short to record a route.',
      },
    };
  }

  let distanceMetres = 0;

  for (let i = 0; i < fixes.length; i++) {
    const fix = fixes[i]!;

    if (fix.isMock) {
      return {
        ok: false,
        distanceMetres,
        durationSeconds,
        rejection: {
          code: 'MOCK_LOCATION',
          reason:
            'This walk used a simulated location, so it could not be scored.',
        },
      };
    }

    if (fix.accuracyMetres > TUNING.trace.maxAccuracyMetres) {
      return {
        ok: false,
        distanceMetres,
        durationSeconds,
        rejection: {
          code: 'TOO_VAGUE',
          reason:
            'The GPS signal was too weak during this walk to say which streets it covered.',
        },
      };
    }

    if (i === 0) continue;
    const previous = fixes[i - 1]!;

    if (fix.at < previous.at) {
      return {
        ok: false,
        distanceMetres,
        durationSeconds,
        rejection: {
          code: 'TIME_WENT_BACKWARDS',
          reason: "This walk's timings did not add up, so it could not be scored.",
        },
      };
    }

    const step = haversineMetres(previous, fix);
    if (step > TUNING.trace.maxJumpMetres) {
      return {
        ok: false,
        distanceMetres,
        durationSeconds,
        rejection: {
          code: 'IMPOSSIBLE_JUMP',
          reason: `The GPS jumped ${Math.round(step)} m, so this walk could not be scored.`,
        },
      };
    }
    distanceMetres += step;
  }

  const speed = distanceMetres / Math.max(durationSeconds, 1);
  if (speed > TUNING.trace.maxSpeedMetresPerSecond) {
    return {
      ok: false,
      distanceMetres,
      durationSeconds,
      rejection: {
        code: 'TOO_FAST',
        reason: `This walk averaged ${(speed * 3.6).toFixed(0)} km/h, which is faster than walking.`,
      },
    };
  }

  return { ok: true, distanceMetres, durationSeconds };
}

/**
 * Checks how quickly new blocks were claimed.
 *
 * Separate from the trace because it catches a different forgery: a plausible
 * track with pickups sprinkled across more blocks than anyone could have
 * worked in the time.
 */
export function validateCoverage(
  segmentCount: number,
  durationSeconds: number,
): Rejection | null {
  const minutes = Math.max(durationSeconds / 60, 1);
  const rate = segmentCount / minutes;
  if (rate > TUNING.trace.maxNewSegmentsPerMinute) {
    return {
      code: 'SEGMENTS_TOO_FAST',
      reason: `This walk covered ${segmentCount} blocks in ${Math.round(minutes)} minutes, which is faster than they can be walked.`,
    };
  }
  return null;
}
