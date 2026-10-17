package com.trashtalk.data.walk

import com.trashtalk.core.LatLng
import com.trashtalk.data.TrashTalkRepository
import com.trashtalk.data.model.LitterType
import com.trashtalk.data.model.Segment
import com.trashtalk.domain.CollectionDraft
import com.trashtalk.domain.EarnedBadgeInfo
import com.trashtalk.domain.ProgressDelta
import com.trashtalk.domain.ScoreBreakdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turning a recorded walk into observations.
 *
 * A walk is not one observation, it is one per street. The whole dataset is
 * organised per segment -- that is what makes a Litter Impact Assessment
 * possible at all -- so an hour that covered four blocks has to arrive as four
 * records, each with its own counts and its own position.
 */

/** What was picked up on one segment during a walk. */
data class SegmentTally(
    val segmentId: String,
    val name: String,
    val counts: Map<LitterType, Int>,
    val at: LatLng,
    val firstAt: Long,
    val lastAt: Long,
) {
    val pieces: Int get() = counts.values.sum()

    /**
     * Minutes spent on this segment, measured as the span from the first
     * pickup to the last.
     *
     * Deliberately not a share of the walk's duration. Walking past a block
     * without picking anything up is not time spent working it, and dividing
     * the hour evenly across the segments would invent effort nobody spent.
     * A single pickup therefore reports zero, which is honest: one piece is
     * not evidence of minutes.
     */
    val minutes: Int get() = ((lastAt - firstAt) / 60_000L).toInt()
}

/** A finished walk, ready to be shown and submitted. */
data class WalkSummary(
    val walk: WalkRow,
    val tallies: List<SegmentTally>,
    /**
     * Pieces that landed too far from any segment to attribute.
     *
     * Shown rather than discarded, and never submitted as an observation about
     * a street they may not have come from. Usually a park, a parking lot, or
     * a fix that drifted.
     */
    val unattributed: Map<LitterType, Int>,
) {
    val pieces: Int get() = tallies.sumOf { it.pieces } + unattributed.values.sum()
    val attributedPieces: Int get() = tallies.sumOf { it.pieces }
    val durationMillis: Long get() = (walk.endedAt ?: System.currentTimeMillis()) - walk.startedAt
    val hasAnything: Boolean get() = pieces > 0
}

class WalkSubmitter(private val walks: WalkRepository, private val dao: WalkDao) {

    /** Groups a finished walk's pickups by the segment they were stamped to. */
    suspend fun summarise(walkId: String, network: List<Segment>): WalkSummary? =
        withContext(Dispatchers.Default) {
            val data = walks.load(walkId) ?: return@withContext null
            val byId = network.associateBy { it.id }

            val attributed = data.pickups.filter { it.segmentId != null }
            val tallies = attributed
                .groupBy { it.segmentId!! }
                .map { (segmentId, rows) ->
                    SegmentTally(
                        segmentId = segmentId,
                        name = byId[segmentId]?.name ?: "Unnamed street",
                        counts = rows.tally(),
                        // The mean of where the pickups actually happened, not
                        // the segment's midpoint: it is the measurement's own
                        // position and it is what the server will map-match.
                        at = LatLng(
                            rows.sumOf { it.lat } / rows.size,
                            rows.sumOf { it.lng } / rows.size,
                        ),
                        firstAt = rows.minOf { it.at },
                        lastAt = rows.maxOf { it.at },
                    )
                }
                .sortedByDescending { it.pieces }

            WalkSummary(
                walk = data.walk,
                tallies = tallies,
                unattributed = data.pickups.filter { it.segmentId == null }.tally(),
            )
        }

