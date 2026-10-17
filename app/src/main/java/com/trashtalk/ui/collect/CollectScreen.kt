package com.trashtalk.ui.collect

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.trashtalk.core.LatLng
import com.trashtalk.core.rememberDeviceLocation
import com.trashtalk.core.distanceToPolylineMeters
import com.trashtalk.core.formatDistance
import com.trashtalk.data.model.LitterType
import com.trashtalk.data.model.Segment
import com.trashtalk.domain.CollectionDraft
import com.trashtalk.domain.ScoreEngine
import com.trashtalk.ui.MainViewModel
import com.trashtalk.ui.components.Chip
import com.trashtalk.ui.components.DataRow
import com.trashtalk.ui.components.Eyebrow
import com.trashtalk.ui.components.Hairline
import com.trashtalk.ui.components.Panel
import com.trashtalk.ui.components.SectionHeader
import com.trashtalk.ui.components.StatusChip
import com.trashtalk.ui.components.TrashTalkButton
import com.trashtalk.ui.components.TrashTalkOutlineButton
import com.trashtalk.ui.theme.HazardBand
import com.trashtalk.ui.theme.TT
import com.trashtalk.ui.theme.rememberMotion
import java.io.File
import kotlin.math.roundToInt

/**
 * Logging a pickup -- the one screen that has to work while standing on a kerb,
 * one-handed, in sunlight, possibly with gloves on.
 *
 * Every decision here follows from that. The six litter types that dominate real
 * street counts are at the top with 44 dp tap targets; location is resolved
 * automatically and the nearest segment pre-selected, with a manual override for
 * when GPS disagrees with reality; the points preview updates as you count, so
 * the incentive is visible at the moment of the decision rather than afterwards;
 * and the photo is optional, because requiring one would quietly halve the number
 * of surveys that ever get logged.
 */
