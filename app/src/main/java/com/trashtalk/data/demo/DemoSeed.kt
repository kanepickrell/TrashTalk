package com.trashtalk.data.demo

import com.trashtalk.core.pointAlongPolyline
import com.trashtalk.data.NeighborhoodNetwork
import com.trashtalk.data.model.CollectionEvent
import com.trashtalk.data.model.FlagKind
import com.trashtalk.data.model.LitterType
import com.trashtalk.data.model.MapFlag
import com.trashtalk.data.model.Segment
import com.trashtalk.data.model.Team
import com.trashtalk.data.model.TrashPath
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * A believable neighbourhood of other people.
 *
 * An empty leaderboard teaches a new player nothing, and a social app with one
 * user cannot demonstrate the thing it is for. This seeds four crews, thirty-odd
 * collectors spread across the four paths and levels, two weeks of their
 * collections, and the flags they planted -- all from a fixed seed, so the demo is
 * identical on every device and screenshots are reproducible.
 *
 * The distribution is deliberately uneven: a handful of people do most of the
 * collecting, most collections cluster on a few convenient blocks, and the
 * unglamorous interior streets are thin. That bias is the problem TrashTalk is
 * built to surface, so the demo data should exhibit it rather than hide it.
 */
object DemoSeed {

    const val SEED = 20250214L

    data class Collector(
        val id: String,
        val name: String,
        val teamId: String,
        val path: TrashPath,
        val level: Int,
        val points: Int,
        val pieces: Int,
        val weeklyPoints: Int,
        val streak: Int,
    )

    data class Community(
        val teams: List<Team>,
        val collectors: List<Collector>,
        val collections: List<CollectionEvent>,
        val flags: List<MapFlag>,
        /** Pieces already removed per segment, folded into segment state. */
        val removedBySegment: Map<String, Int>,
        /** Segments already claimed under Protect the Flag, by segment id. */
        val claims: Map<String, Claim>,
    )

    /** An adopted segment: who holds it, for which crew, since when. */
    data class Claim(
        val userId: String,
        val userName: String,
        val teamId: String,
        val since: Long,
    )

    private val TEAMS = listOf(
        Triple("team_trashers", "Terrell Heights Trashers", "The original crew. Meets Saturdays at the park."),
        Triple("team_austin", "Austin Hwy Alliance", "Arterial specialists. Somebody has to do it."),
        Triple("team_wilshire", "Wilshire Walkers", "Parents doing the school run with grabbers."),
        Triple("team_night", "Nacogdoches Night Crew", "Evening shift, high-vis vests, strong opinions."),
    )

    private val FIRST_NAMES = listOf(
        "Mara", "Dev", "Yolanda", "Theo", "Priya", "Marcus", "Ines", "Caleb",
        "Rosa", "Hank", "Nadia", "Oscar", "Lupe", "Jonah", "Simone", "Abel",
        "Trish", "Emeka", "Dolores", "Wes", "Anika", "Rafa", "Grace", "Bo",
        "Vera", "Luis", "Jo", "Tomas", "Shanice", "Felix", "Imani", "Gus",
    )

    private val LAST_INITIALS = listOf(
        "A.", "B.", "C.", "D.", "F.", "G.", "H.", "K.", "L.", "M.",
        "N.", "P.", "R.", "S.", "T.", "V.", "W.", "Z.",
    )

    private val NOTES = listOf(
        "Bus stop bin was overflowing again.",
        "Same corner, third week running.",
        "Mostly wrappers from the taqueria side.",
        "Storm drain was packed with film.",
        "Quiet block, found almost nothing. Good.",
        "Somebody dumped a bag of fast food.",
        "Cigarette butts around the bench.",
        "After-school rush leaves a trail here.",
        "Wind pushes everything against this fence.",
        "Cleaner than last time -- adoption is working.",
    )

    private val FLAG_NOTES = listOf(
        "Litter always piles against this wall.",
        "Wind trap. Everything ends up here.",
        "Think the bin here is undersized.",
        "Guessing this gets bad on weekends.",
        "Drain inlet collects film every rain.",
    )

