package com.trashtalk.ui.walk

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trashtalk.data.model.LitterType
import com.trashtalk.data.walk.WalkRepository
import com.trashtalk.ui.components.TrashTalkButton
import com.trashtalk.ui.components.TrashTalkOutlineButton
import com.trashtalk.ui.theme.TT
import com.trashtalk.ui.theme.rememberMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf

/**
 * Walk mode.
 *
 * The screen you have open while actually walking, which means it is designed
 * for a glance at arm's length with one hand: large counters, large targets,
 * and nothing that needs reading. Most of a walk happens with this screen off
 * and the notification doing the work -- this is for when somebody looks.
 */
@Composable
fun WalkScreen(
    repository: WalkRepository,
    quickTypes: List<LitterType>,
    onLogPiece: (LitterType) -> Unit,
    onFinish: (walkId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val live by repository.live.collectAsStateWithLifecycle(initialValue = null)
    val motion = rememberMotion()
    val context = LocalContext.current

    // Android 13 denies notifications until they are asked for, and the walk
    // notification is not decoration: it is how somebody sees the recording is
    // running and how they log a piece without unlocking the phone. Starting a
    // walk without it would be a recording the user cannot see or reach.
    val notifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        // Granted or not, the walk starts: the recording is the point and the
        // notification is how it is surfaced, not a precondition for it.
        repository.start()
    }

    fun beginWalk() {
        motion.mediumTick()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            repository.start()
        }
    }

    val state = live
    if (state == null) {
        StartPrompt(onStart = ::beginWalk, modifier = modifier)
        return
    }

    // Elapsed time ticks here rather than coming from the database: the walk
    // row only changes when something is recorded, and a clock that freezes
    // whenever you stop walking reads as a crash.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.walk.id) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val elapsed = now - state.walk.startedAt

    Column(
        modifier
            .fillMaxSize()
            .background(TT.Void)
            .padding(20.dp),
    ) {
        Text(
            text = "RECORDING",
            style = MaterialTheme.typography.labelSmall,
            color = TT.Teal,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
            BigStat(value = formatElapsed(elapsed), label = "elapsed")
            BigStat(value = "${state.pieces}", label = if (state.pieces == 1) "piece" else "pieces")
            BigStat(
                value = "${state.coveredSegmentIds.size}",
                label = if (state.coveredSegmentIds.size == 1) "segment" else "segments",
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = "%.2f km walked".format(state.walk.distanceMeters / 1000),
            style = MaterialTheme.typography.bodyMedium,
            color = TT.InkSecondary,
        )

        Spacer(Modifier.height(26.dp))
        Text(
            text = "Log what you pick up",
            style = MaterialTheme.typography.titleSmall,
            color = TT.Ink,
        )
        Spacer(Modifier.height(10.dp))

        // One tap per piece. Counting is the whole interaction, so it gets the
        // whole screen rather than a form.
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(quickTypes, key = { it.name }) { type ->
                LogRow(
                    type = type,
                    count = state.byType[type] ?: 0,
                    onTap = {
                        motion.selectionTick()
                        onLogPiece(type)
                    },
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        TrashTalkButton(
            text = "Finish walk",
            onClick = {
                motion.heavyBurst()
                // Capture the id before stopping: the flow drops to null the
                // moment the service marks the walk finished, and the summary
                // screen needs to know which walk to summarise.
                val finished = state.walk.id
                repository.finish()
                onFinish(finished)
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StartPrompt(onStart: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .background(TT.Void)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Record a cleanup",
            style = MaterialTheme.typography.headlineSmall,
            color = TT.Ink,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Your walk is tracked with the screen off. Log pieces from " +
                "the notification as you go, or from here.",
            style = MaterialTheme.typography.bodyMedium,
            color = TT.InkSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        TrashTalkButton(text = "Start walking", onClick = onStart)
    }
}

@Composable
private fun LogRow(type: LitterType, count: Int, onTap: () -> Unit) {
    Surface(
        color = TT.Surface2,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            // A whole-row target: at arm's length, walking, a 48dp button is
            // optimistic.
            .height(64.dp)
            .clickable(onClick = onTap),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = type.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = TT.Ink,
                )
                Text(
                    text = type.note,
                    style = MaterialTheme.typography.labelSmall,
                    color = TT.InkMuted,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (count > 0) TT.Teal.copy(alpha = 0.18f) else TT.Surface3),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (count > 0) TT.Teal else TT.InkMuted,
                )
            }
        }
    }
}

@Composable
private fun BigStat(value: String, label: String) {
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = TT.Ink,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TT.InkMuted,
        )
    }
}

private fun formatElapsed(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes >= 60) {
        "%d:%02d:%02d".format(minutes / 60, minutes % 60, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
