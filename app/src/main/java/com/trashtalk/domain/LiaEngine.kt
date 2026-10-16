package com.trashtalk.domain

import com.trashtalk.core.LatLng
import com.trashtalk.core.haversineMeters
import com.trashtalk.data.model.CollectionEvent
import com.trashtalk.data.model.LitterType
import com.trashtalk.data.model.Poi
import com.trashtalk.data.model.Segment
import com.trashtalk.data.model.StreetClass
import com.trashtalk.ui.theme.HazardBand
import kotlin.math.roundToInt

/**
 * A Litter Impact Assessment: the artefact that turns a pile of pickups into
 * something a city council, a business improvement district or a researcher can
 * act on.
 *
 * Every figure here is normalised and carries its own uncertainty, because the
 * assessment is only useful if it survives being checked. Three rules shape it:
 *
 *  - Density over counts. Pieces per 100 m lets a 90 m cul-de-sac and a 600 m
 *    arterial sit in the same table honestly.
 *  - Coverage is reported, never hidden. A confident number over 12% of the
 *    network is a worse input than a hedged number over 80%, and the reader is
 *    entitled to know which one they are holding.
 *  - Drivers, not blame. The distance-decay analysis points at the land uses that
 *    generate the litter, which is where an intervention can actually be aimed.
 */
object LiaEngine {

    /** How many categorical slots the composition chart shows before folding. */
    private const val COMPOSITION_SLOTS = 7

    data class Share(
        val label: String,
        val share: Float,
        val pieces: Int,
        val isOther: Boolean = false,
    )

    data class BandSlice(
        val band: HazardBand,
        val segments: Int,
        val meters: Double,
        val share: Float,
    )

    data class HotspotRow(
        val segment: Segment,
        val piecesPer100m: Double,
        val rank: Int,
    )

    data class DriverRow(
        val label: String,
        val distanceBand: String,
        val meanIndex: Float,
        val segments: Int,
    )

    data class ClassRow(
        val streetClass: StreetClass,
        val meanIndex: Float,
        val piecesPer100m: Double,
        val meters: Double,
        val segments: Int,
    )

    data class Report(
        val neighborhood: String,
        val city: String,
        val generatedAt: Long,

        // Coverage and confidence
        val totalSegments: Int,
        val surveyedSegments: Int,
        val coverageFraction: Float,
        val networkMeters: Double,
        val surveyedMeters: Double,
        val confidence: String,
        val confidenceNote: String,

        // Headline load
        val estimatedPieces: Int,
        val estimatedKilograms: Double,
        val meanPiecesPer100m: Double,
        val medianLitterIndex: Float,

        // Distributions
        val bands: List<BandSlice>,
        val composition: List<Share>,
        val singleUsePlasticShare: Float,
        val byClass: List<ClassRow>,

        // Movement
        val trend: List<Float>,
        val improvingSegments: Int,
        val worseningSegments: Int,
        val netChange: Float,

        // Where and why
        val hotspots: List<HotspotRow>,
        val drivers: List<DriverRow>,
        val recommendations: List<String>,

        // Volunteer effort actually recorded
        val loggedPieces: Int,
        val loggedCollections: Int,
        val volunteerMinutes: Int,
    ) {
        val annualisedPieces: Int
            get() = (estimatedPieces * 365.0 / 14.0).roundToInt()
    }

