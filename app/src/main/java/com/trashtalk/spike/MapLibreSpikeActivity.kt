package com.trashtalk.spike

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trashtalk.ui.map.Basemap
import com.trashtalk.ui.map.BasemapStyle
import com.trashtalk.ui.map.rememberBasemapStyle
import com.trashtalk.ui.theme.TT
import com.trashtalk.ui.theme.TrashTalkTheme
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

/**
 * Phase 2.0 spike -- RESOLVED. Verdict: Path A.
 *
 * Asked two questions and answered both on hardware:
 *
 *  1. Does maplibre-compose 0.20.0 render on this toolchain (Kotlin 2.4.21 /
 *     AGP 9.4.1 / Compose 1.12.1)? Yes. Pixel 6a (Mali-G78) cold launch to
 *     first map frame in 928 ms.
 *  2. Can it read our bundled PMTiles archive with no network? Yes, but only
 *     from a real file -- see `stageArchive` below.
 *
 * Verified in airplane mode with `ping` confirming the network unreachable:
 * the Terrell Heights grid draws, pans by swipe, zooms on double-tap, and
 * rotates and pitches through the camera API. Nothing is fetched; the style
 * declares no glyphs or sprites, so no layer here depends on the network.
 *
 * Two premises in the Phase 2 plan turned out to be wrong, both in our favour:
 *
 *  - The plan assumed maplibre-compose pins MapLibre Native 13.6.1 and that
 *    forcing 13.7.1 would unlock PMTiles. That lever does not exist. 0.20.0
 *    has no dependency on `org.maplibre.gl:android-sdk` at all; it ships a
 *    Rust-backed `maplibre-native-ffi` with PMTiles compiled into the core.
 *  - The renderer backend is a *separate* artifact that nothing pulls in
 *    transitively. maplibre-compose alone gives you `libjniMaplibreNativeC.so`
 *    with no `libmaplibre-native-c.so` underneath it, and the first frame dies
 *    in `dlopen`. See `maplibre-renderer-opengl` in the version catalogue.
 *
 * Launch it directly:
 *   adb shell am start -n com.trashtalk.debug/com.trashtalk.spike.MapLibreSpikeActivity
 *
 * Delete this file, and its manifest entry, once the renderer is ported.
 */
class MapLibreSpikeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TrashTalkTheme { SpikeScreen() } }
    }
}

@Composable
private fun SpikeScreen() {
    val basemap by rememberBasemapStyle()

    when (val state = basemap) {
        is BasemapStyle.Staging -> {
            Box(Modifier.fillMaxSize().background(TT.Void), contentAlignment = Alignment.Center) {
                Text("Staging basemap...", color = TT.InkSecondary)
            }
            return
        }
        is BasemapStyle.Failed -> {
            Box(Modifier.fillMaxSize().background(TT.Void), contentAlignment = Alignment.Center) {
                Text(state.cause.toString(), color = TT.InkSecondary, modifier = Modifier.padding(24.dp))
            }
            return
        }
        is BasemapStyle.Ready -> Unit
    }
    val style = (basemap as BasemapStyle.Ready).style

    SpikeMap(style)
}

@Composable
private fun SpikeMap(style: BaseStyle) {
    val state = rememberMapState(
        baseStyle = style,
        initialCameraPosition = CameraPosition(
            center = Position(latitude = 29.5025, longitude = -98.4560),
            zoom = 15.0,
        ),
    )

    Box(Modifier.fillMaxSize().background(TT.Void)) {
        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            state = state,
        )

        // A live readout, so a screenshot proves the camera is real rather than
        // a static image, and shows where it actually is if nothing draws.
        Surface(
            color = TT.Surface1.copy(alpha = 0.92f),
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
        ) {
            // Tapping the HUD rotates and pitches the camera. A two-finger
            // twist cannot be synthesised over adb, so this drives the same
            // camera API that Phase 2.4's compass and recentre controls will.
            Column(
                Modifier
                    .clickable {
                        val cam = state.cameraPosition
                        state.setCameraPosition(cam.copy(
                            bearing = (cam.bearing + 35.0) % 360.0,
                            pitch = if (cam.pitch > 0.0) 0.0 else 45.0,
                        ))
                    }
                    .padding(12.dp),
            ) {
                Text("MapLibre spike", color = TT.Teal)
                Text(Basemap.ATTRIBUTION, color = TT.InkSecondary)
                val cam = state.cameraPosition
                Text(
                    "%.4f, %.4f  z%.2f  b%.0f  p%.0f".format(
                        cam.center.latitude, cam.center.longitude,
                        cam.zoom, cam.bearing, cam.pitch,
                    ),
                    color = TT.InkSecondary,
                )
            }
        }
    }
}
