package com.trashtalk.benchmark

import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TARGET = "com.trashtalk"

/**
 * The numbers behind the word "snappy".
 *
 * Budgets, from the performance gates:
 *
 *  - cold start to first map frame: under 1.5 s on a mid-range phone
 *  - under 1% janky frames on map gestures
 *
 * These assert nothing on their own -- Macrobenchmark reports, CI compares
 * against the previous run. The point is that the claim is checkable.
 *
 * Measured on a Pixel 6a, 2026-10-16:
 *
 *  - first launch after install  1008 ms median (466 min, 2833 max)
 *  - cold launch thereafter       321-785 ms median across runs, 1033 max
 *
 * Both numbers are real and they measure different things. The first run
 * copies the 937 kB tile archive out of the APK, which only ever happens once;
 * the second is what the app costs every day after. Quoting only the faster
 * one would flatter the install experience, and only the slower one would
 * misrepresent daily use.
 *
 * There are deliberately no frame-timing benchmarks here, and that is a
 * finding rather than an omission.
 *
 * FrameTimingMetric measures the app's HWUI frame pipeline. maplibre-compose
 * renders into a SurfaceView driven by its own render thread, and SurfaceView
 * content is composited by SurfaceFlinger without passing through HWUI. So the
 * metric structurally cannot see the map's frames: across three runs it
 * reported four to seven frames for four full-screen swipes, and no timing at
 * all. Those few frames were the Compose chrome drawn around the map.
 *
 * Keeping those tests would have been worse than not having them, because a
 * green benchmark named `mapPan` reads as evidence that panning is smooth.
 *
 * To actually gate map smoothness, measure the renderer rather than the
 * window: maplibre-compose exposes RenderStats from its own render loop. The
 * JankStats wiring in the app (core/Jank.kt) remains valid for everything that
 * does go through HWUI -- sheets, lists, the collect form, the celebration.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule val rule = MacrobenchmarkRule()

    /**
     * Grant location up front.
     *
     * Without this the first launch puts the system permission dialog over the
     * map, and every swipe the frame tests make lands on the dialog instead.
     * That does not fail -- it reports a median of three or four frames for a
     * gesture that should produce dozens, which looks like a fast map rather
     * than a test that never touched one.
     */
    @Before
    fun grantLocation() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (permission in listOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
        )) {
            runCatching { automation.grantRuntimePermission(TARGET, permission) }
        }
    }

    /**
     * Cold start, which for this app means "until the map has drawn".
     *
     * The map is the start destination, and it has real work to do before the
     * first frame: staging the tile archive out of the APK on first run,
     * parsing the street network, binning it. Measuring only to
     * `reportFullyDrawn` would miss all of it.
     */
    @Test
    fun coldStartup() = rule.measureRepeated(
        packageName = TARGET,
        metrics = listOf(StartupTimingMetric()),
        iterations = 10,
        startupMode = StartupMode.COLD,
    ) {
        pressHome()
        startActivityAndWait()
        // The map is up once the neighbourhood header is on screen.
        device.wait(Until.hasObject(By.textContains("Terrell Heights")), 10_000)
    }

}
