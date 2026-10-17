package com.trashtalk.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.trashtalk.core.DeviceFix
import com.trashtalk.core.LatLng
import com.trashtalk.data.model.CollectionEvent
import com.trashtalk.data.model.MapFlag
import com.trashtalk.data.model.Poi
import com.trashtalk.data.model.Segment
import com.trashtalk.ui.theme.TT
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MapUiOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.style.BaseStyle

/** Which quantity the data layer is painting. */
enum class MapLayer(val label: String, val caption: String) {
    DENSITY("Litter density", "Pieces on the ground now, binned by area"),
    TERRITORY("Territory", "Which crew holds which blocks, and what is still wild"),
    CHANGE("7-day change", "Which blocks are getting better or worse"),
    ACTIVITY("Cleanup activity", "Where people are actually working"),
}

/** Everything the map draws. */
data class MapData(
    val segments: List<Segment> = emptyList(),
    val collections: List<CollectionEvent> = emptyList(),
    val flags: List<MapFlag> = emptyList(),
    val pois: List<Poi> = emptyList(),
    /** Stable crew colours, so a team owns the same hue everywhere in the app. */
    val teamColors: Map<String, Color> = emptyMap(),
    /** The signed-in user, whose own holdings are drawn in the brand teal. */
    val currentUserId: String? = null,
)

/**
 * The map, its camera, and the hex surface it last built.
 *
 * MapLibre wants the layer declarations handed to the state rather than nested
 * under the view, because the style is owned by the render thread and outlives
 * any one composition. That inverts the usual Compose shape, so the pieces the
 * map screen needs -- a camera to fly, a surface to draw a legend from -- come
 * back through this holder instead of being passed down into the view.
 */
class SegmentMapHolder internal constructor(
    val state: MapState,
    val camera: MapCameraState,
    internal val surfaceState: androidx.compose.runtime.State<HexBins.Surface>,
) {
    /** The binned density surface, for the legend and the tap readout. */
    val surface: HexBins.Surface get() = surfaceState.value
}

/**
 * Builds the litter map.
 *
 * A Protomaps vector basemap bundled in the APK, with the app's data drawn over
 * it as MapLibre layers. The data layer changes shape with the question being
 * asked -- density is binned into hexagons, because a surface answers "where is
 * the problem" far faster than a few hundred thin strokes; territory, change and
 * activity stay on the segments, because those are properties *of a block* and
 * binning them would blur the thing being asked about.
 *
 * Nothing here needs a network. The basemap, the glyphs and the street geometry
 * all ship in the app, which matters because volunteers survey exactly the
 * places with the worst coverage.
 *
 * **Where the work happens.** Serialising the network is the expensive step, and
 * it runs off the main thread keyed on the data rather than the camera, so
 * panning and zooming never rebuild a feature. Switching between the four
 * questions only swaps style expressions on layers that are already uploaded.
 */
@Composable
fun rememberSegmentMap(
    basemap: BaseStyle,
    data: MapData,
    layer: MapLayer,
    initialCenter: LatLng,
    initialZoom: Double = 15.0,
    scrubDay: Int? = null,
    selectedSegmentId: String? = null,
    plannedSegmentIds: Set<String> = emptySet(),
    showMarkers: Boolean = true,
    onSegmentTap: (Segment) -> Unit = {},
    onBinTap: (HexBins.Bin?) -> Unit = {},
): SegmentMapHolder {
    // Binning is independent of the camera: the cells cover fixed ground, so
    // panning and zooming never re-bin underneath the reader.
    val surface = remember(data.segments, layer, scrubDay) {
        if (layer != MapLayer.DENSITY) {
            HexBins.Surface(emptyList(), listOf(1.0), 0.0, 0.0)
        } else {
            HexBins.build(
                segments = data.segments,
                referenceLat = data.segments.firstOrNull()?.midpoint?.lat ?: initialCenter.lat,
                valueOf = { it.indexOn(scrubDay) },
                weightOf = { it.piecesOn(scrubDay) },
            )
        }
    }

    // Serialising three hundred polylines is milliseconds, not microseconds,
    // and it would otherwise land on the frame that shows the map.
    val collections by produceState<MapSources.Collections?>(null, data, surface) {
        value = MapSources.buildAsync(data, surface)
    }

    // The style content closes over these, and maplibre-compose keeps the
    // lambda updated, so a new layer selection or a new upload lands without
    // recreating the map.
    val currentLayer by rememberUpdatedState(layer)
    val currentSelected by rememberUpdatedState(selectedSegmentId)
    val currentPlanned by rememberUpdatedState(plannedSegmentIds)
    val currentMarkers by rememberUpdatedState(showMarkers)
    val currentTap by rememberUpdatedState(onSegmentTap)
    val currentBinTap by rememberUpdatedState(onBinTap)
    val currentSurface by rememberUpdatedState(surface)
    val byId = remember(data.segments) { data.segments.associateBy { it.id } }

    val state = rememberMapState(
        baseStyle = basemap,
        initialCameraPosition = initialCamera(initialCenter, initialZoom),
    ) {
        collections?.let {
            MapDataLayers(
                collections = it,
                layer = currentLayer,
                showMarkers = currentMarkers,
                selectedSegmentId = currentSelected,
                plannedSegmentIds = currentPlanned,
                onSegmentTap = { sid -> byId[sid]?.let(currentTap) },
                onBinTap = { q, r ->
                    currentBinTap(currentSurface.bins.firstOrNull { it.q == q && it.r == r })
                },
            )
        }
    }

    val scope = rememberCoroutineScope()
    val camera = rememberMapCamera(state, scope)
    val surfaceHolder = rememberUpdatedState(surface)

    return remember(state, camera) { SegmentMapHolder(state, camera, surfaceHolder) }
}

/**
 * Draws the map built by [rememberSegmentMap].
 */
@Composable
fun SegmentMap(
    holder: SegmentMapHolder,
    modifier: Modifier = Modifier,
    /** Fired when the user drives the camera, so follow-me can release. */
    onCameraMoved: () -> Unit = {},
) {
    val moved by rememberUpdatedState(onCameraMoved)

    // A camera move the user made, as opposed to one we made, releases follow
    // mode. The renderer reports the reason, so there is no need to guess from
    // timing the way the old gesture handler had to.
    LaunchedEffect(holder.state) {
        snapshotFlow { holder.state.isCameraMoving to holder.state.cameraMoveReason }
            .collect { (moving, reason) ->
                if (moving && reason == CameraMoveReason.Gesture) moved()
            }
    }

    Box(modifier.background(TT.MapGround)) {
        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            state = holder.state,
            // The ground colour rather than transparent, so the first frame is
            // the map's own paper instead of whatever is behind it.
            uiOptions = MapUiOptions(MapUiOptions.Standard) { loadColor = TT.MapGround },
        )
    }
}

/** Litter index on the scrubbed day, falling back to live. */
private fun Segment.indexOn(day: Int?): Float =
    if (day == null || history.isEmpty()) litterIndex
    else history.getOrElse(day.coerceIn(0, history.lastIndex)) { litterIndex }

/** Estimated pieces on the scrubbed day, scaled from that day's index. */
private fun Segment.piecesOn(day: Int?): Double {
    val live = litterIndex.takeIf { it > 0f } ?: return estimatedPieces.toDouble()
    return estimatedPieces.toDouble() * (indexOn(day) / live)
}

/** The viewer's own position. */
internal fun DeviceFix.describe(): String = "${location.lat}, ${location.lng}"