    fun build(network: NeighborhoodNetwork, now: Long): Community {
        val rng = Random(SEED)

        val teams = TEAMS.map { (id, name, blurb) ->
            Team(id = id, name = name, cityId = network.cityId, blurb = blurb)
        }

        // Collector strength follows a steep curve: a few people carry the crew.
        val collectors = (0 until 32).map { i ->
            val strength = (1.0 - i / 32.0).let { it * it }
            val path = TrashPath.entries[rng.nextInt(TrashPath.entries.size)]
            val pieces = (40 + strength * 900 + rng.nextInt(80)).roundToInt()
            Collector(
                id = "demo_user_$i",
                name = "${FIRST_NAMES[i % FIRST_NAMES.size]} ${LAST_INITIALS[(i * 7) % LAST_INITIALS.size]}",
                teamId = teams[i % teams.size].id,
                path = path,
                level = 1 + (strength * 6).roundToInt() + rng.nextInt(2),
                points = (pieces * (14 + rng.nextInt(9))),
                pieces = pieces,
                weeklyPoints = (pieces * (2 + rng.nextInt(4))).coerceAtMost(4200),
                streak = if (rng.nextFloat() < 0.4f) rng.nextInt(1, 46) else 0,
            )
        }

        // Attention is concentrated: sort segments by a fixed pseudo-random appeal
        // and draw collection locations mostly from the top of that list.
        val appeal = network.segments.associate { it.id to rng.nextFloat() }
        val ranked = network.segments.sortedByDescending {
            // Convenient blocks: dirty enough to be worth it, plus arbitrary habit.
            it.litterIndex * 0.6f + (appeal[it.id] ?: 0f) * 0.4f
        }

        val collections = mutableListOf<CollectionEvent>()
        val removed = mutableMapOf<String, Int>()
        val dayMs = TimeUnit.DAYS.toMillis(1)

        repeat(260) { k ->
            val collector = collectors[weightedIndex(rng, collectors.size)]
            // Draw from the top third of the ranked list most of the time.
            val segIdx = if (rng.nextFloat() < 0.78f) {
                rng.nextInt((ranked.size / 3).coerceAtLeast(1))
            } else {
                rng.nextInt(ranked.size)
            }
            val segment = ranked[segIdx]
            val daysAgo = rng.nextInt(network.historyDays)
            val at = now - daysAgo * dayMs - rng.nextLong(dayMs)

            val counts = drawCounts(rng, segment)
            val pieces = counts.values.sum()
            if (pieces == 0) return@repeat

            val minutes = (6 + pieces * (0.6 + rng.nextDouble() * 0.8)).roundToInt()
            val team = teams.firstOrNull { it.id == collector.teamId }

            collections += CollectionEvent(
                id = "demo_col_$k",
                userId = collector.id,
                userName = collector.name,
                teamId = team?.id,
                segmentId = segment.id,
                segmentName = segment.name,
                location = pointAlongPolyline(segment.path, rng.nextDouble()),
                counts = counts,
                notes = if (rng.nextFloat() < 0.45f) NOTES[rng.nextInt(NOTES.size)] else "",
                createdAt = at,
                points = counts.entries.sumOf { (t, n) -> t.points * n },
                minutes = minutes,
            )
            removed[segment.id] = (removed[segment.id] ?: 0) + pieces
        }

        val flags = buildList {
            repeat(14) { k ->
                val collector = collectors[rng.nextInt(collectors.size)]
                val segment = ranked[rng.nextInt((ranked.size / 2).coerceAtLeast(1))]
                val anonymous = rng.nextFloat() < 0.3f
                val kind = when {
                    rng.nextFloat() < 0.18f -> FlagKind.HAZARD
                    else -> FlagKind.HOTSPOT_GUESS
                }
                add(
                    MapFlag(
                        id = "demo_flag_$k",
                        userId = collector.id,
                        userName = if (anonymous) "Anonymous" else collector.name,
                        kind = kind,
                        location = pointAlongPolyline(segment.path, 0.35 + rng.nextDouble() * 0.3),
                        segmentId = segment.id,
                        note = FLAG_NOTES[rng.nextInt(FLAG_NOTES.size)],
                        createdAt = now - rng.nextLong(network.historyDays * dayMs),
                        confirmed = kind == FlagKind.HOTSPOT_GUESS && segment.litterIndex > 0.45f,
                        anonymous = anonymous,
                    ),
                )
            }
        }

        // Fold crew totals back into the team rows so the team cards agree with
        // the collections feed rather than contradicting it.
        val byTeam = collections.groupBy { it.teamId }
        val weekAgo = now - TimeUnit.DAYS.toMillis(7)
        val teamsWithTotals = teams.map { team ->
            val own = byTeam[team.id].orEmpty()
            team.copy(
                memberCount = collectors.count { it.teamId == team.id },
                totalPieces = own.sumOf { it.pieces },
                totalPoints = own.sumOf { it.points },
                weeklyPoints = own.filter { it.createdAt >= weekAgo }.sumOf { it.points },
            )
        }

        // Claimed territory. Crews adopt the segments they already work, so the
        // territory map reads as a consequence of effort rather than a separate
        // game pasted on top: the blocks people actually walk are the blocks they
        // end up holding, and the contested ground is the dirty arterial nobody
        // can keep clean for a full week.
        val worked = collections.groupingBy { it.segmentId }.eachCount()
        val claimCandidates = network.segments
            .filter { (worked[it.id] ?: 0) >= 2 }
            .sortedByDescending { worked[it.id] ?: 0 }
            .take(34)
        val claims = mutableMapOf<String, Claim>()
        for (segment in claimCandidates) {
            if (rng.nextFloat() > 0.72f) continue
            val worker = collections
                .filter { it.segmentId == segment.id }
                .groupingBy { it.userId }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
                ?.let { id -> collectors.firstOrNull { it.id == id } }
                ?: continue
            claims[segment.id] = Claim(
                userId = worker.id,
                userName = worker.name,
                teamId = worker.teamId,
                since = now - rng.nextLong(TimeUnit.DAYS.toMillis(21)),
            )
        }

        return Community(
            teams = teamsWithTotals,
            collectors = collectors,
            collections = collections.sortedByDescending { it.createdAt },
            flags = flags,
            removedBySegment = removed,
            claims = claims,
        )
    }

