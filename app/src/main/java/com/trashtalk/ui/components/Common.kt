package com.trashtalk.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trashtalk.ui.theme.CardCorner
import com.trashtalk.ui.theme.EyebrowStyle
import com.trashtalk.ui.theme.LocalReduceMotion
import com.trashtalk.ui.theme.TT
import com.trashtalk.ui.theme.rememberMotion

/** The standard panel: one hairline ring, generous padding, no drop shadow. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    tone: Color = TT.Surface1,
    corner: Dp = CardCorner,
    accent: Color? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        color = tone,
        shape = RoundedCornerShape(corner),
        border = BorderStroke(1.dp, accent?.copy(alpha = 0.34f) ?: TT.Hairline),
    ) {
        Box(Modifier.padding(16.dp)) { content() }
    }
}

/** Small all-caps section label. */
@Composable
fun Eyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = TT.InkMuted,
) {
    Text(
        text = text.uppercase(),
        style = EyebrowStyle,
        color = color,
        modifier = modifier,
    )
}

@Composable
fun SectionHeader(
    eyebrow: String,
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Eyebrow(eyebrow)
            Spacer(Modifier.height(3.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = TT.Ink,
            )
        }
        trailing?.invoke()
    }
}

/**
 * A single figure with its label. Not a chart: when the answer is one number, a
 * number is the right form, and a sparkline only earns its place if the shape of
 * the trend is part of the point.
 */
@Composable
fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    accent: Color = TT.Ink,
    caption: String? = null,
) {
    Column(modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = accent,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TT.InkSecondary,
        )
        if (caption != null) {
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = TT.InkMuted,
            )
        }
    }
}

/** A compact status or metadata chip. */
@Composable
fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = TT.InkSecondary,
    filled: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(if (filled) tint.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.dp, tint.copy(alpha = if (filled) 0.42f else 0.3f), CircleShape)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        leading?.invoke()
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
        )
    }
}

/**
 * Status chip: a reserved colour plus a glyph and a label, never colour alone.
 * The dot is the icon channel -- it keeps the meaning legible in greyscale, under
 * colour-vision deficiency, and in forced-colours mode.
 */
@Composable
fun StatusChip(
    label: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(tint),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = TT.Ink,
        )
    }
}

/** Horizontal selector. Scrolls rather than squeezing when options are many. */
@Composable
fun <T> SegmentedPicker(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(TT.Surface2)
            .padding(3.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (option in options) {
            val isSelected = option == selected
            val bg by animateColorAsState(
                if (isSelected) TT.Surface3 else Color.Transparent,
                label = "seg-bg",
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(bg)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 13.dp, vertical = 8.dp),
            ) {
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) TT.Ink else TT.InkMuted,
                )
            }
        }
    }
}

/** Primary action. */
@Composable
fun TrashTalkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: Color = TT.Teal,
) {
    val motion = rememberMotion()
    Surface(
        modifier = modifier.clickable(enabled = enabled) {
            motion.lightTick()
            onClick()
        },
        color = if (enabled) tone else TT.Surface2,
        shape = RoundedCornerShape(13.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) TT.Void else TT.InkMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 13.dp),
        )
    }
}

/** Secondary action: same geometry, hairline instead of fill. */
@Composable
fun TrashTalkOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = TT.InkSecondary,
) {
    val motion = rememberMotion()
    Surface(
        modifier = modifier.clickable(enabled = enabled) {
            motion.lightTick()
            onClick()
        },
        color = Color.Transparent,
        shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.4f)),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) tint else TT.InkMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 13.dp),
        )
    }
}

/**
 * A progress bar with rounded data-ends anchored at the left baseline, per the
 * mark spec. The track is recessive; the fill carries the colour.
 */
@Composable
fun ProgressTrack(
    fraction: Float,
    modifier: Modifier = Modifier,
    tint: Color = TT.Teal,
    thickness: Dp = 6.dp,
) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "progress")
    Box(
        modifier
            .fillMaxWidth()
            .height(thickness)
            .clip(CircleShape)
            .background(TT.Surface3),
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .height(thickness)
                .clip(CircleShape)
                .background(tint),
        )
    }
}

