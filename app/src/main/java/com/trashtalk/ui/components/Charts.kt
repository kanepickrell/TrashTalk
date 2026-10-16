package com.trashtalk.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.trashtalk.ui.theme.TT
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Charting primitives for TrashTalk, built to one spec so every chart in the app
 * reads as the same system.
 *
 * The shared rules, all of which are load-bearing rather than decorative: thin
 * marks on a recessive grid; 2 px lines; touch targets larger than the marks; a
 * 2 px surface-coloured gap between adjacent fills so a stacked bar never reads
 * as one blurred mass; rounded data-ends anchored to the baseline; selective
 * direct labels instead of a number on every point; a legend whenever there is
 * more than one series, so identity never rests on colour alone; and a table view
 * on the charts whose fills sit below 3:1 against the surface.
 *
 * On a phone there is no hover, so the equivalent affordance is a tap: every
 * chart here exposes a readout for the selected mark and keeps it until the user
 * selects another.
 */

// ---------------------------------------------------------------- trend line

data class TrendPoint(val label: String, val value: Float)

/**
 * A single-series trend line. One series means no legend box -- the title names
 * it -- and only the last point is directly labelled, since a number on every
 * point is noise.
 */
@Composable
fun TrendLine(
    points: List<TrendPoint>,
    modifier: Modifier = Modifier,
    tint: Color = TT.LitterRampOrdinal[2],
    height: Dp = 120.dp,
    valueFormat: (Float) -> String = { "%.2f".format(it) },
    baselineAtZero: Boolean = true,
) {
    if (points.isEmpty()) return
    var selected by remember(points) { mutableIntStateOf(points.lastIndex) }

    val maxV = points.maxOf { it.value }
    val minV = if (baselineAtZero) 0f else points.minOf { it.value }
    val span = (maxV - minV).takeIf { it > 1e-6f } ?: 1f

    Column(modifier) {
        // The readout stands in for a hover tooltip.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(tint),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = valueFormat(points[selected].value),
                style = MaterialTheme.typography.titleSmall,
                color = TT.Ink,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = points[selected].label,
                style = MaterialTheme.typography.bodySmall,
                color = TT.InkMuted,
            )
        }
        Spacer(Modifier.height(8.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(points) {
                    fun pick(x: Float) {
                        val step = size.width / (points.size - 1).coerceAtLeast(1)
                        selected = (x / step).roundToInt().coerceIn(0, points.lastIndex)
                    }
                    detectTapGestures { pick(it.x) }
                }
                .pointerInput(points) {
                    detectDragGestures { change, _ ->
                        val step = size.width / (points.size - 1).coerceAtLeast(1)
                        selected = (change.position.x / step).roundToInt()
                            .coerceIn(0, points.lastIndex)
                    }
                },
        ) {
            val stepX = size.width / (points.size - 1).coerceAtLeast(1)
            fun yOf(v: Float) = size.height - ((v - minV) / span) * size.height

            // Recessive grid: three hairlines, no axis box.
            for (f in listOf(0f, 0.5f, 1f)) {
                val y = size.height * f
                drawLine(
                    color = TT.Hairline,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                )
            }

            val path = Path()
            points.forEachIndexed { i, p ->
                val x = i * stepX
                val y = yOf(p.value)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }

            // A faint fill under the line gives magnitude without stealing focus.
            val fill = Path().apply {
                addPath(path)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(fill, color = tint.copy(alpha = 0.10f))
            drawPath(
                path = path,
                color = tint,
                style = Stroke(width = 2f, cap = StrokeCap.Round),
            )

            // Selected mark: a 9px dot with a 2px surface ring so it stays legible
            // wherever it lands on the line.
            val sx = selected * stepX
            val sy = yOf(points[selected].value)
            drawLine(
                color = TT.Baseline,
                start = Offset(sx, 0f),
                end = Offset(sx, size.height),
                strokeWidth = 1f,
            )
            drawCircle(color = TT.Surface1, radius = 6.5f, center = Offset(sx, sy))
            drawCircle(color = tint, radius = 4.5f, center = Offset(sx, sy))
        }

        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(
                text = points.first().label,
                style = MaterialTheme.typography.labelSmall,
                color = TT.InkMuted,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = points.last().label,
                style = MaterialTheme.typography.labelSmall,
                color = TT.InkMuted,
            )
        }
    }
}

// ------------------------------------------------------------- stacked share

data class ShareSlice(val label: String, val share: Float, val detail: String)

/**
 * A single stacked bar for composition, with a legend and an optional table.
 *
 * Composition is identity, so this uses the categorical palette in fixed slot
 * order -- never cycled, and folded into "Other" past the eighth slot by the
 * caller. Adjacent fills are separated by a 2 px surface gap so the boundaries
 * are structural rather than relying on colour contrast between neighbours.
 */
