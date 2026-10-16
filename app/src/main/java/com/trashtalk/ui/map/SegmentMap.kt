package com.trashtalk.ui.map

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.trashtalk.core.LatLng
import com.trashtalk.core.Mercator
import com.trashtalk.core.distanceToPolylineMeters
import com.trashtalk.data.model.CollectionEvent
import com.trashtalk.data.model.FlagKind
import com.trashtalk.data.model.MapFlag
import com.trashtalk.data.model.Poi
import com.trashtalk.data.model.Segment
import com.trashtalk.ui.theme.TT
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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
 * The litter map.
 *
 * Three layers, bottom to top: a pale raster basemap for real street context, a
 * data layer, and markers. The data layer changes shape with the question being
 * asked -- density is binned into hexagons, because a surface answers "where is
 * the problem" far faster than a few hundred thin strokes; territory, change and
 * activity stay on the segments, because those are properties *of a block* and
 * binning them would blur the thing being asked about.
 *
 * The basemap is a progressive enhancement. Tiles need a network, and volunteers
 * survey exactly the places with the worst coverage, so the bundled street
 * geometry is always drawn underneath and the map remains usable with no
 * connection at all.
 *
 * **Performance.** Projection and path construction are cached in [MapFrame] and
 * rebuilt only on a camera or data change -- never per frame. A continuous pan
 * reuses one projection and slides it with a canvas translate. The ambient pulse
 * is confined to an overlay canvas holding a few dozen small marks, so animating
 * it does not repaint the hex surface underneath.
 */
