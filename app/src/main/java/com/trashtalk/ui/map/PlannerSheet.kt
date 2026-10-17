package com.trashtalk.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trashtalk.core.LatLng
import com.trashtalk.core.formatDistance
import com.trashtalk.data.model.Segment
import com.trashtalk.domain.RoutePlanner
import com.trashtalk.ui.components.Eyebrow
import com.trashtalk.ui.components.Hairline
import com.trashtalk.ui.components.TrashTalkButton
import com.trashtalk.ui.theme.TT
import com.trashtalk.ui.theme.rememberMotion

/**
 * "Where should I walk today", with what it is worth before you go.
 *
 * The preview is the point. Anybody can pick a direction; what people cannot do
 * from a map is tell which hour of walking produces the most, so the sheet puts
 * the coverage bonuses and the expected haul next to each option before the
 * decision rather than after it.
 */
@Composable
fun PlannerSheet(
    from: LatLng,
    segments: List<Segment>,
    onStart: (RoutePlanner.Plan) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = rememberMotion()
    var focus by remember { mutableStateOf(RoutePlanner.Focus.UNSURVEYED) }
    var budget by remember { mutableStateOf(2_000.0) }

    val plan = remember(from, segments, focus, budget) {
        RoutePlanner.plan(from, segments, focus, budget)
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(TT.Surface1)
            .padding(20.dp),
    ) {
        Eyebrow("Plan a walk")
        Spacer(Modifier.height(12.dp))

        Text("What are you after?", style = MaterialTheme.typography.titleSmall, color = TT.Ink)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoutePlanner.Focus.entries.forEach { option ->
                Chip(
                    text = option.label,
                    selected = option == focus,
                    onClick = {
                        motion.selectionTick()
                        focus = option
                    },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = focus.caption,
            style = MaterialTheme.typography.labelSmall,
            color = TT.InkMuted,
        )

        Spacer(Modifier.height(18.dp))
        Text("How far?", style = MaterialTheme.typography.titleSmall, color = TT.Ink)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1_000.0, 2_000.0, 4_000.0).forEach { metres ->
                Chip(
                    text = formatDistance(metres),
                    selected = metres == budget,
                    onClick = {
                        motion.selectionTick()
                        budget = metres
                    },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Hairline()
        Spacer(Modifier.height(16.dp))

        if (plan.isEmpty) {
            Text(
                text = "Nothing matches that within range. Try a different focus " +
                    "or a longer walk.",
                style = MaterialTheme.typography.bodyMedium,
                color = TT.InkSecondary,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                Stat("${plan.targets.size}", "blocks")
                Stat(formatDistance(plan.distanceMeters), "to walk")
                Stat("~${plan.minutes}", "min")
            }
            Spacer(Modifier.height(14.dp))

            if (plan.coverageBonuses > 0) {
                Gain(
                    value = "+${plan.coverageBonuses * 50}",
                    label = "${plan.coverageBonuses} coverage " +
                        if (plan.coverageBonuses == 1) "bonus" else "bonuses",
                    tint = TT.Teal,
                )
                Spacer(Modifier.height(6.dp))
            }
            Gain(
                value = "~${plan.estimatedPieces}",
                // "Estimated" twice over: the model's guess at what is there,
                // not a promise about what anyone will find.
                label = "pieces estimated on these blocks",
                tint = TT.InkSecondary,
            )

            Spacer(Modifier.height(18.dp))
            TrashTalkButton(
                text = "Start this walk",
                onClick = {
                    motion.mediumTick()
                    onStart(plan)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) TT.Teal.copy(alpha = 0.18f) else TT.Surface2,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) TT.Teal else TT.InkSecondary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = TT.Ink,
            fontWeight = FontWeight.Bold,
        )
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = TT.InkMuted)
    }
}

@Composable
private fun Gain(value: String, label: String, tint: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = tint,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(0.dp))
        Text(
            text = "  $label",
            style = MaterialTheme.typography.bodySmall,
            color = TT.InkSecondary,
        )
    }
}
