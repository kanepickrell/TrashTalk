package com.trashtalk.data.model

import com.trashtalk.core.LatLng

/**
 * What a piece of litter is, and what it costs the world.
 *
 * [harm] in 0..1 is a persistence-and-toxicity weight, not a mass: a plastic bag
 * photodegrades into particles that carry PCBs and endocrine disrupters and that
 * animals mistake for food, so it scores near the top despite weighing almost
 * nothing, while an aluminium can -- genuinely circular, worth money at the
 * buy-back centre -- scores near the bottom. Points follow harm, which means the
 * game pays most for removing exactly what hurts most.
 */
enum class LitterType(
    val label: String,
    val avgGrams: Double,
    val harm: Double,
    val note: String,
) {
    PLASTIC_BAG("Plastic bag", 5.5, 1.00, "Photodegrades into particles that resemble zooplankton"),
    FILM_WRAPPER("Film / wrapper", 2.5, 0.95, "Multi-layer film: effectively unrecyclable"),
    FOAM("Foam / polystyrene", 3.0, 0.95, "Shatters into beads that never reconsolidate"),
    CIGARETTE("Cigarette butt", 0.3, 0.90, "Cellulose acetate plus concentrated toxins"),
    FOOD_PACKAGING("Food packaging", 12.0, 0.85, "Single-use by design, often food-soiled"),
    PLASTIC_BOTTLE("Plastic bottle", 20.0, 0.80, "Recyclable in theory, landfilled in practice"),
    GLASS("Glass", 180.0, 0.30, "Inert but an injury hazard; infinitely recyclable"),
    ALUMINUM_CAN("Aluminium can", 14.0, 0.25, "The one truly circular material in the stream"),
    PAPER("Paper / cardboard", 18.0, 0.15, "Biodegrades; still a sign of an upstream problem"),
    OTHER("Other", 25.0, 0.50, "Anything that does not fit the survey taxonomy");

    /** Points for a single piece. Harm dominates, so plastics outscore paper 3:1. */
    val points: Int get() = (10 + harm * 15).toInt()

    val isSingleUsePlastic: Boolean
        get() = this in setOf(PLASTIC_BAG, FILM_WRAPPER, FOAM, FOOD_PACKAGING, PLASTIC_BOTTLE)

    companion object {
        /** Survey order: the six that dominate street counts, then the rest. */
        val surveyOrder: List<LitterType> = listOf(
            PLASTIC_BOTTLE, FILM_WRAPPER, CIGARETTE, FOOD_PACKAGING,
            ALUMINUM_CAN, PLASTIC_BAG, FOAM, GLASS, PAPER, OTHER,
        )
    }
}

/** How a street is classified, which drives both litter load and render weight. */
enum class StreetClass(val id: String, val label: String, val strokeWeight: Float) {
    ARTERIAL("arterial", "Arterial", 1.9f),
    COLLECTOR("collector", "Collector", 1.35f),
    RESIDENTIAL("residential", "Residential", 1.0f);

    companion object {
        fun from(id: String?): StreetClass =
            entries.firstOrNull { it.id == id } ?: RESIDENTIAL
    }
}

/**
 * A surveyable stretch of street between two intersections -- the atom of the whole
 * system. Points, adoption, hotspot detection and the Litter Impact Assessment all
 * resolve to a segment, which is what makes the map a field of edges rather than a
 * scatter of pins.
 */