@Composable
fun CollectScreen(
    vm: MainViewModel,
    initialSegmentId: String?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val motion = rememberMotion()
    val segments by vm.segments.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()

    var counts by remember { mutableStateOf(mapOf<LitterType, Int>()) }
    var notes by remember { mutableStateOf("") }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    var pendingPhoto by remember { mutableStateOf<Uri?>(null) }
    var manualSegmentId by remember { mutableStateOf(initialSegmentId) }
    var showAllTypes by remember { mutableStateOf(false) }
    var showSegmentPicker by remember { mutableStateOf(false) }

    // Session timer. Minutes are what make a pieces-per-hour record meaningful,
    // so the timer is offered rather than asking people to estimate afterwards.
    var timerStart by remember { mutableLongStateOf(0L) }
    var elapsedMinutes by remember { mutableIntStateOf(0) }
    val timerRunning = timerStart > 0L

    val locationState = rememberDeviceLocation()
    val location = locationState.fix?.location

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        if (success) photoUri = pendingPhoto
        pendingPhoto = null
    }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val uri = createPhotoUri(context)
            pendingPhoto = uri
            cameraLauncher.launch(uri)
        }
    }

    // Keep the elapsed figure fresh while the timer runs.
    LaunchedEffect(timerRunning) {
        while (timerRunning) {
            elapsedMinutes = ((SystemClock.elapsedRealtime() - timerStart) / 60_000L)
                .toInt().coerceAtLeast(0)
            kotlinx.coroutines.delay(1_000)
        }
    }

    val nearest = remember(location, segments) {
        location?.let { loc ->
            segments.minByOrNull { distanceToPolylineMeters(loc, it.path) }
        }
    }
    val segment = remember(manualSegmentId, nearest, segments) {
        manualSegmentId?.let { id -> segments.firstOrNull { it.id == id } } ?: nearest
    }
    val distanceToSegment = remember(location, segment) {
        if (location != null && segment != null) {
            distanceToPolylineMeters(location, segment.path)
        } else null
    }

    val pieces = counts.values.sum()
    val draft = CollectionDraft(
        segmentId = segment?.id ?: "",
        segmentName = segment?.name ?: "",
        lat = location?.lat ?: segment?.midpoint?.lat ?: 0.0,
        lng = location?.lng ?: segment?.midpoint?.lng ?: 0.0,
        counts = counts,
        minutes = elapsedMinutes,
        notes = notes,
        photoUri = photoUri?.toString(),
    )
    val breakdown = remember(counts, segment, profile, elapsedMinutes) {
        ScoreEngine.score(draft, segment, profile)
    }

    Column(
        modifier
            .fillMaxSize()
            .background(TT.Void)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 28.dp),
    ) {
        SectionHeader(eyebrow = "Survey", title = "Log a pickup")

        Spacer(Modifier.height(14.dp))

        // ------------------------------------------------------------- where
        Panel(accent = if (segment == null) TT.Warning else null) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("Segment")
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "Change",
                        style = MaterialTheme.typography.labelMedium,
                        color = TT.Teal,
                        modifier = Modifier.clickable { showSegmentPicker = !showSegmentPicker },
                    )
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    text = segment?.name ?: "Waiting for a location fix",
                    style = MaterialTheme.typography.titleMedium,
                    color = TT.Ink,
                )
                Spacer(Modifier.height(5.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (segment != null) {
                        val band = HazardBand.of(segment.litterIndex)
                        StatusChip(label = band.label, tint = band.color)
                    }
                    if (distanceToSegment != null) {
                        Chip(
                            text = if (distanceToSegment < 25) {
                                "On this segment"
                            } else {
                                "${formatDistance(distanceToSegment)} away"
                            },
                            tint = if (distanceToSegment < 60) TT.InkSecondary else TT.Warning,
                        )
                    } else {
                        Chip(text = "No GPS fix", tint = TT.Warning)
                    }
                    if (segment != null && !segment.hasBeenSurveyed) {
                        Chip(text = "First survey +50", tint = TT.Teal, filled = true)
                    }
                }

                if (showSegmentPicker) {
                    Spacer(Modifier.height(12.dp))
                    Hairline()
                    Spacer(Modifier.height(8.dp))
                    Eyebrow("Nearby segments")
                    Spacer(Modifier.height(6.dp))
                    val candidates = remember(location, segments) {
                        val origin = location ?: segment?.midpoint
                        if (origin == null) segments.take(12)
                        else segments.sortedBy { distanceToPolylineMeters(origin, it.path) }.take(12)
                    }
                    candidates.forEach { candidate ->
                        val d = location?.let { distanceToPolylineMeters(it, candidate.path) }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    manualSegmentId = candidate.id
                                    showSegmentPicker = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(TT.litterColor(candidate.litterIndex)),
                            )
                            Spacer(Modifier.width(9.dp))
                            Text(
                                text = candidate.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (candidate.id == segment?.id) TT.Teal else TT.InkSecondary,
                                modifier = Modifier.weight(1f),
                            )
                            if (d != null) {
                                Text(
                                    text = formatDistance(d),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TT.InkMuted,
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // -------------------------------------------------------------- what
        Panel {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("What did you pick up")
                    Spacer(Modifier.weight(1f))
                    if (pieces > 0) {
                        Text(
                            text = "$pieces pieces",
                            style = MaterialTheme.typography.titleSmall,
                            color = TT.Ink,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))

                val shown = if (showAllTypes) {
                    LitterType.surveyOrder
                } else {
                    LitterType.surveyOrder.take(6)
                }
                shown.forEach { type ->
                    CounterRow(
                        type = type,
                        count = counts[type] ?: 0,
                        onChange = { next ->
                            counts = if (next <= 0) counts - type else counts + (type to next)
                        },
                    )
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (showAllTypes) "Show fewer types" else "Show all 10 types",
                    style = MaterialTheme.typography.labelMedium,
                    color = TT.Teal,
                    modifier = Modifier.clickable { showAllTypes = !showAllTypes },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ------------------------------------------------------- how long
        Panel {
            Column {
                Eyebrow("Session")
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (timerRunning || elapsedMinutes > 0) {
                                "$elapsedMinutes min"
                            } else {
                                "Not timed"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = TT.Ink,
                        )
                        Text(
                            text = "Timing a session is what makes an hourly record count",
                            style = MaterialTheme.typography.labelSmall,
                            color = TT.InkMuted,
                        )
                    }
                    TrashTalkOutlineButton(
                        text = if (timerRunning) "Stop" else "Start timer",
                        onClick = {
                            if (timerRunning) {
                                elapsedMinutes = ((SystemClock.elapsedRealtime() - timerStart) / 60_000L)
                                    .toInt().coerceAtLeast(1)
                                timerStart = 0L
                            } else {
                                timerStart = SystemClock.elapsedRealtime()
                            }
                        },
                        tint = if (timerRunning) TT.Critical else TT.Teal,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // --------------------------------------------------------- evidence
        Panel {
            Column {
                Eyebrow("Evidence (optional)")
                Spacer(Modifier.height(8.dp))
                if (photoUri != null) {
                    AsyncImage(
                        model = photoUri,
                        contentDescription = "Photo of the litter collected",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(170.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.height(9.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    TrashTalkOutlineButton(
                        text = if (photoUri == null) "Add photo" else "Retake",
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CAMERA,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                val uri = createPhotoUri(context)
                                pendingPhoto = uri
                                cameraLauncher.launch(uri)
                            } else {
                                cameraPermission.launch(Manifest.permission.CAMERA)
                            }
                        },
                    )
                    if (photoUri != null) {
                        TrashTalkOutlineButton(
                            text = "Remove",
                            onClick = { photoUri = null },
                            tint = TT.InkMuted,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Eyebrow("Notes")
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(TT.Surface2)
                        .padding(12.dp),
                ) {
                    if (notes.isEmpty()) {
                        Text(
                            text = "What did you notice? Overflowing bin, wind trap, dumping...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TT.InkMuted,
                        )
                    }
                    BasicTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        textStyle = TextStyle(
                            color = TT.Ink,
                            fontSize = 14.sp,
                        ),
                        cursorBrush = SolidColor(TT.Teal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ----------------------------------------------------------- reward
        if (pieces > 0) {
            Panel(accent = TT.Teal) {
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            Eyebrow("You will earn")
                            Text(
                                text = "${breakdown.total} pts",
                                style = MaterialTheme.typography.headlineMedium,
                                color = TT.Teal,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "%.0f g".format(
                                    counts.entries.sumOf { (t, n) -> t.avgGrams * n },
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                color = TT.Ink,
                            )
                            Text(
                                text = "estimated mass",
                                style = MaterialTheme.typography.labelSmall,
                                color = TT.InkMuted,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Hairline()
                    Spacer(Modifier.height(4.dp))
                    // The breakdown is shown in full: a score the player cannot
                    // explain is a score they will not trust.
                    breakdown.lines.forEach { (label, value) ->
                        DataRow(label = label, value = "+$value")
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        TrashTalkButton(
            text = if (pieces == 0) "Count something first" else "Save this pickup",
            onClick = {
                // The payoff for the whole form; the celebration follows.
                motion.heavyBurst()
                if (timerRunning) {
                    elapsedMinutes = ((SystemClock.elapsedRealtime() - timerStart) / 60_000L)
                        .toInt().coerceAtLeast(1)
                    timerStart = 0L
                }
                vm.submitCollection(draft.copy(minutes = elapsedMinutes)) { onDone() }
            },
            enabled = pieces > 0 && segment != null,
            modifier = Modifier.fillMaxWidth(),
        )

        if (segment == null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Pick a segment to continue. Grant location access for this to " +
                    "happen automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = TT.Warning,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** One litter type with a stepper. Large targets; harm note doubles as teaching. */
@Composable
private fun CounterRow(
    type: LitterType,
    count: Int,
    onChange: (Int) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = type.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (count > 0) TT.Ink else TT.InkSecondary,
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = "${type.points} pts",
                    style = MaterialTheme.typography.labelSmall,
                    color = TT.InkMuted,
                )
            }
            Text(
                text = type.note,
                style = MaterialTheme.typography.labelSmall,
                color = TT.InkMuted,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(10.dp))
        StepperButton(symbol = "-", enabled = count > 0) { onChange(count - 1) }
        Box(
            Modifier.width(38.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$count",
                style = MaterialTheme.typography.titleMedium,
                color = if (count > 0) TT.Teal else TT.InkMuted,
                fontWeight = FontWeight.Bold,
            )
        }
        StepperButton(symbol = "+", enabled = true) { onChange(count + 1) }
    }
}

@Composable
private fun StepperButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    val motion = rememberMotion()
    Surface(
        color = if (enabled) TT.Surface3 else TT.Surface2,
        shape = CircleShape,
        modifier = Modifier
            .size(40.dp)
            .clickable(enabled = enabled) {
                motion.selectionTick()
                onClick()
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = symbol,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) TT.Ink else TT.InkMuted,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** A content:// URI in the cache directory, shared with the camera app. */
private fun createPhotoUri(context: Context): Uri {
    val dir = File(context.cacheDir, "litter_photos").apply { mkdirs() }
    val file = File(dir, "litter_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
}
