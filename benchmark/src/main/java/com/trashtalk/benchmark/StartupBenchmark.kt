package com.trashtalk.benchmark

import androidx.benchmark.macro.FrameTimingMetric
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
 *  - cold launch thereafter       321 ms median (580 P90)
 *
 * Both numbers are real and they measure different things. The first run
 * copies the 937 kB tile archive out of the APK, which only ever happens once;
 * the second is what the app costs every day after. Quoting only the faster
 * one would flatter the install experience, and only the slower one would
 * misrepresent daily use.
 *
 * The frame metrics below are NOT yet trustworthy. They report a frame count
 * with no timing, and the count is bimodal -- a median of 2 against a maximum
 * of ~200 -- which says most iterations never drove the map at all. The
 * harness is here and the startup number is sound; treat any jank figure from
 * these two as unmeasured until that is fixed.
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

    /** Panning the map: the gesture people spend the most frames on. */
    @Test
    fun mapPan() = rule.measureRepeated(
        packageName = TARGET,
        metrics = listOf(FrameTimingMetric()),
        iterations = 10,
        startupMode = StartupMode.WARM,
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.textContains("Terrell Heights")), 10_000)

        val w = device.displayWidth
        val h = device.displayHeight
        repeat(4) {
            // Through the middle of the map, clear of the top chrome and the
            // bottom sheet, so this measures the map and not the furniture.
            // 40 steps rather than 10: a swipe that completes in four frames
            // measures nothing about how the map behaves under a real drag.
            device.swipe(w * 3 / 4, h * 2 / 3, w / 4, h / 3, 40)
            device.waitForIdle()
        }
    }

    /** Switching the question being asked: should be an expression swap. */
    @Test
    fun layerSwitch() = rule.measureRepeated(
        packageName = TARGET,
        metrics = listOf(FrameTimingMetric()),
        iterations = 10,
        startupMode = StartupMode.WARM,
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.textContains("Terrell Heights")), 10_000)

        for (label in listOf("Territory", "7-day change", "Cleanup", "Litter density")) {
            device.findObject(By.text(label))?.click()
            device.waitForIdle()
        }
    }
}