data class Segment(
    val id: String = "",
    val name: String = "",
    val streetClass: StreetClass = StreetClass.RESIDENTIAL,
    val path: List<LatLng> = emptyList(),
    /** Current litter density in 0..1. */
    val litterIndex: Float = 0f,
    /** How fast this segment re-accumulates once cleaned, in 0..1. */
    val propensity: Float = 0f,
    val lengthMeters: Double = 0.0,
    val estimatedPieces: Int = 0,
    val surveyCount: Int = 0,
    val cleanupCount: Int = 0,
    val piecesRemoved: Int = 0,
    val lastSurveyedAt: Long = 0L,
    /** Trailing daily litter index, oldest first; drives the time scrubber. */
    val history: List<Float> = emptyList(),
    /** Share of pieces by type, summing to ~1. */
    val composition: Map<String, Float> = emptyMap(),
    /** Whoever has adopted this segment under "protect the flag". */
    val guardianUserId: String? = null,
    val guardianName: String? = null,
    val guardianTeamId: String? = null,
    val guardianSince: Long = 0L,
) {
    val isClaimed: Boolean get() = guardianUserId != null

    val hasBeenSurveyed: Boolean get() = surveyCount > 0

    val midpoint: LatLng
        get() = if (path.isEmpty()) LatLng(0.0, 0.0) else path[path.size / 2]

    /** Change in litter index over the trailing window: positive means worsening. */
    val sevenDayChange: Float
        get() {
            if (history.size < 2) return 0f
            val from = history[(history.size - 8).coerceAtLeast(0)]
            return history.last() - from
        }

    /** Litter index as of [dayIndex] in the history window. */
    fun indexAt(dayIndex: Int): Float =
        if (history.isEmpty()) litterIndex
        else history[dayIndex.coerceIn(0, history.lastIndex)]

    /**
     * Pieces per 100 m -- the density figure a city actually needs, because a long
     * arterial and a short cul-de-sac are not comparable on raw counts.
     */
    val piecesPer100m: Double
        get() = if (lengthMeters <= 0) 0.0 else estimatedPieces / lengthMeters * 100.0
}

/** A litter generator: transit stop, shop, school. Explains hotspots on the map. */
data class Poi(
    val id: String = "",
    val name: String = "",
    val kind: String = "",
    val location: LatLng = LatLng(0.0, 0.0),
)

/** One logged pickup. */
data class CollectionEvent(
    val id: String = "",
    val userId: String = "",
    val userName: String = "",
    val teamId: String? = null,
    val segmentId: String = "",
    val segmentName: String = "",
    val location: LatLng = LatLng(0.0, 0.0),
    val counts: Map<LitterType, Int> = emptyMap(),
    val photoUri: String? = null,
    val notes: String = "",
    val createdAt: Long = 0L,
    val points: Int = 0,
    /** Set when this was the first survey of a previously unwalked segment. */
    val wasFirstSurvey: Boolean = false,
    /** Minutes spent, as reported by the collector. */
    val minutes: Int = 0,
) {
    val pieces: Int get() = counts.values.sum()
    val grams: Double get() = counts.entries.sumOf { (t, n) -> t.avgGrams * n }
    val harmScore: Double get() = counts.entries.sumOf { (t, n) -> t.harm * n }
    val singleUsePlasticPieces: Int
        get() = counts.entries.filter { it.key.isSingleUsePlastic }.sumOf { it.value }
}

/** A user-planted marker: a hotspot guess, a hazard report, or an adoption. */
data class MapFlag(
    val id: String = "",
    val userId: String = "",
    val userName: String = "",
    val kind: FlagKind = FlagKind.HOTSPOT_GUESS,
    val location: LatLng = LatLng(0.0, 0.0),
    val segmentId: String? = null,
    val note: String = "",
    val createdAt: Long = 0L,
    /** A hotspot guess is confirmed once collection data backs it up. */
    val confirmed: Boolean = false,
    val anonymous: Boolean = false,
)

enum class FlagKind(val label: String, val blurb: String) {
    HOTSPOT_GUESS("Hotspot guess", "I think litter collects here"),
    HAZARD("Hazard report", "Dumping or a sharps risk that needs a crew"),
    GUARDIAN("Adoption", "I am keeping this segment clean"),
}

data class Team(
    val id: String = "",
    val name: String = "",
    val cityId: String = "",
    val blurb: String = "",
    val memberCount: Int = 0,
    val weeklyPoints: Int = 0,
    val totalPieces: Int = 0,
    val totalPoints: Int = 0,
)

/** A row in any leaderboard, whatever the scope. */
data class LeaderboardEntry(
    val id: String = "",
    val name: String = "",
    val subtitle: String = "",
    val points: Int = 0,
    val pieces: Int = 0,
    val rank: Int = 0,
    val isCurrentUser: Boolean = false,
    val pathLabel: String? = null,
    val level: Int = 1,
)

enum class LeaderboardScope(val label: String) {
    CITY_WEEK("City, this week"),
    TEAM_WEEK("My team, this week"),
    ALL_TIME("All time"),
}

/** Where the data is coming from, surfaced in the UI so the state is never a mystery. */
enum class BackendMode(val label: String, val blurb: String) {
    DEMO(
        "Local demo data",
        "Seeded on this device. Add google-services.json to sync with Firebase.",
    ),
    LIVE(
        "Firebase",
        "Signed in and syncing with Cloud Firestore.",
    ),
}