    fun build(
        neighborhood: String,
        city: String,
        segments: List<Segment>,
        pois: List<Poi>,
        collections: List<CollectionEvent>,
        now: Long = System.currentTimeMillis(),
    ): Report {
        if (segments.isEmpty()) {
            return emptyReport(neighborhood, city, now)
        }

        val networkMeters = segments.sumOf { it.lengthMeters }
        val surveyed = segments.filter { it.hasBeenSurveyed }
        val surveyedMeters = surveyed.sumOf { it.lengthMeters }
        val coverage = (surveyed.size.toFloat() / segments.size).coerceIn(0f, 1f)

        val pieces = segments.sumOf { it.estimatedPieces }
        val indices = segments.map { it.litterIndex }.sorted()
        val median = indices[indices.size / 2]

        // Mass uses the measured composition rather than one average piece weight,
        // because a stream that is 30% glass by count is an entirely different
        // tonnage from one that is 30% film.
        val comp = aggregateComposition(segments)
        val gramsPerPiece = comp.entries.sumOf { (type, share) ->
            type.avgGrams * share
        }.takeIf { it > 0 } ?: 12.0
        val kilograms = pieces * gramsPerPiece / 1000.0

        val bands = buildBands(segments, networkMeters)
        val composition = buildCompositionShares(comp, pieces)
        val singleUse = comp.entries
            .filter { it.key.isSingleUsePlastic }
            .sumOf { it.value.toDouble() }
            .toFloat()
            .coerceIn(0f, 1f)

        val trend = buildTrend(segments)
        val improving = segments.count { it.sevenDayChange < -0.02f }
        val worsening = segments.count { it.sevenDayChange > 0.02f }
        val netChange = if (trend.size >= 2) trend.last() - trend.first() else 0f

        val hotspots = segments
            .sortedByDescending { it.piecesPer100m }
            .take(8)
            .mapIndexed { i, s -> HotspotRow(s, s.piecesPer100m, i + 1) }

        val drivers = buildDrivers(segments, pois)
        val byClass = buildClassRows(segments)

        val confidence = when {
            coverage >= 0.70f -> "High"
            coverage >= 0.35f -> "Moderate"
            else -> "Indicative"
        }
        val confidenceNote = when (confidence) {
            "High" -> "Most of the network has been walked at least once; density " +
                "figures are usable for prioritising work."
            "Moderate" -> "Coverage is partial. Hotspot ranking is reliable; " +
                "neighbourhood totals should be read as a lower bound."
            else -> "Coverage is too thin for totals. Treat this as a scouting " +
                "report: the hotspots found are real, the ones missing are unknown."
        }

        return Report(
            neighborhood = neighborhood,
            city = city,
            generatedAt = now,
            totalSegments = segments.size,
            surveyedSegments = surveyed.size,
            coverageFraction = coverage,
            networkMeters = networkMeters,
            surveyedMeters = surveyedMeters,
            confidence = confidence,
            confidenceNote = confidenceNote,
            estimatedPieces = pieces,
            estimatedKilograms = kilograms,
            meanPiecesPer100m = if (networkMeters > 0) pieces / networkMeters * 100.0 else 0.0,
            medianLitterIndex = median,
            bands = bands,
            composition = composition,
            singleUsePlasticShare = singleUse,
            byClass = byClass,
            trend = trend,
            improvingSegments = improving,
            worseningSegments = worsening,
            netChange = netChange,
            hotspots = hotspots,
            drivers = drivers,
            recommendations = buildRecommendations(drivers, composition, singleUse, hotspots, coverage),
            loggedPieces = collections.sumOf { it.pieces },
            loggedCollections = collections.size,
            volunteerMinutes = collections.sumOf { it.minutes },
        )
    }

    /** Piece-weighted composition across the network, as type -> share. */
    private fun aggregateComposition(segments: List<Segment>): Map<LitterType, Float> {
        val totals = mutableMapOf<LitterType, Double>()
        for (s in segments) {
            val weight = s.estimatedPieces.toDouble()
            if (weight <= 0) continue
            for ((key, share) in s.composition) {
                val type = runCatching { LitterType.valueOf(key) }.getOrNull() ?: continue
                totals[type] = (totals[type] ?: 0.0) + share * weight
            }
        }
        val sum = totals.values.sum()
        if (sum <= 0) return emptyMap()
        return totals.mapValues { (it.value / sum).toFloat() }
    }

    /**
     * Composition for display: the seven largest types keep their identity and the
     * tail folds into "Other", because a categorical palette past eight slots stops
     * being readable regardless of how carefully it was chosen.
     */
    private fun buildCompositionShares(
        comp: Map<LitterType, Float>,
        totalPieces: Int,
    ): List<Share> {
        if (comp.isEmpty()) return emptyList()
        val sorted = comp.entries.sortedByDescending { it.value }
        val head = sorted.take(COMPOSITION_SLOTS)
        val tail = sorted.drop(COMPOSITION_SLOTS)
        val rows = head.map {
            Share(it.key.label, it.value, (it.value * totalPieces).roundToInt())
        }.toMutableList()
        if (tail.isNotEmpty()) {
            val share = tail.sumOf { it.value.toDouble() }.toFloat()
            rows += Share("Other", share, (share * totalPieces).roundToInt(), isOther = true)
        }
        return rows
    }

    private fun buildBands(segments: List<Segment>, networkMeters: Double): List<BandSlice> =
        HazardBand.entries.map { band ->
            val inBand = segments.filter { HazardBand.of(it.litterIndex) == band }
            val meters = inBand.sumOf { it.lengthMeters }
            BandSlice(
                band = band,
                segments = inBand.size,
                meters = meters,
                share = if (networkMeters > 0) (meters / networkMeters).toFloat() else 0f,
            )
        }

    /** Network-mean litter index per day of the history window. */
    private fun buildTrend(segments: List<Segment>): List<Float> {
        val days = segments.maxOfOrNull { it.history.size } ?: 0
        if (days == 0) return emptyList()
        return (0 until days).map { day ->
            val weighted = segments.sumOf { it.indexAt(day).toDouble() * it.lengthMeters }
            val total = segments.sumOf { it.lengthMeters }
            if (total > 0) (weighted / total).toFloat() else 0f
        }
    }