/** Ring gauge for a single bounded fraction, used for path and level progress. */
@Composable
fun ProgressRing(
    fraction: Float,
    modifier: Modifier = Modifier,
    tint: Color = TT.Teal,
    thickness: Dp = 6.dp,
    content: @Composable () -> Unit = {},
) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "ring")
    Box(modifier, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(0.dp)) {}
        androidx.compose.foundation.Canvas(
            modifier = Modifier.matchParentSize(),
        ) {
            val stroke = thickness.toPx()
            val inset = stroke / 2f
            drawArc(
                color = TT.Surface3,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(
                    size.width - stroke,
                    size.height - stroke,
                ),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = tint,
                startAngle = -90f,
                sweepAngle = 360f * animated,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(
                    size.width - stroke,
                    size.height - stroke,
                ),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        content()
    }
}

/** The hero figure: one number, proportional figures, no chart. */
@Composable
fun HeroFigure(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    accent: Color = TT.Teal,
    sublabel: String? = null,
) {
    Column(modifier, horizontalAlignment = Alignment.Start) {
        Text(
            text = value,
            style = MaterialTheme.typography.displaySmall,
            color = accent,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TT.InkSecondary,
        )
        if (sublabel != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = sublabel,
                style = MaterialTheme.typography.labelSmall,
                color = TT.InkMuted,
            )
        }
    }
}

/** A dim gradient strip used to fade content under a floating overlay. */
@Composable
fun ScrimGradient(
    modifier: Modifier = Modifier,
    fromTop: Boolean = false,
    height: Dp = 96.dp,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(
                Brush.verticalGradient(
                    if (fromTop) {
                        listOf(TT.Void.copy(alpha = 0.92f), Color.Transparent)
                    } else {
                        listOf(Color.Transparent, TT.Void.copy(alpha = 0.94f))
                    },
                ),
            ),
    )
}

/** Key-value row used throughout the detail sheets and the assessment. */
@Composable
fun DataRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = TT.Ink,
    caption: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = TT.InkSecondary,
            )
            if (caption != null) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = TT.InkMuted,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = valueColor,
            textAlign = TextAlign.End,
        )
    }
}

/** Hairline divider at the documented gridline tone. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(TT.Hairline),
    )
}

/**
 * A shimmering placeholder for content that is still loading.
 *
 * The point is to distinguish "we are fetching this" from "there is nothing
 * here" -- an empty state shown during a load is a small lie, and the reader
 * acts on it by leaving. Under reduce-motion the sweep is dropped and a static
 * block is drawn, which still says "pending" without the movement.
 */
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    corner: Dp = 8.dp,
) {
    val reduced = LocalReduceMotion.current
    val base = TT.Surface2
    val highlight = TT.Surface3

    if (reduced) {
        Box(modifier.clip(RoundedCornerShape(corner)).background(base))
        return
    }

    val transition = rememberInfiniteTransition(label = "skeleton")
    val progress by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
        ),
        label = "sweep",
    )

    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .drawWithCache {
                // The sweep is a gradient whose stops slide across the box, so
                // the brush is rebuilt only when the size or progress changes.
                val width = size.width
                val brush = Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to base,
                        0.5f to highlight,
                        1f to base,
                    ),
                    start = Offset(progress * width - width * 0.4f, 0f),
                    end = Offset(progress * width + width * 0.4f, 0f),
                )
                onDrawBehind { drawRect(brush) }
            },
    )
}

/** A stack of skeleton lines standing in for a list of rows. */
@Composable
fun SkeletonRows(
    count: Int = 4,
    modifier: Modifier = Modifier,
    rowHeight: Dp = 44.dp,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(count) { i ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                SkeletonBox(Modifier.size(rowHeight * 0.4f), corner = 20.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Varied widths so it reads as text rather than a grid.
                    SkeletonBox(
                        Modifier
                            .fillMaxWidth(if (i % 2 == 0) 0.62f else 0.48f)
                            .height(12.dp),
                    )
                    SkeletonBox(
                        Modifier
                            .fillMaxWidth(if (i % 3 == 0) 0.34f else 0.28f)
                            .height(9.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                SkeletonBox(Modifier.width(38.dp).height(14.dp))
            }
        }
    }
}

/** Empty-state block: says what is missing and what to do about it. */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = TT.Ink,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = TT.InkMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        if (action != null) {
            Spacer(Modifier.height(14.dp))
            action()
        }
    }
}

/** Monospaced-ish numeric label for table columns that must align vertically. */
@Composable
fun TabularNumber(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = TT.Ink,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontFeatureSettings = "tnum",
        ),
    )
}
