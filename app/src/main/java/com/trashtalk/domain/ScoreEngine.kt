package com.trashtalk.domain

import com.trashtalk.data.model.BadgeCatalog
import com.trashtalk.data.model.BadgeDef
import com.trashtalk.data.model.EarnedBadge
import com.trashtalk.data.model.LitterType
import com.trashtalk.data.model.Segment
import com.trashtalk.data.model.UserProfile
import com.trashtalk.data.model.UserStats
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** A pickup as the user entered it, before scoring. */
data class CollectionDraft(
    /**
     * Identifies this submission, generated on the client before it is sent.
     *
     * The id is what makes a retry safe. Without it the observation is written
     * with a server-generated key, so a queue doing its job -- retrying after a
     * timeout it could not distinguish from a failure -- writes the pickup
     * twice, drops the segment's density twice and awards the points twice.
     */
    val submissionId: String = java.util.UUID.randomUUID().toString(),
    val segmentId: String,
    val segmentName: String,
    val lat: Double,
    val lng: Double,
    val counts: Map<LitterType, Int>,
    val minutes: Int = 0,
    val notes: String = "",
    val photoUri: String? = null,
) {
    val pieces: Int get() = counts.values.sum()
}

/** The itemised award for one pickup, kept transparent so the UI can show the maths. */
data class ScoreBreakdown(
    val basePoints: Int,
    val difficultyBonus: Int,
    val streakBonus: Int,
    val coverageBonus: Int,
    val harmBonus: Int,
    val total: Int,
    val difficultyMultiplier: Float,
    val streakMultiplier: Float,
    val lines: List<Pair<String, Int>>,
)

/**
 * Turns pieces of litter into points.
 *
 * Three deliberate incentives are baked in, because a scoring rule is a statement
 * about what the project actually wants:
 *
 *  1. Harm, not mass. A plastic bag outscores a newspaper, because the bag is what
 *     ends up as particles in a food web.
 *  2. Difficulty, not convenience. A heavy segment pays more than an easy one, so
 *     the leaderboard does not reward whoever lives nearest a clean park.
 *  3. Coverage, not repetition. First surveys pay a flat bonus, which is the only
 *     counterweight to everybody re-walking the same photogenic block.
 */
object ScoreEngine {

    private const val COVERAGE_BONUS = 50
    private const val MAX_STREAK_DAYS = 30

    fun difficultyMultiplier(segment: Segment?): Float =
        1f + (segment?.litterIndex ?: 0f) * 0.5f

    fun streakMultiplier(streakDays: Int): Float =
        1f + (streakDays.coerceAtMost(MAX_STREAK_DAYS) * 0.02f)

    fun score(
        draft: CollectionDraft,
        segment: Segment?,
        profile: UserProfile,
    ): ScoreBreakdown {
        val base = draft.counts.entries.sumOf { (type, n) -> type.points * n }

        val difficulty = difficultyMultiplier(segment)
        val streak = streakMultiplier(profile.streakDays)

        val difficultyBonus = (base * (difficulty - 1f)).roundToInt()
        val streakBonus = ((base + difficultyBonus) * (streak - 1f)).roundToInt()

        val isFirstSurvey = segment != null && !segment.hasBeenSurveyed
        val coverage = if (isFirstSurvey) COVERAGE_BONUS else 0

        // An extra nudge toward the single-use plastics the manifesto is about.
        val harmBonus = draft.counts.entries
            .filter { it.key.isSingleUsePlastic }
            .sumOf { it.value } * 2

        val total = base + difficultyBonus + streakBonus + coverage + harmBonus

        val lines = buildList {
            add("${draft.pieces} pieces collected" to base)
            if (difficultyBonus > 0) {
                val band = ((segment?.litterIndex ?: 0f) * 100).roundToInt()
                add("Segment difficulty (litter index $band%)" to difficultyBonus)
            }
            if (streakBonus > 0) add("${profile.streakDays}-day streak" to streakBonus)
            if (coverage > 0) add("First survey of this segment" to coverage)
            if (harmBonus > 0) add("Single-use plastic removed" to harmBonus)
        }

        return ScoreBreakdown(
            basePoints = base,
            difficultyBonus = difficultyBonus,
            streakBonus = streakBonus,
            coverageBonus = coverage,
            harmBonus = harmBonus,
            total = total,
            difficultyMultiplier = difficulty,
            streakMultiplier = streak,
            lines = lines,
        )
    }

