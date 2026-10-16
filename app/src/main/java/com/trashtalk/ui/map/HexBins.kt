package com.trashtalk.ui.map

import com.trashtalk.core.LatLng
import com.trashtalk.core.Mercator
import com.trashtalk.data.model.Segment
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Hexagonal binning for the litter layer.
 *
 * Why bins rather than the raw segments: a street network drawn as lines answers
 * "which block is bad", but it is a poor way to see *where the problem is* at a
 * glance, because the eye has to integrate hundreds of thin strokes. Binning the
 * same measurements into equal-area cells turns the question into a surface --
 * the hot corridor becomes an obvious blob rather than a set of lines you have to
 * trace. Hexagons specifically, because square bins create visual rows and
 * columns that are artefacts of the grid rather than the data, and every hexagon
 * has six equidistant neighbours instead of four near and four far.
 *
 * Bins are defined in Mercator world units from a fixed geographic size, so a
 * cell covers the same ground regardless of zoom or pan. Zooming reveals detail
 * by showing more of the same bins, never by silently re-binning underneath the
 * reader -- which would make two screenshots of the same place incomparable.
 */
object HexBins {

    /** Equatorial circumference in metres: one full world unit of Mercator x. */
    private const val WORLD_METERS = 40_075_016.686

    /** A single aggregated cell. */
    class Bin(
        val q: Int,
        val r: Int,
        /** Centre in world units (0..1). */
        val worldX: Double,
        val worldY: Double,
        val center: LatLng,
        /** Estimated pieces of litter attributed to this cell. */
        val pieces: Double,
        /** Length-weighted mean litter index of the segments inside. */
        val meanIndex: Float,
        val segmentCount: Int,
        /** Index into the class breaks, 0-based. */
        var classIndex: Int = 0,
    )

    /** A binned surface plus the breaks used to classify it. */
    class Surface(
        val bins: List<Bin>,
        /** Upper bound of each class; the last is the maximum. */
        val breaks: List<Double>,
        val hexRadiusWorld: Double,
        val totalPieces: Double,
    ) {
        val isEmpty: Boolean get() = bins.isEmpty()

        /** Human-readable range for a class, e.g. "201 to 514". */
        fun rangeLabel(classIndex: Int): String {
            val lower = if (classIndex == 0) 1.0 else breaks[classIndex - 1]
            val upper = breaks[classIndex]
            return if (classIndex == breaks.lastIndex) {
                "${fmt(lower)}+"
            } else {
                "${fmt(lower)} to ${fmt(upper)}"
            }
        }

        private fun fmt(v: Double): String = when {
            v >= 1000 -> "%.1fK".format(v / 1000)
            else -> v.roundToInt().toString()
        }
    }

    /**
     * Builds the surface.
     *
     * [hexMeters] is the centre-to-vertex radius on the ground. 110 m puts
     * roughly one cell per city block at this neighbourhood's grid spacing,
     * which is the resolution at which a cleanup crew can act.
     */
    fun build(
        segments: List<Segment>,
        referenceLat: Double,
        hexMeters: Double = 110.0,
        classes: Int = 5,
        valueOf: (Segment) -> Float = { it.litterIndex },
        weightOf: (Segment) -> Double = { it.estimatedPieces.toDouble() },
    ): Surface {
        if (segments.isEmpty()) return Surface(emptyList(), listOf(1.0), 0.0, 0.0)

        // Metres to world units, taken at the neighbourhood's latitude. Mercator
        // is conformal, so locally the same factor applies to both axes.
        val worldPerMeter = 1.0 / (WORLD_METERS * cos(Math.toRadians(referenceLat)))
        val size = hexMeters * worldPerMeter

        class Acc {
            var pieces = 0.0
            var indexWeighted = 0.0
            var meters = 0.0
            val segs = HashSet<String>()
        }

        val acc = HashMap<Long, Acc>()

        for (seg in segments) {
            if (seg.path.size < 2) continue
            val totalPieces = weightOf(seg)
            val index = valueOf(seg)

            // Walk the segment, dropping its load into whichever cell each step
            // falls in. Assigning the whole segment to its midpoint would put a
            // 400 m arterial entirely in one cell and leave holes either side.
            val samples = sampleAlong(seg, stepMeters = hexMeters / 2.2)
            if (samples.isEmpty()) continue
            val perSample = totalPieces / samples.size
            val metersPerSample = seg.lengthMeters / samples.size

            for (point in samples) {
                val wx = Mercator.worldX(point.lng)
                val wy = Mercator.worldY(point.lat)
                val (q, r) = worldToAxial(wx, wy, size)
                val key = packed(q, r)
                val a = acc.getOrPut(key) { Acc() }
                a.pieces += perSample
                a.indexWeighted += index * metersPerSample
                a.meters += metersPerSample
                a.segs += seg.id
            }
        }

        val bins = acc.entries.map { (key, a) ->
            val q = unpackQ(key)
            val r = unpackR(key)
            val (wx, wy) = axialToWorld(q, r, size)
            Bin(
                q = q,
                r = r,
                worldX = wx,
                worldY = wy,
                center = LatLng(Mercator.lat(wy), Mercator.lng(wx)),
                pieces = a.pieces,
                meanIndex = if (a.meters > 0) (a.indexWeighted / a.meters).toFloat() else 0f,
                segmentCount = a.segs.size,
            )
        }.filter { it.pieces >= 0.5 }

        val breaks = quantileBreaks(bins.map { it.pieces }, classes)
        for (bin in bins) {
            bin.classIndex = breaks.indexOfFirst { bin.pieces <= it }
                .let { if (it < 0) breaks.lastIndex else it }
        }

        return Surface(
            bins = bins,
            breaks = breaks,
            hexRadiusWorld = size,
            totalPieces = bins.sumOf { it.pieces },
        )
    }

