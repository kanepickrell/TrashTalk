package com.trashtalk.ui.map

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.compose.style.BaseStyle
import java.io.File

/**
 * The offline basemap: a Protomaps vector archive and the style that paints it.
 *
 * Everything the map needs is in the APK -- tiles, glyphs, sprites -- so the
 * map works the same on a dead network in the middle of a cleanup as it does on
 * wifi. Nothing here touches the network, and there is no fallback that does.
 *
 * Rebuild the package with `pwsh tools/build_basemap.ps1`.
 */
object Basemap {

    /** Required wherever the basemap is shown. */
    const val ATTRIBUTION = "© Protomaps © OpenStreetMap"

    private const val ARCHIVE_ASSET = "map/terrell_heights.pmtiles"
    private const val STYLE_ASSET = "map/style.json"

    /** The token build_basemap_style.mjs leaves for the staged archive path. */
    private const val ARCHIVE_TOKEN = "{archive}"

    /**
     * Copies the tile archive out of the APK, returning the staged file.
     *
     * The renderer range-reads the archive -- that is the whole point of the
     * PMTiles layout -- and an asset is an entry inside the APK zip rather than
     * a file with an offset, so there is nothing to seek. Fonts and sprites are
     * whole-file reads and load from `asset://` directly; only this one needs a
     * real path.
     *
     * The copy is skipped when a staged file of the right length is already
     * there, which is every launch after the first.
     */
    private fun stageArchive(context: Context): File {
        val out = File(context.filesDir, "terrell_heights.pmtiles")

        // Cheap length check with no read. This works only because the app's
        // build marks `pmtiles` noCompress -- a compressed asset has no file
        // descriptor. If that setting is ever dropped this throws, which is
        // what we want: a stale basemap that silently never updates is a far
        // worse failure than a loud one.
        val expected = context.assets.openFd(ARCHIVE_ASSET).use { it.length }

        if (!out.exists() || out.length() != expected) {
            // Write beside the target and rename, so a kill mid-copy cannot
            // leave a truncated archive that looks complete on the next launch.
            val tmp = File(context.filesDir, "terrell_heights.pmtiles.part")
            context.assets.open(ARCHIVE_ASSET).use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            check(tmp.renameTo(out)) { "could not replace the staged basemap archive" }
        }
        return out
    }

    /**
     * Reads the style and points it at the staged archive.
     *
     * Blocking; call it off the main thread. [rememberStyle] does that for you.
     */
    fun load(context: Context): BaseStyle {
        val archive = stageArchive(context)
        val json = context.assets.open(STYLE_ASSET)
            .bufferedReader()
            .use { it.readText() }

        require(json.contains(ARCHIVE_TOKEN)) {
            "style.json has no $ARCHIVE_TOKEN placeholder; regenerate it with tools/build_basemap.ps1"
        }

        return BaseStyle.Json(json.replace(ARCHIVE_TOKEN, "file://${archive.absolutePath}"))
    }
}

/** What [rememberBasemapStyle] is currently holding. */
sealed interface BasemapStyle {
    /** First launch, while the archive is copied out of the APK. */
    data object Staging : BasemapStyle

    data class Ready(val style: BaseStyle) : BasemapStyle

    /**
     * The basemap could not be prepared. There is no network fallback by
     * design, so callers should say so plainly rather than show an empty map.
     */
    data class Failed(val cause: Throwable) : BasemapStyle
}

/**
 * Prepares the basemap once per composition and reports progress.
 *
 * Staging is a ~937 kB copy that happens on first launch only, but it is still
 * file I/O, so it runs off the main thread and the map waits on [BasemapStyle].
 */
@Composable
fun rememberBasemapStyle(): State<BasemapStyle> {
    val context = LocalContext.current
    return produceState<BasemapStyle>(initialValue = BasemapStyle.Staging, context) {
        value = withContext(Dispatchers.IO) {
            runCatching { Basemap.load(context) }
                .fold(
                    onSuccess = { BasemapStyle.Ready(it) },
                    onFailure = { BasemapStyle.Failed(it) },
                )
        }
    }
}
