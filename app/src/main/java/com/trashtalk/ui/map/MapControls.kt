package com.trashtalk.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trashtalk.core.DeviceFix
import com.trashtalk.core.LocationAvailability
import com.trashtalk.core.LocationState
import com.trashtalk.core.distanceToPolylineMeters
import com.trashtalk.core.formatDistance
import com.trashtalk.data.model.Segment
import com.trashtalk.ui.components.Chip
import com.trashtalk.ui.components.Eyebrow
import com.trashtalk.ui.theme.HazardBand
import com.trashtalk.ui.theme.TT

/** The actions available on the map's control stack. */
enum class MapAction { LOCATE, ZOOM_IN, ZOOM_OUT, FIT, SEARCH, PLAN }

/**
 * The control stack: locate, zoom, fit, search.
 *
 * Pinch and double-tap already work, but they are poor affordances on a map you
 * are using one-handed while holding a bag — and nothing on screen previously
 * told a newcomer the map could be moved at all. Explicit buttons are the
 * difference between a picture and an instrument.
 */
@Composable
fun MapControlStack(
    locationState: LocationState,
    followingMe: Boolean,
    onAction: (MapAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.End,
    ) {
        ControlButton(
            onClick = { onAction(MapAction.PLAN) },
            contentDescription = "Plan a walk",
        ) { tint -> PlanGlyph(tint) }

        ControlButton(
            onClick = { onAction(MapAction.SEARCH) },
            contentDescription = "Find a street",
        ) { tint -> SearchGlyph(tint) }

        ControlButton(
            onClick = { onAction(MapAction.LOCATE) },
            contentDescription = "Centre on my location",
            tint = when {
                followingMe -> TT.Teal
                locationState.availability != LocationAvailability.GRANTED -> TT.InkMuted
                else -> TT.InkSecondary
            },
        ) { tint -> LocateGlyph(tint, active = followingMe) }

        // Zoom pair, joined into one pill so they read as a unit.
        Surface(
            color = TT.Surface1.copy(alpha = 0.94f),
            shape = RoundedCornerShape(14.dp),
            // Width is pinned: a fillMaxWidth divider inside would otherwise
            // stretch the whole control across the map.
            modifier = Modifier.width(44.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clickable { onAction(MapAction.ZOOM_IN) },
                    contentAlignment = Alignment.Center,
                ) { PlusGlyph(TT.InkSecondary) }
                Box(
                    Modifier
                        .width(24.dp)
                        .height(1.dp)
                        .background(TT.Hairline),
                )
                Box(
                    Modifier
                        .size(44.dp)
                        .clickable { onAction(MapAction.ZOOM_OUT) },
                    contentAlignment = Alignment.Center,
                ) { MinusGlyph(TT.InkSecondary) }
            }
        }

        ControlButton(
            onClick = { onAction(MapAction.FIT) },
            contentDescription = "Fit the whole neighbourhood",
        ) { tint -> FitGlyph(tint) }
    }
}

@Composable
private fun ControlButton(
    onClick: () -> Unit,
    contentDescription: String,
    tint: Color = TT.InkSecondary,
    glyph: @Composable (Color) -> Unit,
) {
    Surface(
        color = TT.Surface1.copy(alpha = 0.94f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .size(44.dp)
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) { glyph(tint) }
    }
}

// ----------------------------------------------------------------- glyphs

@Composable
private fun LocateGlyph(tint: Color, active: Boolean) {
    Canvas(Modifier.size(20.dp)) {
        val r = size.minDimension / 2f
        val c = center
        drawCircle(tint, radius = r * 0.52f, center = c, style = Stroke(width = 2f))
        if (active) drawCircle(tint, radius = r * 0.2f, center = c)
        // Crosshair ticks.
        val tick = r * 0.3f
        listOf(
            Offset(c.x, c.y - r) to Offset(c.x, c.y - r + tick),
            Offset(c.x, c.y + r) to Offset(c.x, c.y + r - tick),
            Offset(c.x - r, c.y) to Offset(c.x - r + tick, c.y),
            Offset(c.x + r, c.y) to Offset(c.x + r - tick, c.y),
        ).forEach { (a, b) -> drawLine(tint, a, b, strokeWidth = 2f, cap = StrokeCap.Round) }
    }
}

@Composable
private fun PlusGlyph(tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        val c = center
        val r = size.minDimension / 2.4f
        drawLine(tint, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 2.4f, StrokeCap.Round)
        drawLine(tint, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 2.4f, StrokeCap.Round)
    }
}

@Composable
private fun MinusGlyph(tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        val c = center
        val r = size.minDimension / 2.4f
        drawLine(tint, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 2.4f, StrokeCap.Round)
    }
}

