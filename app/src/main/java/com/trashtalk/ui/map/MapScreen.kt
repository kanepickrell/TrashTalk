package com.trashtalk.ui.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trashtalk.core.LatLng
import com.trashtalk.core.rememberDeviceLocation
import com.trashtalk.core.formatDistance
import com.trashtalk.data.model.FlagKind
import com.trashtalk.data.model.LitterType
import com.trashtalk.data.model.Segment
import com.trashtalk.domain.LiaEngine
import com.trashtalk.ui.MainViewModel
import com.trashtalk.ui.components.Chip
import com.trashtalk.ui.components.DataRow
import com.trashtalk.ui.components.Eyebrow
import com.trashtalk.ui.components.Hairline
import com.trashtalk.ui.components.MicroSpark
import com.trashtalk.ui.components.Panel
import com.trashtalk.ui.components.RampLegend
import com.trashtalk.ui.components.SegmentedPicker
import com.trashtalk.ui.components.StatusChip
import com.trashtalk.ui.components.TrashTalkButton
import com.trashtalk.ui.components.TrashTalkOutlineButton
import com.trashtalk.ui.theme.HazardBand
import com.trashtalk.ui.theme.LocalReduceMotion
import com.trashtalk.ui.theme.rememberMotion
import com.trashtalk.ui.theme.TT
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * The map screen: the product's centre of gravity.
 *
 * Chrome is deliberately sparse and floats over the data rather than framing it,
 * so the neighbourhood itself is the interface. Three controls earn their space:
 * the layer picker (what is being encoded), the time scrubber (how it got this
 * way), and a single primary action (log what you just picked up).
 */
