package com.trashtalk.ui.map

import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.trashtalk.core.Mercator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.floor
import kotlin.math.pow

/**
 * The raster basemap.
 *
 * A volunteer standing on a kerb needs to recognise where they are -- street
 * names, the park, the creek -- and that is more cartography than it is sane to
 * draw from a bundled file. These are standard XYZ tiles, so the provider is a
 * one-line change.
 *
 * The default is Esri's World Light Gray Canvas, split into a base and a label
 * layer. Two reasons for that style: it is deliberately pale and low-chroma so a
 * data layer can sit on top without fighting it, and the labels ship separately,
 * which lets them be drawn *above* the litter surface so street names stay
 * readable through it.
 *
 * CARTO Positron was the first choice and is kept below as a reference: as of
 * 2026 their public basemap endpoint returns an "API key required" watermark
 * tile rather than an error, which is worth knowing about, because a watermark
 * that arrives with HTTP 200 and decodes as a valid PNG defeats every obvious
 * health check.
 *
 * Tiles are a progressive enhancement, never a dependency. The bundled street
 * geometry is always drawn underneath, so with no network -- common in exactly
 * the places most worth surveying -- the map still works.
 */
enum class TileStyle(val template: String, val attribution: String) {
    /** Pale canvas: roads, water and landuse, no labels. */
    LIGHT_BASE(
        "https://services.arcgisonline.com/ArcGIS/rest/services/Canvas/" +
            "World_Light_Gray_Base/MapServer/tile/{z}/{y}/{x}",
        "Esri, HERE, Garmin, © OpenStreetMap contributors",
    ),

    /** Place and street labels, transparent elsewhere. Drawn above the data. */
    LIGHT_LABELS(
        "https://services.arcgisonline.com/ArcGIS/rest/services/Canvas/" +
            "World_Light_Gray_Reference/MapServer/tile/{z}/{y}/{x}",
        "",
    );

    fun url(z: Int, x: Int, y: Int): String = template
        .replace("{z}", z.toString())
        .replace("{x}", x.toString())
        .replace("{y}", y.toString())
}

object TileSource {
    const val TILE_SIZE = 256

    /** Shown on the map; required by the tile provider's terms. */
    const val ATTRIBUTION = "Basemap: Esri, HERE, Garmin, © OpenStreetMap"

    /**
     * Identifies the app to the tile provider. An app that sends no agent is the
     * first thing a tile service rate-limits.
     */
    const val USER_AGENT = "TrashTalk/1.0 (neighbourhood litter survey)"
}

/** A tile address, including which layer it belongs to. */
data class TileKey(val style: TileStyle, val z: Int, val x: Int, val y: Int)

/**
 * Loads and caches basemap tiles.
 *
 * Bounded by bytes rather than a tile count, and requests are capped by a
 * semaphore so a fast pan cannot open fifty sockets at once.
 */
class TileCache(
    private val scope: CoroutineScope,
    maxBytes: Int = 64 * 1024 * 1024,
) {
    private val cache = object : LruCache<TileKey, ImageBitmap>(maxBytes) {
        override fun sizeOf(key: TileKey, value: ImageBitmap): Int =
            value.width * value.height * 4
    }

    private val inFlight = mutableSetOf<TileKey>()
    private val failed = mutableSetOf<TileKey>()
    private val gate = Semaphore(6)

    /** Bumped whenever a tile arrives, so the map can invalidate and redraw. */
    var version by mutableStateOf(0)
        private set

    fun get(key: TileKey): ImageBitmap? {
        cache.get(key)?.let { return it }
        request(key)
        return null
    }

    /** The best already-cached stand-in: a parent tile, scaled up while loading. */
    fun fallback(key: TileKey): Pair<ImageBitmap, TileKey>? {
        var z = key.z - 1
        var x = key.x / 2
        var y = key.y / 2
        while (z >= 0 && key.z - z <= 4) {
            val parent = TileKey(key.style, z, x, y)
            cache.get(parent)?.let { return it to parent }
            z--; x /= 2; y /= 2
        }
        return null
    }

    private fun request(key: TileKey) {
        synchronized(inFlight) {
            if (key in inFlight || key in failed) return
            inFlight += key
        }
        scope.launch {
            val bitmap = gate.withPermit { fetch(key) }
            synchronized(inFlight) { inFlight -= key }
            if (bitmap != null) {
                cache.put(key, bitmap)
                version++
            } else {
                synchronized(inFlight) { failed += key }
            }
        }
    }

    private suspend fun fetch(key: TileKey): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(key.style.url(key.z, key.x, key.y))
                .openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", TileSource.USER_AGENT)
            }
            if (connection.responseCode != 200) {
                Log.w(TAG, "tile ${key.z}/${key.x}/${key.y} http ${connection.responseCode}")
                null
            } else {
                connection.inputStream.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            }
        }.getOrElse {
            Log.w(TAG, "tile ${key.z}/${key.x}/${key.y} unavailable: ${it.message}")
            null
        }
    }

    /** Clears the failure set so a reconnect can retry. */
    fun retryFailed() {
        synchronized(inFlight) { failed.clear() }
        version++
    }

    private companion object {
        const val TAG = "TrashTalkTiles"
    }
}