@Composable
private fun FitGlyph(tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        val s = size.minDimension
        val inset = s * 0.14f
        val arm = s * 0.26f
        val corners = listOf(
            Triple(Offset(inset, inset), Offset(inset + arm, inset), Offset(inset, inset + arm)),
            Triple(Offset(s - inset, inset), Offset(s - inset - arm, inset), Offset(s - inset, inset + arm)),
            Triple(Offset(inset, s - inset), Offset(inset + arm, s - inset), Offset(inset, s - inset - arm)),
            Triple(
                Offset(s - inset, s - inset),
                Offset(s - inset - arm, s - inset),
                Offset(s - inset, s - inset - arm),
            ),
        )
        corners.forEach { (corner, a, b) ->
            drawLine(tint, corner, a, 2.2f, StrokeCap.Round)
            drawLine(tint, corner, b, 2.2f, StrokeCap.Round)
        }
    }
}

@Composable
private fun SearchGlyph(tint: Color) {
    Canvas(Modifier.size(19.dp)) {
        val r = size.minDimension * 0.32f
        val c = Offset(size.width * 0.44f, size.height * 0.44f)
        drawCircle(tint, radius = r, center = c, style = Stroke(width = 2.2f))
        drawLine(
            tint,
            Offset(c.x + r * 0.72f, c.y + r * 0.72f),
            Offset(size.width * 0.9f, size.height * 0.9f),
            2.6f,
            StrokeCap.Round,
        )
    }
}

// ------------------------------------------------------------- where am I

/**
 * The orientation bar: what street you are on, how bad it is, and what is worth
 * walking next.
 *
 * This is the answer to "understand your surroundings" — a dot on a map tells
 * you where you are, but not what you are standing in. Tapping it opens the
 * segment, so the bar doubles as the fastest route into the thing underfoot.
 */
