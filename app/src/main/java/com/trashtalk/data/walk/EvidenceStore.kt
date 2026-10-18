package com.trashtalk.data.walk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.trashtalk.data.firebase.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private const val TAG = "Evidence"

/**
 * Gets evidence photos off the phone and into Storage.
 *
 * Until this existed, every photo a volunteer took stayed in the app's cache
 * and only its local `content://` URI was written to Firestore -- a path that
 * means nothing on any other device and that Android reclaims whenever it
 * wants the space. People were being asked to document their work, and the
 * documentation was being thrown away.
 *
 * Three things happen on the way out, and none of them are optional:
 *
 *  - The image is downscaled. A 12-megapixel phone photo of a bin bag is
 *    four megabytes of detail nobody needs to count litter in.
 *  - EXIF is dropped. A camera writes GPS, a timestamp and often a device
 *    serial into the file; a reviewer needs none of that, and a photo of
 *    somebody's street should not carry their phone's identity.
 *  - Orientation is baked into the pixels first, because dropping EXIF
 *    without doing that leaves every portrait photo sideways.
 */
object EvidenceStore {

    /** Long edge, in pixels. Enough to count pieces, far short of a portrait. */
    private const val MAX_EDGE = 1280

    private const val JPEG_QUALITY = 82

    /**
     * Uploads [localUri] as evidence for [submissionId].
     *
     * Returns the Storage path, or null if there was nothing to upload. Throws
     * on a failed upload so the caller -- the sync queue -- can decide whether
     * it is worth retrying.
     */
    suspend fun upload(
        context: Context,
        localUri: String,
        submissionId: String,
        capturedAt: Long,
        lat: Double?,
        lng: Double?,
        /** Distinguishes the haul shot from the per-tag spot checks. */
        name: String = "haul.jpg",
    ): String? = withContext(Dispatchers.IO) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
            ?: error("not signed in")

        val bytes = compress(context, Uri.parse(localUri)) ?: run {
            Log.w(TAG, "could not read $localUri; nothing uploaded")
            return@withContext null
        }

        val path = "evidence/$uid/$submissionId/$name"

        // The app's own capture time and fix, not the file's EXIF -- which is
        // exactly what we stripped, and which a determined user could have
        // written anything into anyway. The server checks these against the
        // walk's track and time window.
        val metadata = StorageMetadata.Builder()
            .setContentType("image/jpeg")
            .setCustomMetadata("capturedAt", capturedAt.toString())
            .apply {
                if (lat != null && lng != null) {
                    setCustomMetadata("lat", lat.toString())
                    setCustomMetadata("lng", lng.toString())
                }
            }
            .build()

        FirebaseStorage.getInstance()
            .getReference(path)
            .putBytes(bytes, metadata)
            .await()

        Log.i(TAG, "uploaded ${bytes.size / 1024} kB to $path")
        path
    }

    /**
     * Decodes, rotates, downscales and re-encodes, returning JPEG bytes with
     * no EXIF.
     *
     * Re-encoding from a bitmap is what guarantees the metadata is gone: there
     * is no attribute list to miss, because the output is built from pixels.
     */
    private fun compress(context: Context, uri: Uri): ByteArray? {
        // Each failure says which step gave up. This returned a bare null for
        // every cause, so a photo that existed on disk, was a valid JPEG and
        // still did not upload left nothing behind to tell you why.
        val rotation = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                runCatching { ExifInterface(input).rotationDegrees }.getOrDefault(0)
            } ?: run {
                Log.w(TAG, "compress: openInputStream returned null for exif pass")
                return null
            }
        } catch (e: Exception) {
            Log.w(TAG, "compress: opening $uri for exif threw", e)
            return null
        }

        // Two passes: measure, then decode subsampled. Decoding a 12 MP image
        // at full size only to shrink it is how a cheap phone runs out of heap.
        //
        // The stream is opened on its own line, and that is the entire point.
        // Written as `openInputStream(uri)?.use { decodeStream(...) } ?: return
        // null`, the elvis tests what the lambda returned rather than whether
        // the stream opened -- and a measure pass returns null on purpose,
        // because `inJustDecodeBounds` fills outWidth/outHeight and decodes
        // nothing. So that spelling bailed out every single time, for every
        // image, and no evidence photo ever uploaded.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val measuring = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            Log.w(TAG, "compress: opening $uri to measure threw", e)
            return null
        }
        if (measuring == null) {
            Log.w(TAG, "compress: openInputStream returned null for bounds pass")
            return null
        }
        measuring.use { BitmapFactory.decodeStream(it, null, bounds) }

        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (longEdge <= 0) {
            Log.w(TAG, "compress: bounds gave ${bounds.outWidth}x${bounds.outHeight}")
            return null
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = generateSequence(1) { it * 2 }
                .first { longEdge / it <= MAX_EDGE * 2 }
        }
        val decoding = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            Log.w(TAG, "compress: opening $uri to decode threw", e)
            return null
        }
        if (decoding == null) {
            Log.w(TAG, "compress: openInputStream returned null for decode pass")
            return null
        }
        val decoded = decoding.use { BitmapFactory.decodeStream(it, null, options) }
        if (decoded == null) {
            Log.w(TAG, "compress: decode produced no bitmap (sample ${options.inSampleSize})")
            return null
        }

        val scaled = scaleToLongEdge(decoded, MAX_EDGE)
        val upright = if (rotation == 0) scaled else rotate(scaled, rotation)

        return ByteArrayOutputStream().use { out ->
            upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            upright.recycle()
            out.toByteArray()
        }
    }

    private fun scaleToLongEdge(source: Bitmap, maxEdge: Int): Bitmap {
        val longEdge = maxOf(source.width, source.height)
        if (longEdge <= maxEdge) return source
        val ratio = maxEdge.toFloat() / longEdge
        val scaled = Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== source) source.recycle()
        return scaled
    }

    private fun rotate(source: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(
            source, 0, 0, source.width, source.height, matrix, true,
        )
        if (rotated !== source) source.recycle()
        return rotated
    }
}