    /**
     * Quantile class breaks.
     *
     * Litter is heavily right-skewed -- most blocks are nearly clean and a
     * handful are dreadful -- so equal-interval breaks would put ninety per cent
     * of cells in the lowest class and render a map that is one flat colour.
     * Quantiles spend the colour range where the data actually is.
     */
    private fun quantileBreaks(values: List<Double>, classes: Int): List<Double> {
        if (values.isEmpty()) return listOf(1.0)
        val sorted = values.sorted()
        val out = ArrayList<Double>(classes)
        for (i in 1..classes) {
            val idx = ((sorted.size - 1) * i.toDouble() / classes).roundToInt()
            val v = sorted[idx.coerceIn(0, sorted.lastIndex)]
            // Breaks must be strictly increasing or two classes would collapse.
            out += if (out.isNotEmpty() && v <= out.last()) out.last() + 1e-6 else v
        }
        return out
    }

    /** Points spaced along a segment's polyline, in metres. */
    private fun sampleAlong(seg: Segment, stepMeters: Double): List<LatLng> {
        val length = seg.lengthMeters
        if (length <= 0) return listOf(seg.midpoint)
        val count = (length / stepMeters).toInt().coerceIn(1, 64)
        return (0 until count).map { i ->
            com.trashtalk.core.pointAlongPolyline(seg.path, (i + 0.5) / count)
        }
    }

    // ------------------------------------------------------- hex arithmetic

    /**
     * Pointy-top axial coordinates. Standard formulation: the grid is skewed so
     * every cell has six equidistant neighbours, then cube-rounded so a point
     * lands in exactly one cell with no gaps or overlaps along the seams.
     */
    private fun worldToAxial(x: Double, y: Double, size: Double): Pair<Int, Int> {
        val qf = (sqrt(3.0) / 3.0 * x - 1.0 / 3.0 * y) / size
        val rf = (2.0 / 3.0 * y) / size
        return cubeRound(qf, rf)
    }

    private fun axialToWorld(q: Int, r: Int, size: Double): Pair<Double, Double> {
        val x = size * sqrt(3.0) * (q + r / 2.0)
        val y = size * 1.5 * r
        return x to y
    }

    private fun cubeRound(qf: Double, rf: Double): Pair<Int, Int> {
        val sf = -qf - rf
        var q = Math.round(qf).toInt()
        var r = Math.round(rf).toInt()
        val s = Math.round(sf).toInt()
        val dq = abs(q - qf)
        val dr = abs(r - rf)
        val ds = abs(s - sf)
        if (dq > dr && dq > ds) {
            q = -r - s
        } else if (dr > ds) {
            r = -q - s
        }
        return q to r
    }

    /** The six corners of a pointy-top hexagon, in world units. */
    fun corners(bin: Bin, size: Double): List<Pair<Double, Double>> =
        (0 until 6).map { i ->
            val angle = Math.toRadians(60.0 * i - 30.0)
            bin.worldX + size * cos(angle) to bin.worldY + size * kotlin.math.sin(angle)
        }

    private fun packed(q: Int, r: Int): Long =
        (q.toLong() and 0xFFFFFFFFL) shl 32 or (r.toLong() and 0xFFFFFFFFL)

    private fun unpackQ(key: Long): Int = (key shr 32).toInt()

    private fun unpackR(key: Long): Int = key.toInt()
}