@Composable
fun SegmentMap(
    camera: MapCameraState,
    data: MapData,
    layer: MapLayer,
    modifier: Modifier = Modifier,
    /** Day in the history window to render, or null for live values. */
    scrubDay: Int? = null,
    selectedSegmentId: String? = null,
    selectedBin: HexBins.Bin? = null,
    showGenerators: Boolean = true,
    showFlags: Boolean = true,
    showCollections: Boolean = true,
    showBasemap: Boolean = true,
    reduceMotion: Boolean = false,
    onSurfaceBuilt: (HexBins.Surface) -> Unit = {},
    onSegmentTap: (Segment) -> Unit = {},
    onBinTap: (HexBins.Bin?) -> Unit = {},
    onLongPress: (LatLng, Segment?) -> Unit = { _, _ -> },
) {
    val measurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    val tiles = rememberTileCache(scope)
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    val transition = rememberInfiniteTransition(label = "map-pulse")
    // Held as State and read *inside* the overlay draw lambda, never in the
    // composable body: reading it here would recompose the whole map every frame
    // and drag the static base canvas into the repaint with it.
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulse-phase",
    )

    val labelStyle = remember {
        TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Medium, color = TT.MapInk)
    }
    val poiStyle = remember {
        TextStyle(fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = TT.MapInk)
    }

    // Binning is independent of the camera: the cells cover fixed ground, so
    // panning and zooming never re-bin underneath the reader.
    val surface = remember(data.segments, layer, scrubDay) {
        if (layer != MapLayer.DENSITY) {
            HexBins.Surface(emptyList(), listOf(1.0), 0.0, 0.0)
        } else {
            HexBins.build(
                segments = data.segments,
                referenceLat = data.segments.firstOrNull()?.midpoint?.lat ?: 29.5,
                valueOf = { seg ->
                    if (scrubDay != null) seg.indexAt(scrubDay) else seg.litterIndex
                },
                weightOf = { seg ->
                    val idx = if (scrubDay != null) seg.indexAt(scrubDay) else seg.litterIndex
                    // Estimated pieces scale with density and street length.
                    (idx * seg.lengthMeters * 0.11).coerceAtLeast(0.0)
                },
            )
        }
    }

    remember(surface) {
        onSurfaceBuilt(surface)
        surface
    }

    val frame = remember(
        data, layer, scrubDay, canvasSize, camera.zoom, panBucket(camera, canvasSize),
        showGenerators, showFlags, showCollections, surface,
    ) {
        if (canvasSize.width <= 0f) {
            MapFrame.EMPTY
        } else {
            buildFrame(
                camera = camera,
                data = data,
                layer = layer,
                scrubDay = scrubDay,
                size = canvasSize,
                surface = surface,
                showGenerators = showGenerators,
                showFlags = showFlags,
                showCollections = showCollections,
            )
        }
    }

    Box(modifier = modifier) {

        // Two canvases, deliberately. Anything that animates invalidates its whole
        // canvas every frame, so the heavy geometry lives in a base layer that
        // repaints only when the camera or the data moves, and the ambient pulse
        // is confined to an overlay holding a few dozen small marks.
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            if (zoom != 1f) camera.zoomBy(zoom, centroid, size.toSize())
                            if (pan != Offset.Zero) {
                                camera.panBy(pan)
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(data.segments, layer, surface) {
                    detectTapGestures(
                        onTap = { offset ->
                            val s = this.size.toSize()
                            if (layer == MapLayer.DENSITY) {
                                onBinTap(hitTestBin(camera, surface, offset, s))
                            } else {
                                hitTest(camera, data.segments, offset, s)?.let(onSegmentTap)
                            }
                        },
                        onLongPress = { offset ->
                            val s = this.size.toSize()
                            onLongPress(
                                camera.unproject(offset, s),
                                hitTest(camera, data.segments, offset, s),
                            )
                        },
                        onDoubleTap = { offset -> camera.zoomBy(2f, offset, this.size.toSize()) },
                    )
                },
        ) {
            drawRect(TT.MapGround)

            // 1. Basemap tiles. Reading tiles.version here is what re-triggers the
            //    draw as each tile finishes loading.
            if (showBasemap) {
                @Suppress("UNUSED_EXPRESSION") tiles.version
                for (tile in layoutTiles(camera, size, tiles, TileStyle.LIGHT_BASE)) {
                    drawTile(tile)
                }
            }

            val slide = frame.slideFor(camera, size)

            translate(left = slide.x, top = slide.y) {

                // 2. The bundled street network. Always drawn: it is the
                //    fallback when tiles have not loaded, and it carries the
                //    survey state that no basemap knows about.
                val streetAlpha = if (showBasemap) 0.30f else 1f
                for (r in frame.segments) {
                    drawPath(
                        path = r.path,
                        color = TT.MapInk.copy(alpha = 0.22f * streetAlpha),
                        style = Stroke(
                            width = (r.segment.streetClass.strokeWeight * 1.4f)
                                .coerceAtLeast(1f),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                }

                // 3. The density surface.
                if (layer == MapLayer.DENSITY) {
                    for (cell in frame.cells) {
                        drawPath(
                            path = cell.path,
                            color = TT.HexRamp[cell.classIndex].copy(alpha = 0.78f),
                        )
                        // Every cell carries an outline, so presence and extent
                        // never depend on how light the fill is.
                        drawPath(
                            path = cell.path,
                            color = TT.MapInk.copy(alpha = 0.26f),
                            style = Stroke(width = 1f),
                        )
                    }
                    frame.cells.firstOrNull { it.bin === selectedBin }?.let { cell ->
                        drawPath(
                            path = cell.path,
                            color = TT.MapInk,
                            style = Stroke(width = 3f, join = StrokeJoin.Round),
                        )
                    }
                }

                // 4. Unwalked ground, dashed: the gap in the dataset is visible on
                //    the map instead of hidden in a statistic.
                if (layer != MapLayer.DENSITY) {
                    val dash = PathEffect.dashPathEffect(floatArrayOf(5f, 7f), 0f)
                    for (r in frame.segments) {
                        if (r.segment.hasBeenSurveyed) continue
                        drawPath(
                            path = r.path,
                            color = TT.MapInk.copy(alpha = 0.40f),
                            style = Stroke(width = 1.4f, cap = StrokeCap.Round, pathEffect = dash),
                        )
                    }
                }

                // 5. Segment-level encodings. A white casing under each coloured
                //    stroke is the standard cartographic trick for keeping a line
                //    legible over a busy basemap.
                if (layer != MapLayer.DENSITY) {
                    for (r in frame.segments) {
                        if (r.intensity <= 0.02f) continue
                        drawPath(
                            path = r.path,
                            color = Color.White.copy(alpha = 0.85f),
                            style = Stroke(
                                width = r.coreWidth + 3.4f,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            ),
                        )
                        drawPath(
                            path = r.path,
                            color = r.color.copy(alpha = (0.45f + 0.55f * r.intensity)),
                            style = Stroke(
                                width = r.coreWidth,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            ),
                        )
                    }
                }

                // 6. Litter generators: what explains the hotspots.
                for ((poi, at) in frame.pois) {
                    drawGenerator(at, poi, frame.detail, measurer, poiStyle)
                }
            }

            // 7. Place and street labels last, so names stay readable through
            //    the density surface instead of being buried by it.
            if (showBasemap) {
                for (tile in layoutTiles(camera, size, tiles, TileStyle.LIGHT_LABELS)) {
                    drawTile(tile)
                }
            }
        }

        // The animated overlay: a few dozen marks, repainted every frame.
        Canvas(Modifier.fillMaxSize()) {
            val pulse = if (reduceMotion) 1f else 1f + 0.10f * sin(phase.value)
            translate(
                left = frame.slideFor(camera, size).x,
                top = frame.slideFor(camera, size).y,
            ) {
                for (c in frame.collections) {
                    val r = (1.7f + c.freshness * 2.2f) * (if (reduceMotion) 1f else pulse)
                    drawCircle(Color.White.copy(alpha = 0.9f), radius = r + 1.4f, center = c.at)
                    drawCircle(
                        color = TT.MapInk.copy(alpha = 0.55f + 0.35f * c.freshness),
                        radius = r,
                        center = c.at,
                    )
                }

                for ((flag, at) in frame.flags) {
                    drawFlag(at, flag, pulse, reduceMotion)
                }

                val selected = frame.segments.firstOrNull { it.segment.id == selectedSegmentId }
                if (selected != null) {
                    drawPath(
                        path = selected.path,
                        color = TT.MapInk,
                        style = Stroke(
                            width = selected.coreWidth + 4f,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                    selected.points.firstOrNull()?.let { drawNode(it) }
                    selected.points.lastOrNull()?.let { drawNode(it) }
                }
            }
        }
    }
}

// --------------------------------------------------------------------- frame

private class RenderSegment(
    val segment: Segment,
    val points: List<Offset>,
    val path: Path,
    val color: Color,
    val intensity: Float,
    val coreWidth: Float,
)

private class RenderCell(val bin: HexBins.Bin, val path: Path, val classIndex: Int)

private class RenderCollection(val at: Offset, val freshness: Float)

private class MapFrame(
    val segments: List<RenderSegment>,
    val cells: List<RenderCell>,
    val pois: List<Pair<Poi, Offset>>,
    val flags: List<Pair<MapFlag, Offset>>,
    val collections: List<RenderCollection>,
    val viewport: Rect,
    val detail: Float,
    val builtAt: LatLng,
) {
    /** How far the camera has panned since this frame was projected. */
    fun slideFor(camera: MapCameraState, size: Size): Offset =
        if (segments.isEmpty() && cells.isEmpty()) {
            Offset.Zero
        } else {
            camera.project(builtAt, size) - Offset(size.width / 2f, size.height / 2f)
        }

    companion object {
        val EMPTY = MapFrame(
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            Rect.Zero, 0f, LatLng(0.0, 0.0),
        )
    }
}

private fun buildFrame(
    camera: MapCameraState,
    data: MapData,
    layer: MapLayer,
    scrubDay: Int?,
    size: Size,
    surface: HexBins.Surface,
    showGenerators: Boolean,
    showFlags: Boolean,
    showCollections: Boolean,
): MapFrame {
    val viewport = Rect(-GUTTER_PX, -GUTTER_PX, size.width + GUTTER_PX, size.height + GUTTER_PX)
    // Culling runs against an inflated rect: the frame is reused across a pan of
    // up to half a viewport, so anything within that reach must be projected now
    // or it would pop in late.
    val cull = Rect(
        -size.width * 0.75f,
        -size.height * 0.75f,
        size.width * 1.75f,
        size.height * 1.75f,
    )
    val mpp = camera.metersPerPixel()
    val detail = camera.zoom
    val now = System.currentTimeMillis()

    val rendered = ArrayList<RenderSegment>(data.segments.size)
    for (seg in data.segments) {
        if (seg.path.size < 2) continue
        val pts = seg.path.map { camera.project(it, size) }
        if (!pts.touches(cull)) continue
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
        }
        val value = layerValue(seg, layer, scrubDay, data)
        rendered += RenderSegment(
            segment = seg,
            points = pts,
            path = path,
            color = value.color,
            intensity = value.intensity,
            coreWidth = coreWidth(seg, mpp),
        )
    }

    // Hex corners are in world units; project each to screen once per frame.
    val cells = if (layer != MapLayer.DENSITY) emptyList() else {
        val ws = Mercator.worldSizePx(camera.zoom)
        val cx = Mercator.worldX(camera.center.lng) * ws
        val cy = Mercator.worldY(camera.center.lat) * ws
        surface.bins.mapNotNull { bin ->
            val corners = HexBins.corners(bin, surface.hexRadiusWorld).map { (wx, wy) ->
                Offset(
                    (size.width / 2f + (wx * ws - cx)).toFloat(),
                    (size.height / 2f + (wy * ws - cy)).toFloat(),
                )
            }
            if (!corners.touches(cull)) return@mapNotNull null
            val path = Path().apply {
                moveTo(corners[0].x, corners[0].y)
                for (i in 1 until corners.size) lineTo(corners[i].x, corners[i].y)
                close()
            }
            RenderCell(bin, path, bin.classIndex)
        }
    }

    val pois = if (showGenerators && detail >= 14.5f) {
        data.pois.mapNotNull { poi ->
            val at = camera.project(poi.location, size)
            if (cull.contains(at)) poi to at else null
        }
    } else emptyList()

    val flags = if (showFlags) {
        data.flags.mapNotNull { flag ->
            val at = camera.project(flag.location, size)
            if (cull.contains(at)) flag to at else null
        }
    } else emptyList()

    val collections = if (showCollections) {
        data.collections.mapNotNull { c ->
            val at = camera.project(c.location, size)
            if (!cull.contains(at)) return@mapNotNull null
            val ageHours = (now - c.createdAt) / 3_600_000.0
            RenderCollection(at, (1.0 - ageHours / 72.0).coerceIn(0.0, 1.0).toFloat())
        }
    } else emptyList()

    return MapFrame(
        segments = rendered,
        cells = cells,
        pois = pois,
        flags = flags,
        collections = collections,
        viewport = viewport,
        detail = detail,
        builtAt = camera.center,
    )
}

/**
 * Quantises the camera centre to a coarse grid so panning does not invalidate the
 * cached frame on every frame. Panning only slides already-projected geometry;
 * a rebuild is needed solely to re-cull what has come into view.
 */
private fun panBucket(camera: MapCameraState, size: Size): Long {
    if (size.width <= 0f) return 0L
    val step = (size.width / 2f).toDouble().coerceAtLeast(1.0)
    val ws = Mercator.worldSizePx(camera.zoom)
    val x = (Mercator.worldX(camera.center.lng) * ws / step).toLong()
    val y = (Mercator.worldY(camera.center.lat) * ws / step).toLong()
    return x * 73_856_093L xor y
}

private class LayerValue(val color: Color, val intensity: Float)

private fun layerValue(
    segment: Segment,
    layer: MapLayer,
    scrubDay: Int?,
    data: MapData,
): LayerValue = when (layer) {
    // The hexes carry density; the lines underneath stay quiet context.
    MapLayer.DENSITY -> LayerValue(TT.MapInk, 0f)

    MapLayer.CHANGE -> {
        val d = segment.sevenDayChange
        // Intensity is magnitude regardless of sign; the hue carries direction.
        LayerValue(TT.changeColor(d), (abs(d) / 0.22f).coerceIn(0f, 1f))
    }

    MapLayer.ACTIVITY -> {
        val n = (segment.cleanupCount / 6f).coerceIn(0f, 1f)
        LayerValue(TT.sample(TT.ActivityRamp, n), n)
    }

    MapLayer.TERRITORY -> {
        val holder = segment.guardianUserId
        if (holder == null) {
            LayerValue(TT.MapInk, 0f)
        } else {
            val color = if (holder == data.currentUserId) {
                TT.TealDim
            } else {
                data.teamColors[segment.guardianTeamId] ?: TT.MapInk
            }
            LayerValue(color, 1f)
        }
    }
}

private fun coreWidth(segment: Segment, metersPerPixel: Double): Float {
    val meters = 5.5 * segment.streetClass.strokeWeight
    return (meters / metersPerPixel).toFloat().coerceIn(1.6f, 9f)
}

// ---------------------------------------------------------------- draw parts

private fun DrawScope.drawTile(tile: PlacedTile) {
    val dstSize = IntSize(tile.size.toInt() + 1, tile.size.toInt() + 1)
    val dstOffset = IntOffset(tile.topLeft.x.toInt(), tile.topLeft.y.toInt())
    val image = tile.image
    if (image != null) {
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = dstOffset,
            dstSize = dstSize,
            filterQuality = FilterQuality.Low,
        )
        return
    }
    // Stretch a cached parent tile while the real one loads, so a pan shows
    // blurry-but-correct context instead of blank squares.
    val fb = tile.fallback ?: return
    val sub = tile.fallbackSrcSize.toInt().coerceAtLeast(1)
    drawImage(
        image = fb,
        srcOffset = IntOffset(
            tile.fallbackSrcOffset.x.toInt(),
            tile.fallbackSrcOffset.y.toInt(),
        ),
        srcSize = IntSize(sub, sub),
        dstOffset = dstOffset,
        dstSize = dstSize,
        filterQuality = FilterQuality.Low,
    )
}

private fun DrawScope.drawNode(at: Offset) {
    drawCircle(Color.White, radius = 3.6f, center = at)
    drawCircle(TT.MapInk, radius = 3.6f, center = at, style = Stroke(width = 1.6f))
}

private fun DrawScope.drawGenerator(
    at: Offset,
    poi: Poi,
    detail: Float,
    measurer: TextMeasurer,
    style: TextStyle,
) {
    val tint = when (poi.kind) {
        "commercial" -> Color(0xFFB4529F)
        "transit" -> Color(0xFF5A4199)
        "school", "park" -> Color(0xFF127A4F)
        else -> TT.MapInk
    }
    drawCircle(Color.White, radius = 6.4f, center = at)
    drawCircle(tint, radius = 6.4f, center = at, style = Stroke(width = 1.6f))
    drawCircle(tint, radius = 2.2f, center = at)

    if (detail >= 16.2f) {
        val layout = measurer.measure(poi.name, style)
        drawText(layout, topLeft = at + Offset(10f, -layout.size.height / 2f))
    }
}

private fun DrawScope.drawFlag(at: Offset, flag: MapFlag, pulse: Float, reduceMotion: Boolean) {
    val tint = when (flag.kind) {
        FlagKind.HAZARD -> Color(0xFFC62828)
        FlagKind.GUARDIAN -> TT.TealDim
        FlagKind.HOTSPOT_GUESS -> if (flag.confirmed) Color(0xFFB4529F) else TT.MapInk
    }
    val r = 5.4f * (if (reduceMotion) 1f else pulse)

    rotate(degrees = 45f, pivot = at) {
        drawRect(
            color = Color.White,
            topLeft = Offset(at.x - r - 1.4f, at.y - r - 1.4f),
            size = Size((r + 1.4f) * 2, (r + 1.4f) * 2),
        )
        drawRect(
            color = if (flag.confirmed || flag.kind == FlagKind.HAZARD) tint else Color.White,
            topLeft = Offset(at.x - r, at.y - r),
            size = Size(r * 2, r * 2),
        )
        drawRect(
            color = tint,
            topLeft = Offset(at.x - r, at.y - r),
            size = Size(r * 2, r * 2),
            style = Stroke(width = 1.8f),
        )
    }
}

/** The nearest segment to a tap, within a generous finger-sized tolerance. */
private fun hitTest(
    camera: MapCameraState,
    segments: List<Segment>,
    offset: Offset,
    size: Size,
): Segment? {
    if (segments.isEmpty()) return null
    val point = camera.unproject(offset, size)
    val toleranceM = camera.metersPerPixel() * TAP_TOLERANCE_PX
    var best: Segment? = null
    var bestDist = Double.MAX_VALUE
    for (s in segments) {
        if (s.path.size < 2) continue
        val d = distanceToPolylineMeters(point, s.path)
        if (d < bestDist) {
            bestDist = d
            best = s
        }
    }
    return if (bestDist <= toleranceM) best else null
}

/** The hex cell under a tap: nearest centre within one cell radius. */
private fun hitTestBin(
    camera: MapCameraState,
    surface: HexBins.Surface,
    offset: Offset,
    size: Size,
): HexBins.Bin? {
    if (surface.isEmpty) return null
    val point = camera.unproject(offset, size)
    val wx = Mercator.worldX(point.lng)
    val wy = Mercator.worldY(point.lat)
    var best: HexBins.Bin? = null
    var bestD = Double.MAX_VALUE
    for (bin in surface.bins) {
        val dx = bin.worldX - wx
        val dy = bin.worldY - wy
        val d = dx * dx + dy * dy
        if (d < bestD) {
            bestD = d
            best = bin
        }
    }
    val radius = surface.hexRadiusWorld
    return if (bestD <= radius * radius) best else null
}

/** True when any part of the point set's bounding box intersects the viewport. */
private fun List<Offset>.touches(viewport: Rect): Boolean {
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (o in this) {
        minX = min(minX, o.x); maxX = max(maxX, o.x)
        minY = min(minY, o.y); maxY = max(maxY, o.y)
    }
    return maxX >= viewport.left && minX <= viewport.right &&
        maxY >= viewport.top && minY <= viewport.bottom
}

private const val GUTTER_PX = 48f
private const val TAP_TOLERANCE_PX = 26f