    /**
     * Distance-decay against each class of litter generator.
     *
     * This is the analysis that makes the report actionable: if mean density within
     * 100 m of transit is three times the figure beyond 400 m, the intervention is
     * a bin and a service schedule at the stop, not a guilt campaign aimed at the
     * whole neighbourhood.
     */
    private fun buildDrivers(segments: List<Segment>, pois: List<Poi>): List<DriverRow> {
        if (pois.isEmpty()) return emptyList()
        val bandsM = listOf(0.0 to 100.0, 100.0 to 200.0, 200.0 to 400.0, 400.0 to Double.MAX_VALUE)
        val bandLabels = listOf("within 100 m", "100-200 m", "200-400 m", "beyond 400 m")

        val rows = mutableListOf<DriverRow>()
        for (kind in pois.map { it.kind }.distinct().sorted()) {
            val of = pois.filter { it.kind == kind }
            if (of.isEmpty()) continue
            for ((i, range) in bandsM.withIndex()) {
                val inBand = segments.filter { seg ->
                    val d = of.minOf { haversineMeters(seg.midpoint, it.location) }
                    d >= range.first && d < range.second
                }
                if (inBand.size < 3) continue
                rows += DriverRow(
                    label = kind.replaceFirstChar { it.uppercase() },
                    distanceBand = bandLabels[i],
                    meanIndex = inBand.map { it.litterIndex }.average().toFloat(),
                    segments = inBand.size,
                )
            }
        }
        return rows
    }

    private fun buildClassRows(segments: List<Segment>): List<ClassRow> =
        StreetClass.entries.mapNotNull { cls ->
            val of = segments.filter { it.streetClass == cls }
            if (of.isEmpty()) return@mapNotNull null
            val meters = of.sumOf { it.lengthMeters }
            val pieces = of.sumOf { it.estimatedPieces }
            ClassRow(
                streetClass = cls,
                meanIndex = of.map { it.litterIndex }.average().toFloat(),
                piecesPer100m = if (meters > 0) pieces / meters * 100.0 else 0.0,
                meters = meters,
                segments = of.size,
            )
        }

    /**
     * Plain-language next steps. Generated from the data rather than canned, so the
     * recommendations change when the neighbourhood does.
     */
    private fun buildRecommendations(
        drivers: List<DriverRow>,
        composition: List<Share>,
        singleUseShare: Float,
        hotspots: List<HotspotRow>,
        coverage: Float,
    ): List<String> {
        val out = mutableListOf<String>()

        // Which generator shows the sharpest gradient between its nearest and
        // furthest band? That is the one worth naming first.
        val byKind = drivers.groupBy { it.label }
        val gradients = byKind.mapNotNull { (kind, rows) ->
            val near = rows.firstOrNull { it.distanceBand == "within 100 m" }
            val far = rows.lastOrNull()
            if (near == null || far == null || far == near) null
            else Triple(kind, near.meanIndex - far.meanIndex, near.meanIndex / far.meanIndex.coerceAtLeast(0.01f))
        }.sortedByDescending { it.second }

        gradients.firstOrNull()?.let { (kind, _, ratio) ->
            out += "Litter density within 100 m of %s sites runs %.1fx the level beyond 400 m. Service those frontages first: a serviced bin at the generator beats a cleanup downstream of it."
                .format(kind.lowercase(), ratio)
        }

        if (singleUseShare > 0.5f) {
            out += "Single-use plastics are %.0f%% of the stream by count. This is a packaging problem arriving on the street, not a sweeping problem: the Advocate path exists to take that number to the businesses on these blocks."
                .format(singleUseShare * 100)
        }

        composition.firstOrNull()?.let { top ->
            out += "%s alone accounts for %.0f%% of pieces. A single targeted intervention on that one item would move the headline figure more than a general campaign."
                .format(top.label, top.share * 100)
        }

        hotspots.take(3).takeIf { it.isNotEmpty() }?.let { top ->
            out += "Three segments carry the load: ${top.joinToString(", ") { it.segment.name }}. Adopt them under Protect the Flag before widening coverage."
        }

        if (coverage < 0.35f) {
            out += "Coverage is only %.0f%% of segments. The fastest way to improve this assessment is breadth, not depth: the Scientist path pays a bonus for first surveys precisely here."
                .format(coverage * 100)
        }

        return out
    }

    private fun emptyReport(neighborhood: String, city: String, now: Long) = Report(
        neighborhood = neighborhood,
        city = city,
        generatedAt = now,
        totalSegments = 0,
        surveyedSegments = 0,
        coverageFraction = 0f,
        networkMeters = 0.0,
        surveyedMeters = 0.0,
        confidence = "Indicative",
        confidenceNote = "No segments loaded yet.",
        estimatedPieces = 0,
        estimatedKilograms = 0.0,
        meanPiecesPer100m = 0.0,
        medianLitterIndex = 0f,
        bands = emptyList(),
        composition = emptyList(),
        singleUsePlasticShare = 0f,
        byClass = emptyList(),
        trend = emptyList(),
        improvingSegments = 0,
        worseningSegments = 0,
        netChange = 0f,
        hotspots = emptyList(),
        drivers = emptyList(),
        recommendations = emptyList(),
        loggedPieces = 0,
        loggedCollections = 0,
        volunteerMinutes = 0,
    )

    /** Nearest generator to a point, used by the segment sheet to explain a hotspot. */
    fun nearestGenerator(point: LatLng, pois: List<Poi>): Pair<Poi, Double>? =
        pois.minByOrNull { haversineMeters(point, it.location) }
            ?.let { it to haversineMeters(point, it.location) }
}
