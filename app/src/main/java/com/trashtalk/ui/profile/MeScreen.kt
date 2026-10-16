package com.trashtalk.ui.profile

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trashtalk.data.AdvocacyAction
import com.trashtalk.data.content.LevelContent
import com.trashtalk.data.model.BadgeCatalog
import com.trashtalk.data.model.LitterType
import com.trashtalk.data.model.TrashPath
import com.trashtalk.ui.MainViewModel
import com.trashtalk.ui.components.Chip
import com.trashtalk.ui.components.DataRow
import com.trashtalk.ui.components.EmptyState
import com.trashtalk.ui.components.Eyebrow
import com.trashtalk.ui.components.Hairline
import com.trashtalk.ui.components.HeroFigure
import com.trashtalk.ui.components.LegendGrid
import com.trashtalk.ui.components.Panel
import com.trashtalk.ui.components.ProgressTrack
import com.trashtalk.ui.components.SectionHeader
import com.trashtalk.ui.components.ShareSlice
import com.trashtalk.ui.components.StackedShareBar
import com.trashtalk.ui.components.StatTile
import com.trashtalk.ui.components.TrashTalkButton
import com.trashtalk.ui.components.TrashTalkOutlineButton
import com.trashtalk.ui.theme.TT
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * The player's own record -- and the statshot they are meant to want to share.
 *
 * The figures chosen here are the ones that make an argument when posted: mass
 * removed, the single-use plastic share of what they personally pulled off the
 * street, coverage contributed. Points are included but deliberately not the
 * headline, because points persuade nobody outside the app.
 */