@Composable
fun rememberTileCache(scope: CoroutineScope): TileCache = remember(scope) { TileCache(scope) }

/** One tile positioned on screen. */
class PlacedTile(
    val image: ImageBitmap?,
    val fallback: ImageBitmap?,
    /** Source rect within the fallback image, when one is being stretched. */
    val fallbackSrcOffset: Offset,
    val fallbackSrcSize: Float,
    val topLeft: Offset,
    val size: Float,
)

/**
 * Works out which tiles of [style] cover the viewport and where each one lands.
 *
 * The integer tile zoom is the camera zoom truncated, and tiles are then drawn at
 * a fractional scale, which is what keeps a pinch smooth instead of snapping
 * between levels.
 */
fun layoutTiles(
    camera: MapCameraState,
    size: Size,
    cache: TileCache,
    style: TileStyle,
): List<PlacedTile> {
    if (size.width <= 0f) return emptyList()

    val z = camera.zoom.toInt().coerceIn(0, 19)
    val scale = 2.0.pow(camera.zoom.toDouble() - z)
    val tilePx = (TileSource.TILE_SIZE * scale).toFloat()
    val n = 1 shl z

    val centerTx = Mercator.worldX(camera.center.lng) * n
    val centerTy = Mercator.worldY(camera.center.lat) * n

    val halfW = size.width / 2f
    val halfH = size.height / 2f

    val minTx = floor(centerTx - halfW / tilePx).toInt()
    val maxTx = floor(centerTx + halfW / tilePx).toInt()
    val minTy = floor(centerTy - halfH / tilePx).toInt().coerceAtLeast(0)
    val maxTy = floor(centerTy + halfH / tilePx).toInt().coerceAtMost(n - 1)

    val out = ArrayList<PlacedTile>((maxTx - minTx + 1) * (maxTy - minTy + 1))
    for (ty in minTy..maxTy) {
        for (tx in minTx..maxTx) {
            // Wrap horizontally so panning across the antimeridian still works.
            val wrapped = ((tx % n) + n) % n
            val key = TileKey(style, z, wrapped, ty)
            val image = cache.get(key)

            var fb: ImageBitmap? = null
            var fbOffset = Offset.Zero
            var fbSize = 0f
            if (image == null) {
                cache.fallback(key)?.let { (parent, parentKey) ->
                    val step = 1 shl (key.z - parentKey.z)
                    val sub = parent.width.toFloat() / step
                    fb = parent
                    fbOffset = Offset((wrapped % step) * sub, (ty % step) * sub)
                    fbSize = sub
                }
            }

            out += PlacedTile(
                image = image,
                fallback = fb,
                fallbackSrcOffset = fbOffset,
                fallbackSrcSize = fbSize,
                topLeft = Offset(
                    (halfW + ((tx - centerTx) * tilePx)).toFloat(),
                    (halfH + ((ty - centerTy) * tilePx)).toFloat(),
                ),
                size = tilePx,
            )
        }
    }
    return out
}
