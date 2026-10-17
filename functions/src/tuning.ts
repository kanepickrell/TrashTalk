/**
 * Every number the contest turns on, in one place.
 *
 * These are the rules of a game, which means they are going to be wrong at
 * first and retuned once real walks exist. Keeping them together — rather than
 * scattered through the code that uses them — is what makes that a one-line
 * edit instead of an archaeology exercise.
 *
 * The rationale for each lives in docs/DECISIONS.md. When you change one,
 * change the note too: a constant with no reasoning behind it is a constant
 * nobody will dare touch later.
 */

export const TUNING = {
  /** Anti-cheat: what a recorded walk has to look like to be believable. */
  trace: {
    /**
     * Sustained pace ceiling, metres per second.
     *
     * 12 km/h. The sources this was modelled on use 45 km/h (terrarun) and
     * 25 km/h (Territory-Run), but those are running games. Everyone here is
     * walking and stopping constantly to pick things up; 12 km/h is already
     * generous for that, and anything sustained above it is a vehicle.
     */
    maxSpeedMetresPerSecond: 12_000 / 3600,

    /**
     * The largest believable gap between consecutive fixes, in metres.
     *
     * A walk through an urban canyon loses signal and jumps. 500 m is a
     * tolerable recovery; more than that and the track is describing a car,
     * a train, or a forged file.
     */
    maxJumpMetres: 500,

    /**
     * Fixes vaguer than this are not evidence of being anywhere.
     *
     * The recorder already drops anything worse than 30 m before storing it,
     * so this is the server refusing to trust a client that did not.
     */
    maxAccuracyMetres: 50,

    /**
     * How fast somebody can plausibly start covering new blocks.
     *
     * A block takes minutes to walk and work. Territory-Run caps at 40 cells
     * a minute for runners; at a walking pace with a bag, 4 is already fast.
     */
    maxNewSegmentsPerMinute: 4,

    /** A walk shorter than this is not a walk. */
    minDurationSeconds: 30,
  },

  /** The contest: how territory is won, held and lost. */
  contest: {
    /**
     * Half-life of a score on a segment, in days.
     *
     * Fourteen days, from Territory-Run. This is the number most likely to be
     * wrong for TrashTalk: theirs is a running game where a route is repeated
     * weekly, and litter on a residential street re-accumulates on its own
     * schedule. Watch whether holders feel they are on a treadmill, and
     * lengthen it if so.
     */
    decayHalfLifeDays: 14,

    /**
     * How far a challenger must exceed the holder to take a block.
     *
     * 1.15x. A margin below 1 would let ties flip ownership back and forth on
     * rounding; a margin far above it would make a block effectively
     * unwinnable once held. This is the smallest number that makes taking
     * territory a decision rather than an accident.
     */
    flipMargin: 1.15,

    /** Strength runs 1..5 and is what a siege has to grind through. */
    maxStrength: 5,

    /**
     * Cleanups on a held segment per point of strength.
     *
     * Three. Reaching maximum strength is then twelve cleanups of the same
     * block, which should feel like a commitment rather than a formality.
     */
    cleanupsPerStrength: 3,

    /**
     * Siege progress a single qualifying cleanup contributes.
     *
     * One, so taking a maximum-strength block takes five visits. Progress
     * persists between visits -- a siege the challenger has to restart every
     * time is not a siege, it is a lottery.
     */
    siegePerCleanup: 1,

    /**
     * Below this decayed score, an unchallenged segment reverts to unowned.
     *
     * Someone who walked a block once a year ago is not its guardian.
     */
    abandonBelowScore: 1,
  },

  /** Evidence: what a cleanup has to carry to count for territory. */
  evidence: {
    /** Metres a photo may be from the track before it looks staged. */
    maxPhotoDriftMetres: 150,

    /** Minutes either side of the walk a photo may claim to have been taken. */
    photoWindowMinutes: 30,
  },
} as const;

export type Tuning = typeof TUNING;