    /**
     * Submits every segment of a walk and returns one combined award.
     *
     * Each segment goes through the same path a quick log does, so a walk and a
     * one-off pickup produce the same kind of record and the dataset stays
     * uniform. The user sees one celebration, because they did one walk.
     */
    suspend fun submit(
        summary: WalkSummary,
        repository: TrashTalkRepository,
        haulPhotoUri: String?,
        notes: String = "",
    ): ProgressDelta {
        val deltas = summary.tallies.map { tally ->
            tally to repository.submitCollection(
                CollectionDraft(
                    // Derived, not random: the same walk retried produces the
                    // same ids, which is what lets the server recognise a
                    // repeat instead of counting it twice.
                    submissionId = "${summary.walk.id}_${tally.segmentId}",
                    segmentId = tally.segmentId,
                    segmentName = tally.name,
                    lat = tally.at.lat,
                    lng = tally.at.lng,
                    counts = tally.counts,
                    minutes = tally.minutes,
                    notes = notes,
                    // The haul photo covers the whole walk, so it is attached
                    // to the biggest segment rather than copied onto each --
                    // duplicating it would read as four photos of four streets.
                    photoUri = haulPhotoUri.takeIf { tally == summary.tallies.firstOrNull() },
                ),
            )
        }

        val combined = deltas.combine()

        // A walk that had already landed comes back worth nothing, because the
        // server recognised the repeat. That is the right answer to "what did
        // this submission earn" and the wrong one to store against the walk, so
        // it is passed as null and the existing award is kept.
        val alreadyLanded = combined.pointsAwarded == 0 && summary.tallies.isNotEmpty()
        dao.applyServerResult(
            walkId = summary.walk.id,
            status = WalkStatus.SCORED,
            reason = null,
            points = combined.pointsAwarded.takeUnless { alreadyLanded },
        )
        return combined
    }

    /** Throws the walk away without submitting it. */
    suspend fun discard(walkId: String) = withContext(Dispatchers.IO) {
        dao.deleteWalk(walkId)
    }
}

private fun List<PickupRow>.tally(): Map<LitterType, Int> =
    mapNotNull { row ->
        runCatching { LitterType.valueOf(row.litterType) }.getOrNull()?.let { it to row.count }
    }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, counts) -> counts.sum() }

/**
 * Folds a walk's per-segment awards into the single one the user sees.
 *
 * The breakdown is rewritten rather than concatenated: four segments would
 * otherwise produce twenty lines of base-points-and-multipliers that say
 * nothing. One line per street, with what that street was worth, is the thing
 * a walker actually wants to know.
 */
private fun List<Pair<SegmentTally, ProgressDelta>>.combine(): ProgressDelta {
    if (isEmpty()) return ProgressDelta()
    if (size == 1) return first().second

    val total = sumOf { it.second.pointsAwarded }
    val badges = flatMap { it.second.newBadges }
        .groupBy { it.def.id }
        .map { (_, earned) -> earned.maxBy { it.tier } }

    return ProgressDelta(
        pointsAwarded = total,
        breakdown = ScoreBreakdown(
            basePoints = sumOf { it.second.breakdown?.basePoints ?: 0 },
            difficultyBonus = sumOf { it.second.breakdown?.difficultyBonus ?: 0 },
            streakBonus = sumOf { it.second.breakdown?.streakBonus ?: 0 },
            coverageBonus = sumOf { it.second.breakdown?.coverageBonus ?: 0 },
            harmBonus = sumOf { it.second.breakdown?.harmBonus ?: 0 },
            total = total,
            // Multipliers are per-observation and do not add up; the combined
            // card does not claim one for the walk.
            difficultyMultiplier = 1f,
            streakMultiplier = 1f,
            lines = map { (tally, delta) -> tally.name to delta.pointsAwarded },
        ),
        shareCard = maxByOrNull { it.first.pieces }?.second?.shareCard,
        newBadges = badges,
        pathLevelUp = mapNotNull { it.second.pathLevelUp }.maxOrNull(),
        streakDays = maxOf { it.second.streakDays },
        streakExtended = any { it.second.streakExtended },
        wasFirstSurvey = any { it.second.wasFirstSurvey },
    )
}

private fun List<EarnedBadgeInfo>.maxBy(selector: (EarnedBadgeInfo) -> Int): EarnedBadgeInfo =
    maxByOrNull(selector)!!