@Composable
fun StackedShareBar(
    slices: List<ShareSlice>,
    modifier: Modifier = Modifier,
    barHeight: Dp = 26.dp,
    showTableToggle: Boolean = true,
) {
    if (slices.isEmpty()) return
    var showTable by remember { mutableStateOf(false) }
    var selected by remember(slices) { mutableStateOf<Int?>(null) }

    val colors = slices.mapIndexed { i, s ->
        if (s.label == "Other") TT.InkMuted else TT.categorical(i)
    }
    val total = slices.sumOf { it.share.toDouble() }.toFloat().takeIf { it > 0f } ?: 1f

    Column(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .clip(RoundedCornerShape(6.dp))
                .pointerInput(slices) {
                    detectTapGestures { offset ->
                        var acc = 0f
                        slices.forEachIndexed { i, s ->
                            val w = (s.share / total) * size.width
                            if (offset.x >= acc && offset.x < acc + w) {
                                selected = if (selected == i) null else i
                                return@detectTapGestures
                            }
                            acc += w
                        }
                    }
                },
        ) {
            var x = 0f
            val gap = 2f
            slices.forEachIndexed { i, s ->
                val w = (s.share / total) * size.width
                val drawW = (w - gap).coerceAtLeast(1f)
                drawRect(
                    color = colors[i].let {
                        if (selected == null || selected == i) it else it.copy(alpha = 0.34f)
                    },
                    topLeft = Offset(x, 0f),
                    size = androidx.compose.ui.geometry.Size(drawW, size.height),
                )
                x += w
            }
        }

        Spacer(Modifier.height(10.dp))

        if (selected != null) {
            val s = slices[selected!!]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(colors[selected!!]),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = "${s.label} - ${(s.share * 100).roundToInt()}%",
                    style = MaterialTheme.typography.titleSmall,
                    color = TT.Ink,
                )
            }
            Text(
                text = s.detail,
                style = MaterialTheme.typography.bodySmall,
                color = TT.InkMuted,
                modifier = Modifier.padding(start = 16.dp, top = 1.dp),
            )
            Spacer(Modifier.height(8.dp))
        }

        // The legend is always present: identity never rests on colour alone.
        LegendGrid(
            labels = slices.map { it.label },
            colors = colors,
            values = slices.map { "${(it.share * 100).roundToInt()}%" },
        )

        if (showTableToggle) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (showTable) "Hide table" else "Show as table",
                style = MaterialTheme.typography.labelMedium,
                color = TT.Teal,
                modifier = Modifier.clickable { showTable = !showTable },
            )
            if (showTable) {
                Spacer(Modifier.height(6.dp))
                Hairline()
                slices.forEachIndexed { i, s ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(colors[i]),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = s.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TT.InkSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        TabularNumber("${(s.share * 100).roundToInt()}%")
                        Spacer(Modifier.width(12.dp))
                        TabularNumber(s.detail, color = TT.InkMuted)
                    }
                }
            }
        }
    }
}

/** Legend rows: swatch, label, value. Wraps in two columns to stay compact. */
@Composable
fun LegendGrid(
    labels: List<String>,
    colors: List<Color>,
    values: List<String>? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        labels.chunked(2).forEachIndexed { rowIndex, chunk ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                chunk.forEachIndexed { colIndex, label ->
                    val i = rowIndex * 2 + colIndex
                    Row(
                        modifier = Modifier.weight(1f).padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(colors.getOrElse(i) { TT.InkMuted }),
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall,
                            color = TT.InkSecondary,
                            maxLines = 1,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (values != null) {
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = values.getOrElse(i) { "" },
                                style = MaterialTheme.typography.labelMedium,
                                color = TT.InkMuted,
                            )
                        }
                    }
                }
                if (chunk.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// -------------------------------------------------------------- ordinal bars

data class BarDatum(
    val label: String,
    val value: Float,
    val display: String,
    val caption: String? = null,
)

/**
 * Horizontal bars for an ordered set of buckets -- distance bands, street
 * classes, hazard levels.
 *
 * Ordered buckets are ordinal, not categorical, so the colour comes from a
 * one-hue ramp whose lightness carries the order; the reader sees the ranking in
 * the colour as well as the length. Bars are directly labelled because there are
 * few enough of them for that to stay clean.
 */
@Composable
fun OrdinalBars(
    data: List<BarDatum>,
    modifier: Modifier = Modifier,
    ramp: List<Color> = TT.LitterRampOrdinal,
    maxValue: Float? = null,
    barHeight: Dp = 10.dp,
) {
    if (data.isEmpty()) return
    val top = maxValue ?: data.maxOf { it.value }.takeIf { it > 0f } ?: 1f

    Column(modifier.fillMaxWidth()) {
        data.forEachIndexed { i, d ->
            val tint = if (data.size == 1) {
                ramp[ramp.size / 2]
            } else {
                TT.sample(ramp, i.toFloat() / (data.size - 1))
            }
            Column(Modifier.padding(vertical = 5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = d.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TT.InkSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = d.display,
                        style = MaterialTheme.typography.titleSmall,
                        color = TT.Ink,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(barHeight)
                        .clip(RoundedCornerShape(4.dp))
                        .background(TT.Surface2),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth((d.value / top).coerceIn(0.01f, 1f))
                            .height(barHeight)
                            .clip(RoundedCornerShape(4.dp))
                            .background(tint),
                    )
                }
                if (d.caption != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = d.caption,
                        style = MaterialTheme.typography.labelSmall,
                        color = TT.InkMuted,
                    )
                }
            }
        }
    }
}

