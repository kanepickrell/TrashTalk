package com.trashtalk.ui.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.trashtalk.core.LatLng
import com.trashtalk.core.Mercator
import com.trashtalk.data.model.LitterType
import com.trashtalk.data.model.Segment
import com.trashtalk.ui.theme.HazardBand
import com.trashtalk.ui.theme.TT
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What a share card says.
 *
 * Deliberately not "I scored 240 points" -- points persuade nobody outside the
 * app. The figures here are the ones that make an argument to somebody who has
 * never heard of TrashTalk: a real street name, a count, what the material
 * actually was, and the shape of the block it came off.
 */
data class ShareCardData(
    val segmentName: String,
    val pieces: Int,
    val grams: Double,
    val topMaterial: String?,
    val topMaterialShare: Int,
    val coverageBonus: Boolean,
    val hazard: HazardBand,
    val path: List<LatLng>,
    val neighborhood: String,
) {
    companion object {
        /** Builds a card from a submitted pickup. */
        fun from(
            segment: Segment?,
            counts: Map<LitterType, Int>,
            wasFirstSurvey: Boolean,
            neighborhood: String,
        ): ShareCardData {
            val pieces = counts.values.sum()
            // "Top" material by harm, not by count: the point of the card is
            // what the pickup prevented, and twenty cigarette butts matter
            // differently from twenty sheets of newspaper.
            val top = counts.entries.maxByOrNull { (type, n) -> type.harm * n }
            return ShareCardData(
                segmentName = segment?.name ?: "Terrell Heights",
                pieces = pieces,
                grams = counts.entries.sumOf { (t, n) -> t.avgGrams * n },
                topMaterial = top?.key?.label,
                topMaterialShare = if (pieces > 0 && top != null) {
                    (top.value * 100.0 / pieces).roundToInt()
                } else 0,
                coverageBonus = wasFirstSurvey,
                hazard = HazardBand.of(segment?.litterIndex ?: 0f),
                path = segment?.path.orEmpty(),
                neighborhood = neighborhood,
            )
        }
    }
}

/**
 * The card itself.
 *
 * Rendered on screen and captured to a PNG, so it is laid out at a fixed aspect
 * rather than filling its parent -- a share image that reflows with the device
 * it was made on is not a share image.
 */
@Composable
fun ShareCard(data: ShareCardData, modifier: Modifier = Modifier) {
    Column(
        modifier
            .width(320.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(TT.Surface1)
            .padding(22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(TT.Teal),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "TRASHTALK",
                style = MaterialTheme.typography.labelSmall,
                color = TT.Teal,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = data.neighborhood,
                style = MaterialTheme.typography.labelSmall,
                color = TT.InkMuted,
            )
        }

        Spacer(Modifier.height(18.dp))

        Text(
            text = "${data.pieces}",
            style = MaterialTheme.typography.displaySmall,
            color = TT.Ink,
        )
        Text(
            text = if (data.pieces == 1) "piece off the street" else "pieces off the street",
            style = MaterialTheme.typography.bodyMedium,
            color = TT.InkSecondary,
        )

        Spacer(Modifier.height(16.dp))

        // The block itself, drawn from the real geometry.
        Box(
            Modifier
                .fillMaxWidth()
                .height(92.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(TT.Void),
        ) {
            if (data.path.size >= 2) {
                SegmentSparkMap(
                    path = data.path,
                    tint = data.hazard.color,
                    modifier = Modifier.fillMaxWidth().height(92.dp),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Text(
            text = data.segmentName,
            style = MaterialTheme.typography.titleSmall,
            color = TT.Ink,
            maxLines = 1,
        )

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CardStat(
                value = if (data.grams >= 1000) {
                    "%.1f kg".format(data.grams / 1000)
                } else {
                    "${data.grams.roundToInt()} g"
                },
                label = "removed",
            )
            data.topMaterial?.let {
                CardStat(value = "${data.topMaterialShare}%", label = it)
            }
        }

        if (data.coverageBonus) {
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(TT.Teal.copy(alpha = 0.16f))
                    .padding(horizontal = 11.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "First survey of this block",
                    style = MaterialTheme.typography.labelMedium,
                    color = TT.Teal,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = "Open litter data - trashtalk-app.vercel.app",
            style = MaterialTheme.typography.labelSmall,
            color = TT.InkMuted,
        )
    }
}

@Composable
private fun CardStat(value: String, label: String) {
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
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

/** The segment's own shape, fitted to the card. */
@Composable
private fun SegmentSparkMap(path: List<LatLng>, tint: Color, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val xs = path.map { Mercator.worldX(it.lng) }
        val ys = path.map { Mercator.worldY(it.lat) }
        val minX = xs.min()
        val maxX = xs.max()
        val minY = ys.min()
        val maxY = ys.max()
        val spanX = (maxX - minX).takeIf { it > 1e-12 } ?: 1e-12
        val spanY = (maxY - minY).takeIf { it > 1e-12 } ?: 1e-12

        val pad = 22f
        val scale = min(
            (size.width - pad * 2) / spanX,
            (size.height - pad * 2) / spanY,
        )
        val offsetX = (size.width - spanX * scale) / 2
        val offsetY = (size.height - spanY * scale) / 2

        val points = path.indices.map { i ->
            Offset(
                (offsetX + (xs[i] - minX) * scale).toFloat(),
                (offsetY + (ys[i] - minY) * scale).toFloat(),
            )
        }
        val line = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
        }
        // Same glow idiom as the map, so the card is recognisably from here.
        drawPath(line, tint.copy(alpha = 0.22f), style = Stroke(14f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(line, tint, style = Stroke(4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(Color.White, 4.5f, points.first())
        drawCircle(Color.White, 4.5f, points.last())
    }
}

/**
 * Writes a captured card to the share cache and hands back a shareable URI.
 *
 * Goes through the existing FileProvider: a `file://` URI would throw
 * FileUriExposedException on anything since Android 7.
 */
fun writeShareCard(context: Context, image: ImageBitmap): android.net.Uri {
    val dir = File(context.cacheDir, "share_cards").apply { mkdirs() }
    // One file per card, timestamped, so a second share does not race the
    // receiving app still reading the first.
    val file = File(dir, "trashtalk_${System.currentTimeMillis()}.png")
    FileOutputStream(file).use { out ->
        image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
    }
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

/** Fires the system share sheet for a card. */
fun shareCardIntent(context: Context, uri: android.net.Uri, caption: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, caption)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share your pickup"))
}
