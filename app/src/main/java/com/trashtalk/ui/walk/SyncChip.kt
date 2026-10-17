package com.trashtalk.ui.walk

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trashtalk.data.walk.WalkRepository
import com.trashtalk.data.walk.WalkStatus
import com.trashtalk.ui.theme.TT
import kotlinx.coroutines.flow.map

/**
 * How many cleanups are still waiting to reach the server.
 *
 * Present only when there is something to say. A queue the user cannot see is
 * indistinguishable from losing their work -- they finished a walk in a dead
 * spot, the app said it was saved, and then nothing visible happened for an
 * hour. This is the thing that makes "saved" believable.
 */
@Composable
fun SyncChip(modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    val context = LocalContext.current
    val walks = remember(context) { WalkRepository(context) }

    val state by remember(walks) {
        walks.recentWalks.map { rows ->
            SyncState(
                pending = rows.count { it.status == WalkStatus.PENDING },
                syncing = rows.count { it.status == WalkStatus.SYNCING },
                rejected = rows.count { it.status == WalkStatus.REJECTED },
            )
        }
    }.collectAsStateWithLifecycle(initialValue = SyncState())

    AnimatedVisibility(
        visible = state.hasAnything,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            color = TT.Surface1.copy(alpha = 0.94f),
            shape = CircleShape,
            modifier = Modifier.clickable(onClick = onClick),
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (state.rejected > 0) TT.Magenta else TT.Teal),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = state.label(),
                    style = MaterialTheme.typography.labelMedium,
                    color = TT.InkSecondary,
                )
            }
        }
    }
}

private data class SyncState(
    val pending: Int = 0,
    val syncing: Int = 0,
    val rejected: Int = 0,
) {
    val hasAnything: Boolean get() = pending > 0 || syncing > 0 || rejected > 0

    /**
     * What the chip says.
     *
     * A rejection outranks a queue: the user can do nothing about waiting, but
     * a refused cleanup is something they may want to look at.
     */
    fun label(): String = when {
        rejected > 0 -> if (rejected == 1) "1 cleanup rejected" else "$rejected cleanups rejected"
        syncing > 0 -> "Sending..."
        pending == 1 -> "1 cleanup waiting to send"
        else -> "$pending cleanups waiting to send"
    }
}