/**
 * Status-coded bars, where the buckets mean good-to-critical rather than simply
 * more-to-less. Status colours are reserved and always carry a dot plus a label,
 * so the state never rests on hue.
 */
@Composable
fun StatusBars(
    data: List<Triple<String, Float, Color>>,
    modifier: Modifier = Modifier,
    valueText: (Float) -> String = { "${(it * 100).roundToInt()}%" },
) {
    if (data.isEmpty()) return
    val top = data.maxOf { it.second }.takeIf { it > 0f } ?: 1f
    Column(modifier.fillMaxWidth()) {
        for ((label, value, tint) in data) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(tint),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TT.InkSecondary,
                    modifier = Modifier.width(78.dp),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(9.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(TT.Surface2),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth((value / top).coerceIn(0f, 1f))
                            .height(9.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(tint),
                    )
                }
                Spacer(Modifier.width(10.dp))
                TabularNumber(valueText(value), modifier = Modifier.width(44.dp))
            }
        }
    }
}

// ------------------------------------------------------------ diverging bars

/**
 * Diverging bars around a zero baseline: improving to the left, worsening to the
 * right, with a neutral grey centre line. Two hues plus a neutral midpoint,
 * never a single ramp, because the quantity has a sign.
 */
@Composable
fun DivergingBars(
    data: List<BarDatum>,
    modifier: Modifier = Modifier,
    barHeight: Dp = 10.dp,
) {
    if (data.isEmpty()) return
    val extent = max(
        data.maxOf { it.value },
        abs(data.minOf { it.value }),
    ).takeIf { it > 0f } ?: 1f

    Column(modifier.fillMaxWidth()) {
        data.forEach { d ->
            Column(Modifier.padding(vertical = 5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = d.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TT.InkSecondary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    Text(
                        text = d.display,
                        style = MaterialTheme.typography.titleSmall,
                        color = TT.changeColor(d.value),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(barHeight),
                ) {
                    val mid = size.width / 2f
                    drawLine(
                        color = TT.DivergingMid,
                        start = Offset(mid, 0f),
                        end = Offset(mid, size.height),
                        strokeWidth = 2f,
                    )
                    val w = (abs(d.value) / extent) * (size.width / 2f - 2f)
                    val left = if (d.value < 0) mid - w else mid + 2f
                    drawRoundRect(
                        color = TT.changeColor(d.value),
                        topLeft = Offset(left, 0f),
                        size = androidx.compose.ui.geometry.Size(w.coerceAtLeast(2f), size.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------- ramp legend

/**
 * The map's sequential legend. Discrete chips rather than a smooth gradient, and
 * taken from the floored subset of the ramp so the lightest chip still clears
 * contrast against the panel it sits on.
 */
@Composable
fun RampLegend(
    lowLabel: String,
    highLabel: String,
    modifier: Modifier = Modifier,
    ramp: List<Color> = TT.LitterRampOrdinal,
) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ramp.forEach { c ->
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
                text = lowLabel,
                style = MaterialTheme.typography.labelSmall,
                color = TT.InkMuted,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = highLabel,
                style = MaterialTheme.typography.labelSmall,
                color = TT.InkMuted,
                textAlign = TextAlign.End,
            )
        }
    }
}

/** Compact inline sparkline for list rows. No axes, no labels, no interaction. */
@Composable
fun MicroSpark(
    values: List<Float>,
    modifier: Modifier = Modifier,
    tint: Color = TT.InkMuted,
) {
    if (values.size < 2) return
    Canvas(modifier) {
        val maxV = values.max()
        val minV = values.min()
        val span = (maxV - minV).takeIf { it > 1e-6f } ?: 1f
        val stepX = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height - ((v - minV) / span) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = tint, style = Stroke(width = 1.6f, cap = StrokeCap.Round))
    }
}