    /** Index drawn with a bias toward the front of the list. */
    private fun weightedIndex(rng: Random, size: Int): Int {
        val r = rng.nextFloat()
        return (r * r * size).toInt().coerceIn(0, size - 1)
    }

    /**
     * Draws a plausible set of counts for a pickup on [segment], using the
     * segment's own composition so the feed agrees with the Litter Impact
     * Assessment instead of inventing a different stream.
     */
    private fun drawCounts(rng: Random, segment: Segment): Map<LitterType, Int> {
        val pieces = (2 + segment.litterIndex * 26 * rng.nextDouble(0.5, 1.4)).roundToInt()
            .coerceIn(1, 60)
        if (segment.composition.isEmpty()) {
            return mapOf(LitterType.OTHER to pieces)
        }
        val types = segment.composition.entries.mapNotNull { (k, v) ->
            runCatching { LitterType.valueOf(k) }.getOrNull()?.let { it to v }
        }
        if (types.isEmpty()) return mapOf(LitterType.OTHER to pieces)

        val total = types.sumOf { it.second.toDouble() }
        val out = mutableMapOf<LitterType, Int>()
        repeat(pieces) {
            var roll = rng.nextDouble() * total
            for ((type, share) in types) {
                roll -= share
                if (roll <= 0) {
                    out[type] = (out[type] ?: 0) + 1
                    return@repeat
                }
            }
            out[LitterType.OTHER] = (out[LitterType.OTHER] ?: 0) + 1
        }
        return out
    }
}
