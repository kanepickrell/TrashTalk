package com.trashtalk.ui.lia

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trashtalk.core.formatDistance
import com.trashtalk.domain.LiaEngine
import com.trashtalk.ui.MainViewModel
import com.trashtalk.ui.components.BarDatum
import com.trashtalk.ui.components.Chip
import com.trashtalk.ui.components.DataRow
import com.trashtalk.ui.components.DivergingBars
import com.trashtalk.ui.components.Eyebrow
import com.trashtalk.ui.components.Hairline
import com.trashtalk.ui.components.HeroFigure
import com.trashtalk.ui.components.OrdinalBars
import com.trashtalk.ui.components.Panel
import com.trashtalk.ui.components.SectionHeader
import com.trashtalk.ui.components.ShareSlice
import com.trashtalk.ui.components.StackedShareBar
import com.trashtalk.ui.components.StatusBars
import com.trashtalk.ui.components.StatusChip
import com.trashtalk.ui.components.TrendLine
import com.trashtalk.ui.components.TrendPoint
import com.trashtalk.ui.theme.TT
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The Litter Impact Assessment.
 *
 * This screen is the project's output to the outside world -- the thing a council
 * member, a business improvement district or a researcher receives. It is
 * therefore built to be checked rather than admired: coverage and confidence sit
 * at the top, before any headline figure; every normalised number says what it
 * was normalised by; and the recommendations are derived from the distance-decay
 * analysis rather than asserted.
 *
 * Chart forms follow from the data's job, not from variety. Magnitude by ordered
 * bucket gets ordinal bars on a one-hue ramp. Composition gets a single stacked
 * bar with a legend and a table. Signed change gets diverging bars around a
 * neutral midpoint. The trend gets one line with one directly labelled point.
 */