@Composable
fun MeScreen(
    vm: MainViewModel,
    onOpenReport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    val mine = vm.myCollections()
    val backend = vm.backendMode

    var editingName by remember { mutableStateOf(false) }
    var nameDraft by remember(profile.displayName) { mutableStateOf(profile.displayName) }
    var showReset by remember { mutableStateOf(false) }

    val grams = mine.sumOf { it.grams }
    val myCounts = remember(mine) {
        val acc = mutableMapOf<LitterType, Int>()
        mine.forEach { c -> c.counts.forEach { (t, n) -> acc[t] = (acc[t] ?: 0) + n } }
        acc.toMap()
    }
    val myPieces = myCounts.values.sum()
    val singleUse = myCounts.entries.filter { it.key.isSingleUsePlastic }.sumOf { it.value }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(TT.Void),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {

        // ------------------------------------------------------------ identity
        item {
            Column {
                SectionHeader(eyebrow = "Your record", title = profile.title)
                Spacer(Modifier.height(12.dp))
                Panel {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Eyebrow("Display name")
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = if (editingName) "Save" else "Edit",
                                style = MaterialTheme.typography.labelMedium,
                                color = TT.Teal,
                                modifier = Modifier.clickable {
                                    if (editingName) vm.setDisplayName(nameDraft)
                                    editingName = !editingName
                                },
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        if (editingName) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(TT.Surface2)
                                    .padding(12.dp),
                            ) {
                                BasicTextField(
                                    value = nameDraft,
                                    onValueChange = { nameDraft = it },
                                    textStyle = TextStyle(color = TT.Ink, fontSize = 16.sp),
                                    cursorBrush = SolidColor(TT.Teal),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        } else {
                            Text(
                                text = profile.displayName.ifBlank { "You" },
                                style = MaterialTheme.typography.titleMedium,
                                color = TT.Ink,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Chip(
                                text = profile.teamName ?: "No crew",
                                tint = if (profile.teamName != null) TT.Teal else TT.InkMuted,
                                filled = profile.teamName != null,
                            )
                            if (profile.referralCode.isNotEmpty()) {
                                Chip(text = profile.referralCode, tint = TT.InkSecondary)
                            }
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------------ statshot
        item {
            Panel(accent = TT.Teal) {
                Column {
                    Eyebrow("Statshot")
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        HeroFigure(
                            value = if (grams >= 1000) {
                                "%.1f kg".format(grams / 1000)
                            } else {
                                "${grams.roundToInt()} g"
                            },
                            label = "removed from the street",
                            sublabel = "$myPieces pieces over ${mine.size} collections",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        StatTile(
                            value = "${profile.totalPoints}",
                            label = "points",
                            modifier = Modifier.weight(1f),
                        )
                        StatTile(
                            value = "${profile.streakDays}",
                            label = "day streak",
                            accent = if (profile.streakDays > 0) TT.LitterRampOrdinal[2] else TT.Ink,
                            modifier = Modifier.weight(1f),
                        )
                        StatTile(
                            value = "${profile.totalTiers}",
                            label = "badge tiers",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    if (myPieces > 0) {
                        DataRow(
                            label = "Single-use plastic you removed",
                            value = "${(singleUse * 100.0 / myPieces).roundToInt()}%",
                            caption = "$singleUse of $myPieces pieces",
                            valueColor = TT.Serious,
                        )
                        DataRow(
                            label = "Segments you have surveyed",
                            value = "${profile.stats.segmentsTouched.size}",
                            caption = "${profile.stats.segmentsFirstSurveyed} of them for the first time",
                        )
                        DataRow(
                            label = "Best hour",
                            value = "${profile.stats.personalBestHour} pieces",
                        )
                        DataRow(
                            label = "Volunteer time",
                            value = "${profile.stats.minutes / 60}h ${profile.stats.minutes % 60}m",
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        TrashTalkButton(
                            text = "Share statshot",
                            onClick = { vm.recordAdvocacy(AdvocacyAction.SHARE_STATSHOT) },
                        )
                        TrashTalkOutlineButton(
                            text = "Open the LIA",
                            onClick = onOpenReport,
                        )
                    }
                }
            }
        }

        // --------------------------------------------------- what you collected
        if (myCounts.isNotEmpty()) {
            item {
                Panel {
                    Column {
                        Eyebrow("What you have collected")
                        Spacer(Modifier.height(14.dp))
                        val top = myCounts.entries.sortedByDescending { it.value }
                        val head = top.take(7)
                        val tailCount = top.drop(7).sumOf { it.value }
                        val slices = buildList {
                            head.forEach { (type, n) ->
                                add(
                                    ShareSlice(
                                        label = type.label,
                                        share = n.toFloat() / myPieces,
                                        detail = "$n pieces",
                                    ),
                                )
                            }
                            if (tailCount > 0) {
                                add(
                                    ShareSlice(
                                        label = "Other",
                                        share = tailCount.toFloat() / myPieces,
                                        detail = "$tailCount pieces",
                                    ),
                                )
                            }
                        }
                        StackedShareBar(slices = slices)
                    }
                }
            }
        }

        // ------------------------------------------------------------ progress
        item {
            Panel {
                Column {
                    Eyebrow("Progress")
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Narrative",
                        style = MaterialTheme.typography.titleSmall,
                        color = TT.Ink,
                    )
                    Spacer(Modifier.height(7.dp))
                    ProgressTrack(
                        fraction = profile.completedLevels.size / LevelContent.count.toFloat(),
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = "${profile.completedLevels.size} of ${LevelContent.count} levels",
                        style = MaterialTheme.typography.labelSmall,
                        color = TT.InkMuted,
                    )

                    Spacer(Modifier.height(16.dp))
                    Hairline()
                    Spacer(Modifier.height(14.dp))

                    Text(
                        text = "Paths",
                        style = MaterialTheme.typography.titleSmall,
                        color = TT.Ink,
                    )
                    Spacer(Modifier.height(9.dp))
                    TrashPath.entries.forEach { path ->
                        val tiers = profile.tiersIn(path)
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (profile.path == path) TT.Teal else TT.Surface3,
                                    ),
                            )
                            Spacer(Modifier.width(9.dp))
                            Text(
                                text = path.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (profile.path == path) TT.Ink else TT.InkSecondary,
                                modifier = Modifier.width(100.dp),
                            )
                            ProgressTrack(
                                fraction = (tiers % BadgeCatalog.TIERS_PER_PATH_LEVEL) /
                                    BadgeCatalog.TIERS_PER_PATH_LEVEL.toFloat(),
                                tint = if (profile.path == path) TT.Teal else TT.InkMuted,
                                thickness = 5.dp,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "L${profile.pathLevel(path)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = TT.InkMuted,
                            )
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------- recent history
        item {
            Panel {
                Column {
                    Eyebrow("Your recent pickups")
                    Spacer(Modifier.height(10.dp))
                    if (mine.isEmpty()) {
                        EmptyState(
                            title = "Nothing logged yet",
                            body = "Your first survey is worth a coverage bonus. " +
                                "Open the map and pick a dashed segment.",
                        )
                    } else {
                        mine.take(8).forEach { c ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = c.segmentName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TT.Ink,
                                    )
                                    Text(
                                        text = "${c.pieces} pieces - ${relativeTime(c.createdAt)}" +
                                            if (c.wasFirstSurvey) " - first survey" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TT.InkMuted,
                                    )
                                }
                                Text(
                                    text = "+${c.points}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = TT.Teal,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------------- backend
        item {
            Panel {
                Column {
                    Eyebrow("Data source")
                    Spacer(Modifier.height(7.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (backend == com.trashtalk.data.model.BackendMode.LIVE) {
                                        TT.Good
                                    } else {
                                        TT.Warning
                                    },
                                ),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = backend.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = TT.Ink,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = backend.blurb,
                        style = MaterialTheme.typography.bodySmall,
                        color = TT.InkMuted,
                    )
                    Spacer(Modifier.height(12.dp))
                    DataRow(label = "Neighbourhood", value = vm.network.neighborhood)
                    DataRow(label = "Segments in network", value = "${vm.network.segments.size}")
                    DataRow(label = "Litter generators mapped", value = "${vm.network.pois.size}")

                    if (backend == com.trashtalk.data.model.BackendMode.DEMO) {
                        Spacer(Modifier.height(14.dp))
                        if (!showReset) {
                            TrashTalkOutlineButton(
                                text = "Reset my progress",
                                onClick = { showReset = true },
                                tint = TT.Critical,
                            )
                        } else {
                            Text(
                                text = "This clears your profile, pickups, flags and badges on " +
                                    "this device. The seeded neighbourhood stays.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TT.Critical,
                            )
                            Spacer(Modifier.height(9.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                TrashTalkButton(
                                    text = "Yes, reset",
                                    onClick = {
                                        vm.resetProgress()
                                        showReset = false
                                    },
                                    tone = TT.Critical,
                                )
                                TrashTalkOutlineButton(
                                    text = "Cancel",
                                    onClick = { showReset = false },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun relativeTime(millis: Long): String {
    val delta = System.currentTimeMillis() - millis
    val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
    val hours = TimeUnit.MILLISECONDS.toHours(delta)
    val days = TimeUnit.MILLISECONDS.toDays(delta)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> "${days / 7}w ago"
    }
}
