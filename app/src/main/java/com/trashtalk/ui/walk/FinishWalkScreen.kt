package com.trashtalk.ui.walk

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.trashtalk.data.model.LitterType
import com.trashtalk.data.walk.SegmentTally
import com.trashtalk.data.walk.WalkSummary
import com.trashtalk.ui.components.Hairline
import com.trashtalk.ui.components.TrashTalkButton
import com.trashtalk.ui.components.TrashTalkOutlineButton
import com.trashtalk.ui.theme.TT
import com.trashtalk.ui.theme.rememberMotion

/**
 * The end of a walk: what was found, where, and whether to submit it.
 *
 * This is the last moment before an hour of somebody's effort becomes data, so
 * it shows the data rather than a total. Per street, per material, including
 * the pieces that could not be placed -- because a summary that quietly drops
 * what it could not attribute is a summary that lies by omission.
 */
@Composable
fun FinishWalkScreen(
    summary: WalkSummary,
    submitting: Boolean,
    onSubmit: (haulPhotoUri: String?) -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val motion = rememberMotion()
    var haulPhoto by remember { mutableStateOf<Uri?>(null) }
    var pendingPhoto by remember { mutableStateOf<Uri?>(null) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) haulPhoto = pendingPhoto
        pendingPhoto = null
    }

    // CAMERA has to be asked for before the capture intent is launched, and
    // this screen used to launch straight into it. Because the manifest
    // declares the permission, Android enforces it even on an intent handled
    // by somebody else's camera app, so a revoked permission did not fail
    // softly -- it threw SecurityException and killed the process at the end
    // of a walk, with the haul unsubmitted. CollectScreen already gated it
    // this way; this is the same gate, not a new idea.
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val uri = newHaulPhotoUri(context)
            pendingPhoto = uri
            camera.launch(uri)
        }
    }

    fun takeHaulPhoto() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            val uri = newHaulPhotoUri(context)
            pendingPhoto = uri
            camera.launch(uri)
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(TT.Void),
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    text = "WALK COMPLETE",
                    style = MaterialTheme.typography.labelSmall,
                    color = TT.Teal,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Headline("${summary.pieces}", if (summary.pieces == 1) "piece" else "pieces")
                    Headline("${summary.tallies.size}", "streets")
                    Headline(
                        "%.2f".format(summary.walk.distanceMeters / 1000),
                        "km",
                    )
                    val minutes = summary.durationMillis / 60_000
                    Headline(
                        formatDuration(summary.durationMillis),
                        if (minutes >= 60) "hours" else "min",
                    )
                }
                Spacer(Modifier.height(22.dp))
            }

            if (summary.tallies.isNotEmpty()) {
                item {
                    Text(
                        text = "What you took off each street",
                        style = MaterialTheme.typography.titleSmall,
                        color = TT.Ink,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                items(summary.tallies, key = { it.segmentId }) { tally ->
                    TallyCard(tally)
                }
            }

            if (summary.unattributed.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(10.dp))
                    UnattributedCard(summary.unattributed)
                }
            }

            item {
                Spacer(Modifier.height(18.dp))
                HaulPhotoPrompt(
                    photo = haulPhoto,
                    onTake = {
                        motion.lightTick()
                        takeHaulPhoto()
                    },
                    onClear = { haulPhoto = null },
                )
            }
        }

        Hairline()
        Column(Modifier.padding(20.dp)) {
            TrashTalkButton(
                text = when {
                    submitting -> "Submitting..."
                    !summary.hasAnything -> "Nothing to submit"
                    else -> "Submit this walk"
                },
                enabled = summary.hasAnything && !submitting,
                onClick = { onSubmit(haulPhoto?.toString()) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            TrashTalkOutlineButton(
                text = "Discard",
                enabled = !submitting,
                onClick = onDiscard,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TallyCard(tally: SegmentTally) {
    Surface(color = TT.Surface2, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = tally.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = TT.Ink,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${tally.pieces}",
                    style = MaterialTheme.typography.titleMedium,
                    color = TT.Teal,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (tally.minutes > 0) {
                Text(
                    text = "${tally.minutes} min working this block",
                    style = MaterialTheme.typography.labelSmall,
                    color = TT.InkMuted,
                )
            }
            Spacer(Modifier.height(8.dp))
            tally.counts.entries
                .sortedByDescending { it.value }
                .forEach { (type, n) ->
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text(
                            text = type.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = TT.InkSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "$n",
                            style = MaterialTheme.typography.bodySmall,
                            color = TT.InkSecondary,
                        )
                    }
                }
        }
    }
}

/**
 * Pieces that could not be placed on a street.
 *
 * Kept visible, and kept out of the per-street data. Saying so is the point:
 * the alternative is attributing them to whichever street happened to be
 * nearest, which would put litter on blocks nobody walked.
 */
@Composable
private fun UnattributedCard(counts: Map<LitterType, Int>) {
    val total = counts.values.sum()
    Surface(
        color = TT.Surface2,
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = "$total not matched to a street",
                style = MaterialTheme.typography.titleSmall,
                color = TT.Ink,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Too far from the network to attribute — a park, a lot, or a " +
                    "drifting fix. These count toward your total but not toward any block.",
                style = MaterialTheme.typography.labelSmall,
                color = TT.InkMuted,
            )
        }
    }
}

@Composable
private fun HaulPhotoPrompt(photo: Uri?, onTake: () -> Unit, onClear: () -> Unit) {
    Surface(color = TT.Surface2, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = "Photo of the haul",
                style = MaterialTheme.typography.titleSmall,
                color = TT.Ink,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Optional, and it is what lets a cleanup count as verified. " +
                    "Point at the litter, not at people.",
                style = MaterialTheme.typography.labelSmall,
                color = TT.InkMuted,
            )
            Spacer(Modifier.height(12.dp))

            if (photo == null) {
                TrashTalkOutlineButton(text = "Take a photo", onClick = onTake)
            } else {
                Box {
                    AsyncImage(
                        model = photo,
                        contentDescription = "The haul from this walk",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(170.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                    Surface(
                        color = TT.Void.copy(alpha = 0.72f),
                        shape = CircleShape,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(32.dp)
                            .clickable(onClick = onClear),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("×", color = TT.Ink, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Headline(value: String, label: String) {
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
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

/**
 * A content:// URI for the haul photo.
 *
 * Same cache directory and the same FileProvider the quick-log form uses, so
 * 4E.1's upload has one place to look rather than two.
 */
private fun newHaulPhotoUri(context: android.content.Context): Uri {
    val dir = java.io.File(context.cacheDir, "litter_photos").apply { mkdirs() }
    val file = java.io.File(dir, "haul_${System.currentTimeMillis()}.jpg")
    return androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
}

private fun formatDuration(millis: Long): String {
    val minutes = millis / 60_000
    return if (minutes >= 60) "%d:%02d".format(minutes / 60, minutes % 60) else "$minutes"
}