@Composable
fun ReportScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val report by vm.report.collectAsStateWithLifecycle()
    val segments by vm.segments.collectAsStateWithLifecycle()

    val dateFormat = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(TT.Void),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {

        // ------------------------------------------------------------- masthead
        item {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Back",
                        style = MaterialTheme.typography.labelLarge,
                        color = TT.Teal,
                        modifier = Modifier.clickable(onClick = onBack),
                    )
                }
                Spacer(Modifier.height(14.dp))
                Eyebrow("Litter Impact Assessment")
                Spacer(Modifier.height(4.dp))
                Text(
                    text = report.neighborhood,
                    style = MaterialTheme.typography.headlineMedium,
                    color = TT.Ink,
                )
                Text(
                    text = "${report.city} - generated ${dateFormat.format(Date(report.generatedAt))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TT.InkMuted,
                )
            }
        }

        // --------------------------------------------------- coverage first
        item {
            Panel(
                accent = when (report.confidence) {
                    "High" -> TT.Good
                    "Moderate" -> TT.Warning
                    else -> TT.Serious
                },
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Eyebrow("Confidence")
                        Spacer(Modifier.weight(1f))
                        StatusChip(
                            label = report.confidence,
                            tint = when (report.confidence) {
                                "High" -> TT.Good
                                "Moderate" -> TT.Warning
                                else -> TT.Serious
                            },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = report.confidenceNote,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TT.InkSecondary,
                    )
                    Spacer(Modifier.height(12.dp))
                    Hairline()
                    Spacer(Modifier.height(4.dp))
                    DataRow(
                        label = "Segments surveyed",
                        value = "${report.surveyedSegments} of ${report.totalSegments}",
                        caption = "${(report.coverageFraction * 100).roundToInt()}% of the network",
                    )
                    DataRow(
                        label = "Network length",
                        value = formatDistance(report.networkMeters),
                        caption = "${formatDistance(report.surveyedMeters)} walked at least once",
                    )
                }
            }
        }

        // -------------------------------------------------------- the headline
        item {
            Panel {
                Column {
                    Eyebrow("Standing litter load")
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        HeroFigure(
                            value = "${report.estimatedPieces}",
                            label = "pieces on the ground",
                            accent = TT.LitterRampOrdinal[2],
                            sublabel = "across ${report.totalSegments} segments",
                            modifier = Modifier.weight(1f),
                        )
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "%.1f kg".format(report.estimatedKilograms),
                                style = MaterialTheme.typography.headlineSmall,
                                color = TT.Ink,
                            )
                            Text(
                                text = "estimated mass",
                                style = MaterialTheme.typography.labelSmall,
                                color = TT.InkMuted,
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Hairline()
                    Spacer(Modifier.height(4.dp))
                    DataRow(
                        label = "Density",
                        value = "%.1f".format(report.meanPiecesPer100m),
                        caption = "pieces per 100 m of street",
                    )
                    DataRow(
                        label = "Median litter index",
                        value = "%.2f".format(report.medianLitterIndex),
                        caption = "0 = clear, 1 = dumping-scale",
                    )
                    DataRow(
                        label = "Single-use plastic",
                        value = "${(report.singleUsePlasticShare * 100).roundToInt()}%",
                        caption = "share of all pieces by count",
                        valueColor = if (report.singleUsePlasticShare > 0.5f) TT.Serious else TT.Ink,
                    )
                }
            }
        }

        // -------------------------------------------------------------- trend
        if (report.trend.size > 1) {
            item {
                Panel {
                    Column {
                        SectionHeaderSmall(
                            eyebrow = "Trend",
                            title = "Network litter index, last ${report.trend.size} days",
                        )
                        Spacer(Modifier.height(12.dp))
                        TrendLine(
                            points = report.trend.mapIndexed { i, v ->
                                TrendPoint(
                                    label = when (i) {
                                        report.trend.lastIndex -> "today"
                                        else -> "${report.trend.lastIndex - i}d ago"
                                    },
                                    value = v,
                                )
                            },
                            valueFormat = { "%.3f".format(it) },
                            baselineAtZero = false,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip(
                                text = "${report.improvingSegments} improving",
                                tint = TT.ImprovingArm[2],
                                filled = true,
                            )
                            Chip(
                                text = "${report.worseningSegments} worsening",
                                tint = TT.WorseningArm[2],
                                filled = true,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = if (report.netChange > 0.005f) {
                                "The network is accumulating faster than it is being cleared. " +
                                    "Volunteer effort is not yet keeping pace with the inflow."
                            } else if (report.netChange < -0.005f) {
                                "The network is getting cleaner over the window. Current effort " +
                                    "is outpacing accumulation."
                            } else {
                                "The network is roughly in balance: cleanups and accumulation " +
                                    "are cancelling out."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = TT.InkSecondary,
                        )
                    }
                }
            }
        }

        // -------------------------------------------------------- composition
        if (report.composition.isNotEmpty()) {
            item {
                Panel {
                    Column {
                        SectionHeaderSmall(
                            eyebrow = "Composition",
                            title = "What the litter actually is",
                        )
                        Spacer(Modifier.height(14.dp))
                        StackedShareBar(
                            slices = report.composition.map {
                                ShareSlice(
                                    label = it.label,
                                    share = it.share,
                                    detail = "${it.pieces} pieces",
                                )
                            },
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Shares are by piece count, weighted by each segment's " +
                                "estimated load. Mass is computed from this mix rather than an " +
                                "average piece weight, because a stream that is a third glass " +
                                "weighs nothing like one that is a third film.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TT.InkMuted,
                        )
                    }
                }
            }
        }

        // ------------------------------------------------------------- hazard
        if (report.bands.isNotEmpty()) {
            item {
                Panel {
                    Column {
                        SectionHeaderSmall(
                            eyebrow = "Severity",
                            title = "Network length by hazard band",
                        )
                        Spacer(Modifier.height(14.dp))
                        StatusBars(
                            data = report.bands.map {
                                Triple(it.band.label, it.share, it.band.color)
                            },
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "Share of street length in each band. Severity is a " +
                                "reserved scale: every band carries its own label and marker " +
                                "so the reading never depends on colour alone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TT.InkMuted,
                        )
                    }
                }
            }
        }

        // ------------------------------------------------------------ drivers
        if (report.drivers.isNotEmpty()) {
            item {
                Panel(accent = TT.Warning) {
                    Column {
                        SectionHeaderSmall(
                            eyebrow = "Drivers",
                            title = "Litter density by distance to generator",
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Mean litter index for segments binned by their distance to " +
                                "the nearest site of each kind. A steep gradient identifies a " +
                                "generator; a flat one rules it out.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TT.InkMuted,
                        )
                        Spacer(Modifier.height(14.dp))

                        report.drivers.groupBy { it.label }.forEach { (kind, rows) ->
                            Eyebrow(kind)
                            Spacer(Modifier.height(6.dp))
                            OrdinalBars(
                                data = rows.map { row ->
                                    BarDatum(
                                        label = row.distanceBand,
                                        value = row.meanIndex,
                                        display = "%.2f".format(row.meanIndex),
                                        caption = "${row.segments} segments",
                                    )
                                }.reversed(),
                                maxValue = report.drivers.maxOf { it.meanIndex },
                            )
                            Spacer(Modifier.height(14.dp))
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------ street class
        if (report.byClass.isNotEmpty()) {
            item {
                Panel {
                    Column {
                        SectionHeaderSmall(
                            eyebrow = "By street type",
                            title = "Where the load concentrates",
                        )
                        Spacer(Modifier.height(14.dp))
                        OrdinalBars(
                            data = report.byClass.map {
                                BarDatum(
                                    label = it.streetClass.label,
                                    value = it.piecesPer100m.toFloat(),
                                    display = "%.1f".format(it.piecesPer100m),
                                    caption = "per 100 m - ${formatDistance(it.meters)} of network",
                                )
                            },
                        )
                    }
                }
            }
        }

        // ----------------------------------------------------------- hotspots
        if (report.hotspots.isNotEmpty()) {
            item {
                Panel {
                    Column {
                        SectionHeaderSmall(
                            eyebrow = "Priority segments",
                            title = "The eight worst blocks",
                        )
                        Spacer(Modifier.height(12.dp))
                        report.hotspots.forEach { row ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.selectSegment(row.segment.id) }
                                    .padding(vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier.width(22.dp),
                                ) {
                                    Text(
                                        text = "${row.rank}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = TT.InkMuted,
                                    )
                                }
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(TT.litterColor(row.segment.litterIndex)),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = row.segment.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TT.Ink,
                                    )
                                    Text(
                                        text = "${row.segment.estimatedPieces} pieces - " +
                                            formatDistance(row.segment.lengthMeters),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TT.InkMuted,
                                    )
                                }
                                Text(
                                    text = "%.0f".format(row.piecesPer100m),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = TT.Ink,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "/100m",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TT.InkMuted,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ------------------------------------------------- biggest movements
        item {
            val movers = segments
                .filter { it.history.size > 7 }
                .sortedByDescending { kotlin.math.abs(it.sevenDayChange) }
                .take(6)
            if (movers.isNotEmpty()) {
                Panel {
                    Column {
                        SectionHeaderSmall(
                            eyebrow = "Movement",
                            title = "Biggest 7-day swings",
                        )
                        Spacer(Modifier.height(14.dp))
                        DivergingBars(
                            data = movers.map {
                                BarDatum(
                                    label = it.name,
                                    value = it.sevenDayChange,
                                    display = (if (it.sevenDayChange > 0) "+" else "") +
                                        "%.0f%%".format(it.sevenDayChange * 100),
                                )
                            },
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "Left of the centre line is improving, right is worsening.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TT.InkMuted,
                        )
                    }
                }
            }
        }

        // ---------------------------------------------------- recommendations
        if (report.recommendations.isNotEmpty()) {
            item {
                Panel(accent = TT.Teal) {
                    Column {
                        SectionHeaderSmall(
                            eyebrow = "What to do about it",
                            title = "Recommendations from this data",
                        )
                        Spacer(Modifier.height(12.dp))
                        report.recommendations.forEachIndexed { i, rec ->
                            Row(Modifier.padding(vertical = 7.dp)) {
                                Text(
                                    text = "${i + 1}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TT.Teal,
                                    modifier = Modifier.width(20.dp),
                                )
                                Text(
                                    text = rec,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TT.InkSecondary,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------ volunteer effort
        item {
            Panel {
                Column {
                    SectionHeaderSmall(
                        eyebrow = "Recorded effort",
                        title = "What volunteers have logged",
                    )
                    Spacer(Modifier.height(10.dp))
                    DataRow(label = "Collections logged", value = "${report.loggedCollections}")
                    DataRow(label = "Pieces removed", value = "${report.loggedPieces}")
                    DataRow(
                        label = "Volunteer time",
                        value = "${report.volunteerMinutes / 60}h ${report.volunteerMinutes % 60}m",
                    )
                    if (report.loggedCollections > 0) {
                        DataRow(
                            label = "Average per collection",
                            value = "%.1f pieces".format(
                                report.loggedPieces.toDouble() / report.loggedCollections,
                            ),
                        )
                    }
                }
            }
        }

        // -------------------------------------------------------------- method
        item {
            Panel(tone = TT.Surface1.copy(alpha = 0.7f)) {
                Column {
                    Eyebrow("Method and limitations")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = vm.network.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = TT.InkMuted,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Segment litter indices combine volunteer survey counts with a " +
                            "re-accumulation model fitted per segment. Mass is derived from " +
                            "observed composition and per-type average weights, so it is an " +
                            "estimate with roughly a two-fold uncertainty band, not a weighed " +
                            "figure. Density is reported per 100 m because raw counts are not " +
                            "comparable between a cul-de-sac and an arterial. Where coverage " +
                            "is below a third of segments, neighbourhood totals should be " +
                            "read as lower bounds.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TT.InkMuted,
                    )
                }
            }
        }
    }
}

/** Panel-internal header, smaller than the screen-level one. */
@Composable
private fun SectionHeaderSmall(eyebrow: String, title: String) {
    Column {
        Eyebrow(eyebrow)
        Spacer(Modifier.height(3.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = TT.Ink,
        )
    }
}