@Composable
fun MapScreen(
    vm: MainViewModel,
    onLogPickup: (Segment?) -> Unit,
    onOpenReport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val segments by vm.segments.collectAsStateWithLifecycle()
    val collections = vm.collections.collectAsStateWithLifecycle().value.orEmpty()
    val flags by vm.flags.collectAsStateWithLifecycle()
    val layer by vm.mapLayer.collectAsStateWithLifecycle()
    val scrubDay by vm.scrubDay.collectAsStateWithLifecycle()
    val selectedId by vm.selectedSegmentId.collectAsStateWithLifecycle()
    val showGenerators by vm.showGenerators.collectAsStateWithLifecycle()
    val showFlags by vm.showFlags.collectAsStateWithLifecycle()
    val showCollections by vm.showCollections.collectAsStateWithLifecycle()
    val teams = vm.teams.collectAsStateWithLifecycle().value.orEmpty()
    val profile by vm.profile.collectAsStateWithLifecycle()

    // A crew keeps the same hue everywhere it appears. Teams are identities, so
    // the colours come from the categorical palette in fixed slot order.
    val teamColors = remember(teams) {
        teams.sortedBy { it.id }
            .mapIndexed { i, team -> team.id to TT.categorical(i) }
            .toMap()
    }

    val network = vm.network
    val camera = remember {
        MapCameraState(
            initialCenter = network.bounds.center,
            initialZoom = 15.2f,
            limit = network.bounds.pad(0.35),
        )
    }

    var flagTarget by remember { mutableStateOf<Pair<LatLng, Segment?>?>(null) }
    var showLegend by remember { mutableStateOf(false) }
    var surface by remember { mutableStateOf<HexBins.Surface?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    // "Following" means the camera tracks the fix until the user pans away,
    // which is the behaviour every maps app has trained people to expect.
    var followMe by remember { mutableStateOf(false) }
    val locationState = rememberDeviceLocation()
    val motion = rememberMotion()
    val scope = rememberCoroutineScope()
    var selectedBin by remember { mutableStateOf<HexBins.Bin?>(null) }
    var showBasemap by remember { mutableStateOf(true) }

    // The map is full-bleed with chrome floating over it, so the camera is framed
    // against the band between the two overlays rather than the raw viewport.
    // Both are measured rather than estimated, since the legend can be collapsed.
    var viewport by remember { mutableStateOf(Size.Zero) }
    var topChromePx by remember { mutableFloatStateOf(0f) }
    var bottomChromePx by remember { mutableFloatStateOf(0f) }
    var framed by remember { mutableStateOf(false) }

    LaunchedEffect(viewport, topChromePx, bottomChromePx) {
        if (!framed && viewport.width > 0f && bottomChromePx > 0f) {
            camera.fitBounds(
                bounds = network.bounds,
                size = viewport,
                insetTopPx = topChromePx,
                insetBottomPx = bottomChromePx,
            )
            framed = true
        }
    }

    // Track the fix while following. Any manual pan or zoom drops follow mode,
    // so the camera never fights the hand that is moving it.
    LaunchedEffect(followMe, locationState.fix) {
        val fix = locationState.fix
        if (followMe && fix != null) {
            camera.flyTo(fix.location, targetZoom = camera.zoom.coerceAtLeast(16.5f))
        }
    }

    val selected = vm.segment(selectedId)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    Box(
        modifier
            .fillMaxSize()
            .background(TT.Void)
            .onSizeChanged { viewport = Size(it.width.toFloat(), it.height.toFloat()) },
    ) {

        SegmentMap(
            camera = camera,
            data = MapData(
                segments = segments,
                collections = if (showCollections) collections else emptyList(),
                flags = flags,
                pois = network.pois,
                teamColors = teamColors,
                currentUserId = profile.id,
            ),
            layer = layer,
            scrubDay = scrubDay,
            selectedSegmentId = selectedId,
            selectedBin = selectedBin,
            userFix = locationState.fix,
            showGenerators = showGenerators,
            showFlags = showFlags,
            showCollections = showCollections,
            showBasemap = showBasemap,
            reduceMotion = LocalReduceMotion.current,
            onSurfaceBuilt = { surface = it },
            onBinTap = { selectedBin = it },
            onCameraMoved = { followMe = false },
            onSegmentTap = {
                motion.mediumTick()
                vm.selectSegment(it.id)
            },
            onLongPress = { point, segment -> flagTarget = point to segment },
            modifier = Modifier.fillMaxSize(),
        )

        // ------------------------------------------------------------ top chrome
        Column(
            Modifier
                .align(Alignment.TopStart)
                .onSizeChanged { topChromePx = it.height.toFloat() }
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 14.dp)
                .padding(top = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The basemap is pale, so the title needs its own dark plate
                // rather than relying on the map staying light behind it.
                Surface(
                    color = TT.Surface1.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(
                            text = network.neighborhood,
                            style = MaterialTheme.typography.titleMedium,
                            color = TT.Ink,
                        )
                        Text(
                            text = "${network.city}, ${network.state} - ${segments.size} segments",
                            style = MaterialTheme.typography.labelSmall,
                            color = TT.InkMuted,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                MapIconButton(label = "LIA", onClick = onOpenReport)
                Spacer(Modifier.width(8.dp))
                MapIconButton(
                    label = if (showLegend) "Hide key" else "Key",
                    onClick = { showLegend = !showLegend },
                )
            }

            Spacer(Modifier.height(10.dp))

            SegmentedPicker(
                options = MapLayer.entries,
                selected = layer,
                label = { it.label },
                onSelect = { vm.setLayer(it) },
            )

            Spacer(Modifier.height(10.dp))

            NearbyBar(
                locationState = locationState,
                segments = segments,
                onSelectSegment = { vm.selectSegment(it.id) },
                onLocate = { followMe = true },
            )
        }

        // Control stack, vertically centred on the right edge so it sits under
        // the thumb rather than in the corner.
        MapControlStack(
            locationState = locationState,
            followingMe = followMe,
            onAction = { action ->
                when (action) {
                    MapAction.LOCATE -> {
                        followMe = true
                        locationState.fix?.let { fix ->
                            scope.launch {
                                camera.flyTo(
                                    fix.location,
                                    targetZoom = camera.zoom.coerceAtLeast(16.5f),
                                )
                            }
                        }
                    }

                    MapAction.ZOOM_IN -> scope.launch {
                        camera.flyTo(camera.center, camera.zoom + 1f, durationMs = 260)
                    }

                    MapAction.ZOOM_OUT -> scope.launch {
                        camera.flyTo(camera.center, camera.zoom - 1f, durationMs = 260)
                    }

                    MapAction.FIT -> {
                        followMe = false
                        if (viewport.width > 0f) {
                            camera.fitBounds(
                                bounds = network.bounds,
                                size = viewport,
                                insetTopPx = topChromePx,
                                insetBottomPx = bottomChromePx,
                            )
                        }
                    }

                    MapAction.SEARCH -> showSearch = true
                }
            },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 14.dp),
        )

        // --------------------------------------------------------- bottom chrome
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .onSizeChanged { bottomChromePx = it.height.toFloat() }
                .padding(horizontal = 14.dp)
                .padding(bottom = 14.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            AnimatedVisibility(visible = showLegend) {
                Column {
                    LegendPanel(
                        layer = layer,
                        segments = segments,
                        surface = surface,
                        teams = teams,
                        teamColors = teamColors,
                        showGenerators = showGenerators,
                        showFlags = showFlags,
                        showCollections = showCollections,
                        onToggleGenerators = vm::toggleGenerators,
                        onToggleFlags = vm::toggleFlags,
                        onToggleCollections = vm::toggleCollections,
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }

            TimeScrubber(
                days = network.historyDays,
                day = scrubDay,
                onDayChange = { vm.setScrubDay(it) },
            )

            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                TrashTalkButton(
                    text = "Log a pickup",
                    onClick = { onLogPickup(selected) },
                )
                Spacer(Modifier.width(10.dp))
                ScaleBar(metersPerPixel = camera.metersPerPixel())
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = "Long-press the map to plant a flag",
                style = MaterialTheme.typography.labelSmall,
                color = TT.InkMuted,
            )
        }

        // Selected-cell readout. Anchored top-centre so it never collides with
        // the legend or the primary action.
        selectedBin?.let { bin ->
            Surface(
                color = TT.Surface1,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 148.dp),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Eyebrow("Litter density")
                        Spacer(Modifier.width(14.dp))
                        Text(
                            text = "Close",
                            style = MaterialTheme.typography.labelMedium,
                            color = TT.Teal,
                            modifier = Modifier.clickable { selectedBin = null },
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "${bin.pieces.roundToInt()}",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TT.Ink,
                    )
                    Text(
                        text = "pieces across ${bin.segmentCount} segments",
                        style = MaterialTheme.typography.labelSmall,
                        color = TT.InkMuted,
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------ segment sheet
    if (selected != null) {
        ModalBottomSheet(
            onDismissRequest = { vm.selectSegment(null) },
            sheetState = sheetState,
            containerColor = TT.Surface1,
            scrimColor = TT.Void.copy(alpha = 0.6f),
        ) {
            SegmentSheet(
                segment = selected,
                pois = network.pois,
                historyDays = network.historyDays,
                isMine = selected.guardianUserId != null &&
                    selected.guardianUserId == vm.profile.value.id,
                onLogPickup = {
                    vm.selectSegment(null)
                    onLogPickup(selected)
                },
                onAdopt = { adopt -> vm.setGuardian(selected.id, adopt) },
                onReportHazard = {
                    vm.plantFlag(
                        kind = FlagKind.HAZARD,
                        lat = selected.midpoint.lat,
                        lng = selected.midpoint.lng,
                        segmentId = selected.id,
                        note = "Reported from the segment sheet",
                    )
                },
            )
        }
    }

    if (showSearch) {
        SegmentSearchSheet(
            segments = segments,
            fix = locationState.fix,
            onDismiss = { showSearch = false },
            onPick = { seg ->
                showSearch = false
                followMe = false
                vm.selectSegment(seg.id)
                scope.launch { camera.flyTo(seg.midpoint, targetZoom = 17f) }
            },
        )
    }

    // -------------------------------------------------------------- flag dialog
    flagTarget?.let { (point, segment) ->
        PlantFlagSheet(
            point = point,
            segment = segment,
            onDismiss = { flagTarget = null },
            onPlant = { kind, note, anonymous ->
                vm.plantFlag(kind, point.lat, point.lng, segment?.id, note, anonymous)
                flagTarget = null
            },
        )
    }
}

@Composable
private fun MapIconButton(label: String, onClick: () -> Unit) {
    Surface(
        color = TT.Surface1.copy(alpha = 0.88f),
        shape = RoundedCornerShape(11.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = TT.InkSecondary,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
        )
    }
}

/**
 * The map key. Which legend appears depends on the active encoding, because a
 * sequential ramp and a diverging scale are not interchangeable and labelling one
 * as the other would be worse than showing nothing.
 */
@Composable
private fun LegendPanel(
    layer: MapLayer,
    segments: List<Segment>,
    surface: HexBins.Surface?,
    teams: List<com.trashtalk.data.model.Team>,
    teamColors: Map<String, Color>,
    showGenerators: Boolean,
    showFlags: Boolean,
    showCollections: Boolean,
    onToggleGenerators: () -> Unit,
    onToggleFlags: () -> Unit,
    onToggleCollections: () -> Unit,
) {
    Panel(tone = TT.Surface1.copy(alpha = 0.93f)) {
        Column {
            Eyebrow(layer.label)
            Spacer(Modifier.height(3.dp))
            Text(
                text = layer.caption,
                style = MaterialTheme.typography.bodySmall,
                color = TT.InkSecondary,
            )
            Spacer(Modifier.height(10.dp))

            when (layer) {
                // A classed choropleth needs its actual ranges, not a smooth
                // gradient: the reader has to be able to decode a cell's colour
                // back into a number.
                MapLayer.DENSITY -> {
                    val s = surface
                    if (s == null || s.isEmpty) {
                        Text(
                            text = "Binning the network...",
                            style = MaterialTheme.typography.bodySmall,
                            color = TT.InkMuted,
                        )
                    } else {
                        Column {
                            TT.HexRamp.indices.forEach { i ->
                                Row(
                                    modifier = Modifier.padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        Modifier
                                            .size(width = 22.dp, height = 12.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(TT.HexRamp[i]),
                                    )
                                    Spacer(Modifier.width(9.dp))
                                    Text(
                                        text = s.rangeLabel(i),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TT.InkSecondary,
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "Total pieces",
                                style = MaterialTheme.typography.labelSmall,
                                color = TT.InkMuted,
                            )
                            Text(
                                text = "%,d".format(s.totalPieces.toInt()),
                                style = MaterialTheme.typography.headlineSmall,
                                color = TT.Ink,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "${s.bins.size} cells - 110 m each",
                                style = MaterialTheme.typography.labelSmall,
                                color = TT.InkMuted,
                            )
                        }
                    }
                }

                MapLayer.CHANGE -> {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        (TT.ImprovingArm.reversed() + TT.DivergingMid + TT.WorseningArm)
                            .forEach { c ->
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(c),
                                )
                            }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            "Improving",
                            style = MaterialTheme.typography.labelSmall,
                            color = TT.InkMuted,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "No change",
                            style = MaterialTheme.typography.labelSmall,
                            color = TT.InkMuted,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "Worsening",
                            style = MaterialTheme.typography.labelSmall,
                            color = TT.InkMuted,
                        )
                    }
                }

                MapLayer.ACTIVITY -> {
                    RampLegend(
                        lowLabel = "Never worked",
                        highLabel = "Worked often",
                        ramp = TT.ActivityRamp.drop(2),
                    )
                }

                MapLayer.TERRITORY -> {
                    val held = segments.count { it.isClaimed }
                    val wild = segments.count { !it.hasBeenSurveyed }
                    Column {
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Chip(text = "Yours", tint = TT.Teal, filled = true)
                            teamColors.entries.forEach { (id, color) ->
                                val name = teams.firstOrNull { it.id == id }?.name ?: id
                                Chip(
                                    text = name.split(' ').first(),
                                    tint = color,
                                    filled = true,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "$held of ${segments.size} blocks held - $wild still unwalked",
                            style = MaterialTheme.typography.labelSmall,
                            color = TT.InkMuted,
                        )
                    }
                }
            }

            Spacer(Modifier.height(11.dp))
            Hairline()
            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Chip(
                    text = "Generators",
                    tint = TT.Warning,
                    filled = showGenerators,
                    modifier = Modifier.clickable { onToggleGenerators() },
                )
                Chip(
                    text = "Flags",
                    tint = TT.Magenta,
                    filled = showFlags,
                    modifier = Modifier.clickable { onToggleFlags() },
                )
                Chip(
                    text = "Pickups",
                    tint = TT.ActivityRamp.last(),
                    filled = showCollections,
                    modifier = Modifier.clickable { onToggleCollections() },
                )
            }

            Spacer(Modifier.height(10.dp))
            // Tile providers require attribution, and it belongs on the map
            // rather than buried in an about screen.
            Text(
                text = TileSource.ATTRIBUTION,
                style = MaterialTheme.typography.labelSmall,
                color = TT.InkMuted,
            )
        }
    }
}

/**
 * The time scrubber: a timeline, not a progress bar.
 *
 * Material's slider reads as "how full is this", which is exactly the wrong
 * metaphor for replaying a fortnight -- so this is drawn directly: one tick per
 * day, a dot on the selected day, and a dim trail behind it. Dragging it replays
 * the trailing two weeks of observations, which is the clearest way to show that
 * litter is a flow and not a fixed state: the same block fills back in within
 * days of being cleared.
 */
@Composable
private fun TimeScrubber(
    days: Int,
    day: Int?,
    onDayChange: (Int?) -> Unit,
) {
    if (days <= 1) return
    val selected = day ?: (days - 1)

    Surface(
        color = TT.Surface1.copy(alpha = 0.93f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.width(62.dp)) {
                Text(
                    text = if (day == null || day == days - 1) "Today" else "-${days - 1 - day}d",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (day == null) TT.Ink else TT.Teal,
                )
                Text(
                    text = if (day == null) "live" else "replay",
                    style = MaterialTheme.typography.labelSmall,
                    color = TT.InkMuted,
                )
            }
            Spacer(Modifier.width(10.dp))

            Canvas(
                Modifier
                    .weight(1f)
                    .height(30.dp)
                    .pointerInput(days) {
                        fun pick(x: Float) {
                            val step = size.width / (days - 1).coerceAtLeast(1)
                            val d = (x / step).roundToInt().coerceIn(0, days - 1)
                            onDayChange(if (d == days - 1) null else d)
                        }
                        detectTapGestures { pick(it.x) }
                    }
                    .pointerInput(days) {
                        detectDragGestures { change, _ ->
                            val step = size.width / (days - 1).coerceAtLeast(1)
                            val d = (change.position.x / step).roundToInt().coerceIn(0, days - 1)
                            onDayChange(if (d == days - 1) null else d)
                        }
                    },
            ) {
                val midY = size.height / 2f
                val step = size.width / (days - 1).coerceAtLeast(1)

                drawLine(
                    color = TT.Surface3,
                    start = Offset(0f, midY),
                    end = Offset(size.width, midY),
                    strokeWidth = 2f,
                )
                drawLine(
                    color = TT.TealDim,
                    start = Offset(0f, midY),
                    end = Offset(selected * step, midY),
                    strokeWidth = 2f,
                )
                for (i in 0 until days) {
                    val x = i * step
                    drawCircle(
                        color = if (i <= selected) TT.TealDim else TT.Surface3,
                        radius = 2f,
                        center = Offset(x, midY),
                    )
                }
                // The handle carries a surface ring so it stays legible wherever
                // it lands on the track.
                drawCircle(TT.Surface1, radius = 8f, center = Offset(selected * step, midY))
                drawCircle(TT.Teal, radius = 5.5f, center = Offset(selected * step, midY))
            }

            if (day != null) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Live",
                    style = MaterialTheme.typography.labelMedium,
                    color = TT.Teal,
                    modifier = Modifier.clickable { onDayChange(null) },
                )
            }
        }
    }
}

/** A real scale bar, because a map without one invites misreading of distance. */
@Composable
private fun ScaleBar(metersPerPixel: Double) {
    // Snap to a round number of metres that fits in roughly 70-110 px.
    val candidates = listOf(25, 50, 100, 200, 400, 800, 1600)
    val meters = candidates.firstOrNull { it / metersPerPixel in 60.0..130.0 }
        ?: candidates.minByOrNull { kotlin.math.abs(it / metersPerPixel - 90.0) }!!
    val widthPx = (meters / metersPerPixel).toFloat()

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(
            Modifier
                .width(with(androidx.compose.ui.platform.LocalDensity.current) { widthPx.toDp() })
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(TT.InkSecondary),
        )
        Text(
            text = if (meters >= 1000) "${meters / 1000} km" else "$meters m",
            style = MaterialTheme.typography.labelSmall,
            color = TT.InkMuted,
        )
    }
}

