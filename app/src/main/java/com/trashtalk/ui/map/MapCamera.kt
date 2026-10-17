package com.trashtalk.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.trashtalk.core.LatLng
import com.trashtalk.core.LatLngBounds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraFit
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.map.MapState
import org.maplibre.compose.util.DpPadding
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position
import kotlin.math.cos
import kotlin.time.Duration.Companion.milliseconds

/**
 * The map camera.
 *
 * Previously this file carried its own Mercator projection, a hand-rolled
 * inertial pan and an animation loop, because the renderer underneath it was a
 * Compose Canvas that had no camera of its own. MapLibre has one, and it runs
 * on the render thread rather than through recomposition, so what is left here
 * is a thin translation layer: the app's LatLng vocabulary and its
 * screen-inset-aware framing, expressed in terms of [MapState].
 *
 * Keeping the shim rather than rewriting every call site is deliberate. The
 * app asks the camera for things in the units it thinks in -- "fit this
 * bounding box with the sheet covering the bottom 280 px" -- and that
 * translation has to live somewhere. Here is a smaller somewhere than
 * scattered through the map screen.
 */
class MapCameraState(
    internal val state: MapState,
    private val density: Density,
    private val scope: CoroutineScope,
) {
    val center: LatLng
        get() = state.cameraPosition.center.let { LatLng(it.latitude, it.longitude) }

    val zoom: Float get() = state.cameraPosition.zoom.toFloat()

    val bearing: Float get() = state.cameraPosition.bearing.toFloat()

    /**
     * Ground resolution at the current centre.
     *
     * MapLibre reports metres per *dp*; callers here -- the scale bar in
     * particular -- work in pixels, so this converts. Getting that wrong shows
     * up as a scale bar that is off by the display's density factor, which on
     * this phone would be a silent 2.6x lie.
     */
    fun metersPerPixel(): Double {
        val perDp = state.metersPerDpAtLatitude(center.lat)
            ?: return 156_543.03392 * cos(Math.toRadians(center.lat)) / Math.pow(2.0, zoom.toDouble())
        return perDp / density.density
    }

    /**
     * Frames [bounds], leaving room for the chrome drawn over the map.
     *
     * The insets matter: the top bar and the bottom sheet cover a third of the
     * screen between them, and a fit that ignores them centres the network
     * behind the furniture.
     */
    fun fitBounds(
        bounds: LatLngBounds,
        size: Size,
        padding: Float = 0.06f,
        insetTopPx: Float = 0f,
        insetBottomPx: Float = 0f,
    ) {
        val edge = (size.minDimension * padding) / density.density
        scope.launch {
            state.animateCameraToBounds(
                boundingBox = bounds.toBoundingBox(),
                fit = CameraFit(
                    fitPadding = DpPadding(
                        left = edge.dp,
                        top = (insetTopPx / density.density).dp + edge.dp,
                        right = edge.dp,
                        bottom = (insetBottomPx / density.density).dp + edge.dp,
                    ),
                ),
                animation = CameraAnimation.Ease.Standard,
            )
        }
    }

    /** Moves to [target], optionally changing zoom. */
    suspend fun flyTo(
        target: LatLng,
        targetZoom: Float? = null,
        durationMs: Int = 550,
    ) {
        state.animateCamera(
            update = CameraUpdate(
                center = Position(latitude = target.lat, longitude = target.lng),
                zoom = targetZoom?.toDouble(),
            ),
            animation = CameraAnimation.Ease { duration = durationMs.milliseconds },
        )
    }
}

@Composable
fun rememberMapCamera(state: MapState, scope: CoroutineScope): MapCameraState {
    val density = LocalDensity.current
    return remember(state, density, scope) { MapCameraState(state, density, scope) }
}

/** The app's bounds vocabulary, in the renderer's. */
internal fun LatLngBounds.toBoundingBox(): BoundingBox =
    BoundingBox(west = minLng, south = minLat, east = maxLng, north = maxLat)

/** The starting camera, before anything has been framed. */
internal fun initialCamera(center: LatLng, zoom: Double): CameraPosition =
    CameraPosition(
        center = Position(latitude = center.lat, longitude = center.lng),
        zoom = zoom,
    )
