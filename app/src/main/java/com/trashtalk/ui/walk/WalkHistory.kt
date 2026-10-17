package com.trashtalk.ui.walk

import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trashtalk.data.walk.WalkRepository
import com.trashtalk.data.walk.WalkRow
import com.trashtalk.data.walk.WalkStatus
import com.trashtalk.ui.theme.TT
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Recorded walks and where each one stands.
 *
 * Status is the whole point. A walk finished in a dead spot is saved but not
 * sent, and without somewhere to see that, "saved" is a claim the user has no
 * way to check. A rejected walk needs somewhere to show its reason, in words
 * rather than an error code.
 */
@Composable
fun WalkHistory(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val walks = remember(context) { WalkRepository(context) }
    val rows by walks.recentWalks.collectAsStateWithLifecycle(initialValue = emptyList())

    // Only finished walks: the one being recorded has its own screen.
    val finished = rows.filter { it.status != WalkStatus.RECORDING }
    if (finished.isEmpty()) return

    Column(modifier) {
        finished.take(8).forEach { walk ->
            WalkRowItem(walk)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun WalkRowItem(walk: WalkRow) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 5.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(walk.status.tint()),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = walk.headline(),
                style = MaterialTheme.typography.bodyMedium,
                color = TT.Ink,
            )
            Text(
                text = walk.detail(),
                style = MaterialTheme.typography.labelSmall,
                color = if (walk.status == WalkStatus.REJECTED) TT.Magenta else TT.InkMuted,
            )
        }
        walk.awardedPoints?.takeIf { it > 0 }?.let {
            Text(
                text = "+$it",
                style = MaterialTheme.typography.bodyMedium,
                color = TT.Teal,
            )
        }
    }
}

private fun WalkStatus.tint(): Color = when (this) {
    WalkStatus.SCORED -> TT.Teal
    WalkStatus.REJECTED -> TT.Magenta
    WalkStatus.SYNCING -> TT.InkSecondary
    else -> TT.InkMuted
}

private val DAY = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

private fun WalkRow.headline(): String {
    val km = "%.2f km".format(distanceMeters / 1000)
    return "${DAY.format(Date(startedAt))} - $km"
}

/**
 * What state this walk is in, in words.
 *
 * "Waiting for signal" rather than "PENDING": the user did not choose a status
 * enum, they finished a walk somewhere with no bars.
 */
private fun WalkRow.detail(): String = when (status) {
    WalkStatus.PENDING -> "Saved - waiting for signal"
    WalkStatus.SYNCING -> "Sending..."
    WalkStatus.SCORED -> "Scored"
    WalkStatus.REJECTED -> rejectionReason ?: "Could not be scored"
    WalkStatus.RECORDING -> "Recording"
}