    /** Day index in the device's timezone, so streaks break at local midnight. */
    fun dayIndex(timeMillis: Long): Long {
        val offset = java.util.TimeZone.getDefault().getOffset(timeMillis)
        return TimeUnit.MILLISECONDS.toDays(timeMillis + offset)
    }

    /**
     * Streak after a collection at [now]: same day keeps it, the next day extends
     * it, any larger gap restarts at one.
     */
    fun nextStreak(profile: UserProfile, now: Long): Int {
        val today = dayIndex(now)
        return when (profile.lastCollectionDay) {
            today -> profile.streakDays.coerceAtLeast(1)
            today - 1 -> profile.streakDays + 1
            else -> 1
        }
    }
}

/** What changed as a result of one action, so the UI can celebrate precisely. */
data class ProgressDelta(
    val pointsAwarded: Int = 0,
    val breakdown: ScoreBreakdown? = null,
    /** Everything a share card needs, when this delta came from a pickup. */
    val shareCard: com.trashtalk.ui.share.ShareCardData? = null,
    val newBadges: List<EarnedBadgeInfo> = emptyList(),
    val pathLevelUp: Int? = null,
    val streakDays: Int = 0,
    val streakExtended: Boolean = false,
    val wasFirstSurvey: Boolean = false,
) {
    val isNoteworthy: Boolean
        get() = newBadges.isNotEmpty() || pathLevelUp != null
}

data class EarnedBadgeInfo(
    val def: BadgeDef,
    val tier: Int,
)

/**
 * Awards badge tiers by comparing banked tiers against what the current stats
 * justify. Evaluating the whole catalogue every time -- rather than reacting to
 * individual events -- makes awards idempotent: replaying a collection, or fixing
 * a stats bug later, cannot double-award or silently skip a tier.
 */
object BadgeEngine {

    fun evaluate(
        profile: UserProfile,
        stats: UserStats,
        now: Long,
    ): Pair<UserProfile, List<EarnedBadgeInfo>> {
        val newlyEarned = mutableListOf<EarnedBadgeInfo>()
        val tiers = profile.badgeTiers.toMutableMap()
        val earned = profile.earned.toMutableList()

        for (badge in BadgeCatalog.all) {
            val deserved = badge.tiersEarned(stats.metric(badge.metric))
            val banked = tiers[badge.id] ?: 0
            if (deserved > banked) {
                for (tier in (banked + 1)..deserved) {
                    newlyEarned += EarnedBadgeInfo(badge, tier)
                    earned += EarnedBadge(badge.id, tier, now)
                }
                tiers[badge.id] = deserved
            }
        }

        val updated = profile.copy(
            badgeTiers = tiers,
            earned = earned,
            stats = stats,
        )
        return updated to newlyEarned
    }

    /** Badge tiers award points too, scaled so later tiers stay worth chasing. */
    fun pointsFor(info: EarnedBadgeInfo): Int = 100 * info.tier

    /** Progress toward a badge's next tier, as a 0..1 fraction. */
    fun progress(def: BadgeDef, value: Int): Float {
        val next = def.nextTarget(value) ?: return 1f
        val prev = def.tierTargets.lastOrNull { value >= it } ?: 0
        if (next <= prev) return 1f
        return ((value - prev).toFloat() / (next - prev)).coerceIn(0f, 1f)
    }
}