/**
 * Segment detail. Leads with what a volunteer needs in the field -- how bad it is,
 * how long it is, when it was last walked -- then the evidence behind the number,
 * then the actions.
 */
@Composable
private fun SegmentSheet(
    segment: Segment,
    pois: List<com.trashtalk.data.model.Poi>,
    historyDays: Int,
    isMine: Boolean,
    onLogPickup: () -> Unit,
    onAdopt: (Boolean) -> Unit,
    onReportHazard: () -> Unit,
) {
    val band = HazardBand.of(segment.litterIndex)
    val nearest = LiaEngine.nearestGenerator(segment.midpoint, pois)

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(bottom = 26.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Eyebrow(segment.streetClass.label)
                Text(
                    text = segment.name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = TT.Ink,
                )
            }
            StatusChip(label = "${band.shortLabel} ${band.label}", tint = band.color)
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = band.blurb,
            style = MaterialTheme.typography.bodyMedium,
            color = TT.InkSecondary,
        )

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniStat(
                value = "${segment.estimatedPieces}",
                label = "pieces now",
                modifier = Modifier.weight(1f),
                accent = TT.litterColor(segment.litterIndex),
            )
            MiniStat(
                value = "%.0f".format(segment.piecesPer100m),
                label = "per 100 m",
                modifier = Modifier.weight(1f),
            )
            MiniStat(
                value = formatDistance(segment.lengthMeters),
                label = "length",
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(18.dp))

        if (segment.history.size > 1) {
            Eyebrow("Last $historyDays days")
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MicroSpark(
                    values = segment.history,
                    tint = TT.litterColor(segment.litterIndex),
                    modifier = Modifier.weight(1f).height(34.dp),
                )
                Spacer(Modifier.width(12.dp))
                val change = segment.sevenDayChange
                Text(
                    text = (if (change > 0) "+" else "") + "%.0f%%".format(change * 100),
                    style = MaterialTheme.typography.titleSmall,
                    color = TT.changeColor(change),
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        Hairline()
        Spacer(Modifier.height(8.dp))

        DataRow(
            label = "Surveys logged",
            value = if (segment.surveyCount == 0) "Never surveyed" else "${segment.surveyCount}",
            valueColor = if (segment.surveyCount == 0) TT.Warning else TT.Ink,
            caption = if (segment.surveyCount == 0) {
                "First survey pays a coverage bonus"
            } else null,
        )
        DataRow(label = "Cleanups", value = "${segment.cleanupCount}")
        DataRow(label = "Pieces removed to date", value = "${segment.piecesRemoved}")
        DataRow(
            label = "Re-accumulation rate",
            value = "${(segment.propensity * 100).roundToInt()}%",
            caption = "How fast it fills back in once cleared",
        )
        if (segment.lastSurveyedAt > 0) {
            val hours = (System.currentTimeMillis() - segment.lastSurveyedAt) /
                TimeUnit.HOURS.toMillis(1)
            DataRow(
                label = "Last surveyed",
                value = when {
                    hours < 1 -> "Just now"
                    hours < 24 -> "${hours}h ago"
                    else -> "${hours / 24}d ago"
                },
            )
        }
        if (nearest != null && nearest.second < 400) {
            DataRow(
                label = "Nearest generator",
                value = formatDistance(nearest.second),
                caption = nearest.first.name,
            )
        }
        if (segment.guardianName != null) {
            DataRow(
                label = "Adopted by",
                value = segment.guardianName,
                valueColor = TT.Teal,
            )
        }

        if (segment.composition.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Eyebrow("Typical composition here")
            Spacer(Modifier.height(7.dp))
            val top = segment.composition.entries
                .mapNotNull { (k, v) ->
                    runCatching { LitterType.valueOf(k) }.getOrNull()?.let { it to v }
                }
                .sortedByDescending { it.second }
                .take(3)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                top.forEachIndexed { i, (type, share) ->
                    Chip(
                        text = "${type.label} ${(share * 100).roundToInt()}%",
                        tint = TT.categorical(i),
                        filled = true,
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        TrashTalkButton(
            text = "Log a pickup here",
            onClick = onLogPickup,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            TrashTalkOutlineButton(
                text = if (isMine) "Release" else "Adopt",
                onClick = { onAdopt(!isMine) },
                tint = TT.Teal,
                modifier = Modifier.weight(1f),
            )
            TrashTalkOutlineButton(
                text = "Report hazard",
                onClick = onReportHazard,
                tint = TT.Critical,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MiniStat(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    accent: Color = TT.Ink,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(TT.Surface2)
            .padding(horizontal = 11.dp, vertical = 10.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = accent,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TT.InkMuted,
        )
    }
}

/** Flag planting, offered where the user long-pressed. */
@Composable
private fun PlantFlagSheet(
    point: LatLng,
    segment: Segment?,
    onDismiss: () -> Unit,
    onPlant: (FlagKind, String, Boolean) -> Unit,
) {
    var kind by remember { mutableStateOf(FlagKind.HOTSPOT_GUESS) }
    var anonymous by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = TT.Surface1,
        scrimColor = TT.Void.copy(alpha = 0.6f),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 26.dp),
        ) {
            Eyebrow("Plant a flag")
            Text(
                text = segment?.name ?: "Off the surveyed network",
                style = MaterialTheme.typography.headlineSmall,
                color = TT.Ink,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "%.5f, %.5f".format(point.lat, point.lng),
                style = MaterialTheme.typography.labelSmall,
                color = TT.InkMuted,
            )

            Spacer(Modifier.height(16.dp))
            SegmentedPicker(
                options = FlagKind.entries,
                selected = kind,
                label = { it.label },
                onSelect = { kind = it },
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = kind.blurb,
                style = MaterialTheme.typography.bodyMedium,
                color = TT.InkSecondary,
            )

            if (kind == FlagKind.HOTSPOT_GUESS) {
                Spacer(Modifier.height(10.dp))
                Panel(tone = TT.Surface2) {
                    Text(
                        text = "A guess only counts as a confirmed hotspot once a " +
                            "survey here comes back heavy. Being wrong is still useful " +
                            "data -- it tells us where litter is not.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TT.InkSecondary,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(if (anonymous) TT.Teal else TT.Surface3)
                        .clickable { anonymous = !anonymous },
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Plant anonymously",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TT.InkSecondary,
                    modifier = Modifier.clickable { anonymous = !anonymous },
                )
            }

            Spacer(Modifier.height(18.dp))
            TrashTalkButton(
                text = "Plant ${kind.label.lowercase()}",
                onClick = { onPlant(kind, kind.blurb, anonymous) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
