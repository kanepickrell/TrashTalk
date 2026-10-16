package com.trashtalk.ui.map

import androidx.compose.runtime.Stable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.trashtalk.core.LatLng
import com.trashtalk.core.LatLngBounds
import com.trashtalk.core.Mercator
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.min

/**
 * The map camera: a centre, a zoom, and the arithmetic to move between geography
 * and screen pixels.
 *
 * This is a purpose-built camera rather than a wrapped third-party map, which buys
 * three things the product needs: no API key and therefore no blank grey screen
 * when a key is missing or a quota is spent, complete control of the render so
 * the litter layer can bloom the way the reference Citi Bike maps do, and a
 * basemap that works with no network at all -- which matters because volunteers
 * survey exactly the places where coverage is worst.
 *
 * Zoom follows the slippy-map convention (256 px tiles, zoom 0 = whole world), so
 * swapping in raster tiles later is a drop-in change.
 */
@Stable
class MapCameraState(
    initialCenter: LatLng,
    initialZoom: Float,
    val minZoom: Float = 13f,
    val maxZoom: Float = 19.5f,
    /** Camera is constrained to this envelope so the user cannot get lost. */
    var limit: LatLngBounds? = null,
) {
    private var _lat by mutableDoubleStateOf(initialCenter.lat)
    private var _lng by mutableDoubleStateOf(initialCenter.lng)
    private var _zoom by mutableFloatStateOf(initialZoom)

    val center: LatLng get() = LatLng(_lat, _lng)
    val zoom: Float get() = _zoom

    /** World pixels across the globe at the current zoom. */
    private val worldSize: Double get() = Mercator.worldSizePx(_zoom)

    fun project(point: LatLng, size: Size): Offset {
        val ws = worldSize
        val cx = Mercator.worldX(_lng) * ws
        val cy = Mercator.worldY(_lat) * ws
        return Offset(
            x = (size.width / 2f + (Mercator.worldX(point.lng) * ws - cx)).toFloat(),
            y = (size.height / 2f + (Mercator.worldY(point.lat) * ws - cy)).toFloat(),
        )
    }

    fun unproject(offset: Offset, size: Size): LatLng {
        val ws = worldSize
        val cx = Mercator.worldX(_lng) * ws
        val cy = Mercator.worldY(_lat) * ws
        val wx = (cx + (offset.x - size.width / 2f)) / ws
        val wy = (cy + (offset.y - size.height / 2f)) / ws
        return LatLng(Mercator.lat(wy.coerceIn(0.0, 1.0)), Mercator.lng(wx))
    }

    fun panBy(delta: Offset) {
        val ws = worldSize
        val wx = Mercator.worldX(_lng) - delta.x / ws
        val wy = (Mercator.worldY(_lat) - delta.y / ws).coerceIn(0.0, 1.0)
        setCenter(LatLng(Mercator.lat(wy), Mercator.lng(wx)))
    }

    /** Zooms by [factor], holding the geography under [focus] in place. */
    fun zoomBy(factor: Float, focus: Offset, size: Size) {
        if (factor <= 0f) return
        val anchor = unproject(focus, size)
        val next = (_zoom + log2(factor)).coerceIn(minZoom, maxZoom)
        if (next == _zoom) return
        _zoom = next
        // Re-centre so the anchor lands back under the same pixel.
        val after = project(anchor, size)
        panBy(focus - after)
    }

    fun setZoom(value: Float) {
        _zoom = value.coerceIn(minZoom, maxZoom)
    }

    fun setCenter(point: LatLng) {
        val l = limit
        if (l == null) {
            _lat = point.lat
            _lng = point.lng
        } else {
            _lat = point.lat.coerceIn(l.minLat, l.maxLat)
            _lng = point.lng.coerceIn(l.minLng, l.maxLng)
        }
    }

    /**
     * Frames [bounds] in the part of the viewport that is actually visible.
     *
     * The map draws full-bleed with chrome floating over the top and bottom, so
     * fitting to the raw viewport would centre the neighbourhood underneath the
     * legend. [insetTopPx] and [insetBottomPx] describe how much of the viewport
     * the chrome covers; the fit targets the band between them and then offsets
     * the camera so the content lands inside it.
     */
    fun fitBounds(
        bounds: LatLngBounds,
        size: Size,
        padding: Float = 0.06f,
        insetTopPx: Float = 0f,
        insetBottomPx: Float = 0f,
    ) {
        if (size.width <= 0f || size.height <= 0f) return
        val spanX = (Mercator.worldX(bounds.maxLng) - Mercator.worldX(bounds.minLng))
            .coerceAtLeast(1e-9)
        val spanY = (Mercator.worldY(bounds.minLat) - Mercator.worldY(bounds.maxLat))
            .coerceAtLeast(1e-9)

        val bandH = (size.height - insetTopPx - insetBottomPx).coerceAtLeast(size.height * 0.25f)
        val usableW = size.width * (1f - padding * 2)
        val usableH = bandH * (1f - padding * 2)

        val zx = log2(usableW / (256.0 * spanX))
        val zy = log2(usableH / (256.0 * spanY))
        _zoom = min(zx, zy).toFloat().coerceIn(minZoom, maxZoom)
        setCenter(bounds.center)

        // Shift the content from the viewport centre to the visible band centre.
        val shift = (insetTopPx - insetBottomPx) / 2f
        if (shift != 0f) panBy(Offset(0f, shift))
    }

    /**
     * Eases the camera to a target over [durationMs].
     *
     * Interpolation happens in Mercator world space rather than on raw
     * latitude, so the motion is linear on screen; easing on lat/lng directly
     * would visibly accelerate as it moved north. Zoom is interpolated
     * logarithmically for the same reason -- zoom is already a log scale, so a
     * linear walk through it looks even.
     */
    suspend fun flyTo(
        target: LatLng,
        targetZoom: Float? = null,
        durationMs: Int = 550,
    ) {
        val fromX = Mercator.worldX(_lng)
        val fromY = Mercator.worldY(_lat)
        val toX = Mercator.worldX(target.lng)
        val toY = Mercator.worldY(target.lat)
        val fromZoom = _zoom
        val toZoom = (targetZoom ?: _zoom).coerceIn(minZoom, maxZoom)

        val startNanos = withFrameNanos { it }
        while (true) {
            val nowNanos = withFrameNanos { it }
            val elapsed = (nowNanos - startNanos) / 1_000_000f
            val raw = (elapsed / durationMs).coerceIn(0f, 1f)
            // Ease in-out cubic: starts and lands gently, which reads as the
            // map moving rather than cutting.
            val t = if (raw < 0.5f) {
                4f * raw * raw * raw
            } else {
                1f - (-2f * raw + 2f).let { it * it * it } / 2f
            }
            _zoom = (fromZoom + (toZoom - fromZoom) * t).coerceIn(minZoom, maxZoom)
            setCenter(
                LatLng(
                    Mercator.lat(fromY + (toY - fromY) * t),
                    Mercator.lng(fromX + (toX - fromX) * t),
                ),
            )
            if (raw >= 1f) return
        }
    }

    /** Ground resolution in metres per pixel, for the scale bar. */
    fun metersPerPixel(): Double =
        156_543.03392 * cos(_lat * Math.PI / 180.0) / Math.pow(2.0, _zoom.toDouble())

    /** The geography currently on screen, used to cull off-screen work. */
    fun visibleBounds(size: Size): LatLngBounds {
        val nw = unproject(Offset.Zero, size)
        val se = unproject(Offset(size.width, size.height), size)
        return LatLngBounds(
            minLat = min(nw.lat, se.lat),
            minLng = min(nw.lng, se.lng),
            maxLat = maxOf(nw.lat, se.lat),
            maxLng = maxOf(nw.lng, se.lng),
        )
    }

    companion object {
        /** Natural logarithm helper kept local so the call sites read cleanly. */
        internal fun ln2(v: Double) = ln(v) / ln(2.0)
    }
}
