package com.trashtalk.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/** A WGS84 coordinate. */
data class LatLng(val lat: Double, val lng: Double)

/** An axis-aligned geographic rectangle. */
data class LatLngBounds(
    val minLat: Double,
    val minLng: Double,
    val maxLat: Double,
    val maxLng: Double,
) {
    val center: LatLng get() = LatLng((minLat + maxLat) / 2.0, (minLng + maxLng) / 2.0)

    fun pad(fraction: Double): LatLngBounds {
        val dLat = (maxLat - minLat) * fraction
        val dLng = (maxLng - minLng) * fraction
        return LatLngBounds(minLat - dLat, minLng - dLng, maxLat + dLat, maxLng + dLng)
    }

    fun contains(p: LatLng): Boolean =
        p.lat in minLat..maxLat && p.lng in minLng..maxLng

    companion object {
        fun of(points: List<LatLng>): LatLngBounds {
            require(points.isNotEmpty()) { "cannot bound an empty point set" }
            var a = points.first().lat
            var b = points.first().lat
            var c = points.first().lng
            var d = points.first().lng
            for (p in points) {
                a = min(a, p.lat); b = max(b, p.lat)
                c = min(c, p.lng); d = max(d, p.lng)
            }
            return LatLngBounds(a, c, b, d)
        }
    }
}

/**
 * Spherical Web Mercator (EPSG:3857) normalised to the unit square, which is the
 * projection every slippy map uses. World coordinates run 0..1 with (0,0) at the
 * north-west corner, so screen placement is a single multiply by the world pixel
 * size at the current zoom.
 */
object Mercator {
    const val MAX_LAT = 85.05112878

    fun worldX(lng: Double): Double = (lng + 180.0) / 360.0

    fun worldY(lat: Double): Double {
        val clamped = lat.coerceIn(-MAX_LAT, MAX_LAT)
        val s = sin(clamped * PI / 180.0)
        return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
    }

    fun lng(worldX: Double): Double = worldX * 360.0 - 180.0

    fun lat(worldY: Double): Double {
        val n = PI - 2.0 * PI * worldY
        return 180.0 / PI * atan(sinh(n))
    }

    /** World pixels across the whole globe at [zoom], using the 256px tile convention. */
    fun worldSizePx(zoom: Float): Double = 256.0 * pow2(zoom)

    private fun pow2(z: Float): Double = exp(z.toDouble() * LN2)

    private const val LN2 = 0.6931471805599453
}

private const val EARTH_RADIUS_M = 6_371_008.8

/** Great-circle distance in metres. */
fun haversineMeters(a: LatLng, b: LatLng): Double {
    val dLat = (b.lat - a.lat) * PI / 180.0
    val dLng = (b.lng - a.lng) * PI / 180.0
    val la1 = a.lat * PI / 180.0
    val la2 = b.lat * PI / 180.0
    val h = sin(dLat / 2).let { it * it } + cos(la1) * cos(la2) * sin(dLng / 2).let { it * it }
    return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
}

/**
 * Distance in metres from [p] to the segment [a]-[b].
 *
 * Projects into a local metres-per-degree frame first; over a neighbourhood this
 * is indistinguishable from a geodesic solution and far cheaper, which matters
 * because this runs against every segment on every map tap.
 */
fun pointToSegmentMeters(p: LatLng, a: LatLng, b: LatLng): Double {
    val mPerLat = 110_574.0
    val mPerLng = 111_320.0 * cos(p.lat * PI / 180.0)
    val px = p.lat * mPerLat
    val py = p.lng * mPerLng
    val ax = a.lat * mPerLat
    val ay = a.lng * mPerLng
    val bx = b.lat * mPerLat
    val by = b.lng * mPerLng
    val vx = bx - ax
    val vy = by - ay
    val wx = px - ax
    val wy = py - ay
    val len2 = vx * vx + vy * vy
    val t = if (len2 == 0.0) 0.0 else ((wx * vx + wy * vy) / len2).coerceIn(0.0, 1.0)
    val cx = ax + t * vx
    val cy = ay + t * vy
    return sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
}

/** Shortest distance in metres from [p] to a polyline. */
fun distanceToPolylineMeters(p: LatLng, poly: List<LatLng>): Double {
    if (poly.isEmpty()) return Double.MAX_VALUE
    if (poly.size == 1) return haversineMeters(p, poly[0])
    var best = Double.MAX_VALUE
    for (i in 0 until poly.size - 1) {
        best = min(best, pointToSegmentMeters(p, poly[i], poly[i + 1]))
    }
    return best
}

/** Total length of a polyline in metres. */
fun polylineLengthMeters(poly: List<LatLng>): Double {
    var total = 0.0
    for (i in 0 until poly.size - 1) total += haversineMeters(poly[i], poly[i + 1])
    return total
}

/** The point on a polyline at [fraction] of its length, used to place markers. */
fun pointAlongPolyline(poly: List<LatLng>, fraction: Double): LatLng {
    if (poly.isEmpty()) return LatLng(0.0, 0.0)
    if (poly.size == 1) return poly[0]
    val target = polylineLengthMeters(poly) * fraction.coerceIn(0.0, 1.0)
    var walked = 0.0
    for (i in 0 until poly.size - 1) {
        val d = haversineMeters(poly[i], poly[i + 1])
        if (walked + d >= target && d > 0) {
            val t = (target - walked) / d
            return LatLng(
                poly[i].lat + (poly[i + 1].lat - poly[i].lat) * t,
                poly[i].lng + (poly[i + 1].lng - poly[i].lng) * t,
            )
        }
        walked += d
    }
    return poly.last()
}

/** Compass bearing of a polyline's dominant direction, in degrees. */
fun polylineBearing(poly: List<LatLng>): Double {
    if (poly.size < 2) return 0.0
    val a = poly.first()
    val b = poly.last()
    val dy = (b.lat - a.lat)
    val dx = (b.lng - a.lng) * cos(a.lat * PI / 180.0)
    return (Math.toDegrees(kotlin.math.atan2(dx, dy)) + 360.0) % 360.0
}

/** Formats a distance for display, switching units where precision stops helping. */
fun formatDistance(meters: Double): String = when {
    meters < 10 -> "${meters.toInt()} m"
    meters < 1000 -> "${(meters / 5).toInt() * 5} m"
    else -> String.format("%.1f km", meters / 1000.0)
}

/** True when two headings are within [toleranceDeg] of each other, modulo 180. */
fun headingsAlign(a: Double, b: Double, toleranceDeg: Double): Boolean {
    val d = abs(((a - b) % 180.0 + 180.0) % 180.0)
    return min(d, 180.0 - d) <= toleranceDeg
}

/** Mercator scale distortion at a latitude; keeps on-screen metre scales honest. */
fun mercatorScaleAt(lat: Double): Double = 1.0 / cos(lat.coerceIn(-85.0, 85.0) * PI / 180.0)

internal fun tanDeg(deg: Double): Double = tan(deg * PI / 180.0)