@Composable
fun NearbyBar(
    locationState: LocationState,
    segments: List<Segment>,
    onSelectSegment: (Segment) -> Unit,
    onLocate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fix = locationState.fix

    // Nearest segment, and the nearest one nobody has walked yet. Both are
    // cheap enough to recompute whenever the fix moves.
    val nearest = remember(fix, segments) {
        fix?.let { f ->
            segments.minByOrNull { distanceToPolylineMeters(f.location, it.path) }
        }
    }
    val nearestDistance = remember(fix, nearest) {
        if (fix != null && nearest != null) {
            distanceToPolylineMeters(fix.location, nearest.path)
        } else null
    }
    val nearestUnwalked = remember(fix, segments) {
        fix?.let { f ->
            segments.filter { !it.hasBeenSurveyed }
                .minByOrNull { distanceToPolylineMeters(f.location, it.path) }
        }
    }

    Surface(
        color = TT.Surface1.copy(alpha = 0.94f),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            when {
                locationState.availability == LocationAvailability.PROVIDERS_OFF -> {
                    Eyebrow("Location off")
                    Text(
                        text = "Turn on location to see where you are on the map",
                        style = MaterialTheme.typography.bodySmall,
                        color = TT.InkSecondary,
                    )
                }

                locationState.availability == LocationAvailability.DENIED -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Eyebrow("Where am I")
                            Text(
                                text = "Allow location to orient the map",
                                style = MaterialTheme.typography.bodySmall,
                                color = TT.InkSecondary,
                            )
                        }
                        Text(
                            text = "Allow",
                            style = MaterialTheme.typography.labelMedium,
                            color = TT.Teal,
                            modifier = Modifier.clickable(onClick = onLocate),
                        )
                    }
                }

                nearest == null -> {
                    Eyebrow("Locating")
                    Text(
                        text = "Finding your position...",
                        style = MaterialTheme.typography.bodySmall,
                        color = TT.InkSecondary,
                    )
                }

                else -> {
                    val band = HazardBand.of(nearest.litterIndex)
                    val onIt = (nearestDistance ?: 999.0) < 30
                    Row(
                        modifier = Modifier.clickable { onSelectSegment(nearest) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(
                                    if (nearest.hasBeenSurveyed) band.color
                                    else TT.Warning.copy(alpha = 0.3f),
                                ),
                        )
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = if (onIt) nearest.name else "Near ${nearest.name}",
                                style = MaterialTheme.typography.titleSmall,
                                color = TT.Ink,
                                maxLines = 1,
                            )
                            // An unwalked segment has a modelled index, not an
                            // observed one. Saying "Clear - 2 pieces" there would
                            // claim a measurement nobody has made, which is the
                            // exact failure the assessment is built to avoid.
                            Text(
                                text = buildString {
                                    if (nearest.hasBeenSurveyed) {
                                        append(band.label)
                                        append(" - ")
                                        append("${nearest.estimatedPieces} pieces")
                                    } else {
                                        append("Never surveyed - estimate only")
                                    }
                                    if (!onIt && nearestDistance != null) {
                                        append(" - ")
                                        append(formatDistance(nearestDistance))
                                        append(" away")
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (nearest.hasBeenSurveyed) TT.InkMuted else TT.Warning,
                                maxLines = 1,
                            )
                        }
                        if (fix != null && fix.accuracyMeters > 60f) {
                            Chip(text = "±${fix.accuracyMeters.toInt()} m", tint = TT.InkMuted)
                        }
                    }

                    // The most useful next move, stated plainly.
                    if (nearestUnwalked != null && fix != null) {
                        val d = distanceToPolylineMeters(fix.location, nearestUnwalked.path)
                        if (nearestUnwalked.id != nearest.id && d < 600) {
                            Spacer(Modifier.height(7.dp))
                            Row(
                                modifier = Modifier.clickable { onSelectSegment(nearestUnwalked) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier
                                        .size(9.dp)
                                        .clip(CircleShape)
                                        .background(TT.Warning.copy(alpha = 0.25f)),
                                )
                                Spacer(Modifier.width(9.dp))
                                Text(
                                    text = "Unwalked: ${nearestUnwalked.name}, ${formatDistance(d)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TT.Warning,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- search

/**
 * Street finder. Typing filters the network by name; tapping flies there.
 *
 * With 184 segments named after about twenty streets, scrolling is hopeless and
 * panning to find a specific block is worse. Sorted by distance when a fix is
 * available, because "the nearest Burr Rd block" is almost always the one meant.
 */
@Composable
fun SegmentSearchSheet(
    segments: List<Segment>,
    fix: DeviceFix?,
    onDismiss: () -> Unit,
    onPick: (Segment) -> Unit,
) {
    var query by remember { mutableStateOf("") }

    val results = remember(query, segments, fix) {
        val q = query.trim().lowercase()
        segments
            .filter { q.isEmpty() || it.name.lowercase().contains(q) }
            .sortedBy { seg ->
                if (fix != null) {
                    distanceToPolylineMeters(fix.location, seg.path)
                } else {
                    seg.name.lowercase().indexOf(q).let { if (it < 0) 9999.0 else it.toDouble() }
                }
            }
            .take(40)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = TT.Surface1,
        scrimColor = TT.Void.copy(alpha = 0.6f),
    ) {
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 24.dp)) {
            Eyebrow("Find a street")
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(11.dp))
                    .background(TT.Surface2)
                    .padding(horizontal = 13.dp, vertical = 12.dp),
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = "Street or block, e.g. Burr Rd",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TT.InkMuted,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = TT.Ink, fontSize = 15.sp),
                    cursorBrush = SolidColor(TT.Teal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.height(340.dp)) {
                items(results, key = { it.id }) { seg ->
                    val band = HazardBand.of(seg.litterIndex)
                    val d = fix?.let { distanceToPolylineMeters(it.location, seg.path) }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(seg) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(
                                    if (seg.hasBeenSurveyed) band.color
                                    else TT.Warning.copy(alpha = 0.3f),
                                ),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = seg.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TT.Ink,
                                maxLines = 1,
                            )
                            Text(
                                text = if (seg.hasBeenSurveyed) {
                                    "${band.label} - ${seg.estimatedPieces} pieces"
                                } else {
                                    "Never surveyed"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (seg.hasBeenSurveyed) TT.InkMuted else TT.Warning,
                            )
                        }
                        if (d != null) {
                            Text(
                                text = formatDistance(d),
                                style = MaterialTheme.typography.labelMedium,
                                color = TT.InkMuted,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The plan glyph: a route through three stops.
 *
 * Drawn rather than lifted from an icon set so it matches the weight of the
 * others in the stack; a heavier or lighter mark here would read as a
 * different kind of control.
 */
@Composable
private fun PlanGlyph(tint: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
        // Proportional, not raw pixels: the canvas is 20dp, which is 52 px on
        // this phone and 40 on a cheaper one, so a hard-coded 2.6 px dot is
        // invisible on the first and wrong on both.
        val r = size.minDimension * 0.11f
        val stops = listOf(
            androidx.compose.ui.geometry.Offset(size.width * 0.18f, size.height * 0.78f),
            androidx.compose.ui.geometry.Offset(size.width * 0.52f, size.height * 0.30f),
            androidx.compose.ui.geometry.Offset(size.width * 0.86f, size.height * 0.64f),
        )
        for (i in 0 until stops.lastIndex) {
            drawLine(
                color = tint,
                start = stops[i],
                end = stops[i + 1],
                strokeWidth = size.minDimension * 0.085f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
        stops.forEach { drawCircle(tint, radius = r, center = it) }
    }
}
