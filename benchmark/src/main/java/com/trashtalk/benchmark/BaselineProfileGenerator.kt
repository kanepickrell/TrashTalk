package com.trashtalk.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the baseline profile.
 *
 * Walks the paths a new user takes in their first minute, so those classes and
 * methods are compiled ahead of time rather than interpreted on the frames the
 * user is actually watching. The map's first draw is the expensive one and it
 * is also the first thing anyone sees, which makes it the single best thing to
 * spend profile budget on.
 *
 *   ./gradlew :app:generateBaselineProfile
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = "com.trashtalk") {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.textContains("Terrell Heights")), 10_000)

        // Each layer compiles a different set of style expressions.
        for (label in listOf("Territory", "7-day change", "Cleanup", "Litter density")) {
            device.findObject(By.text(label))?.click()
            device.waitForIdle()
        }

        // A pan, so the renderer's tile and draw paths are covered.
        val w = device.displayWidth
        val h = device.displayHeight
        device.swipe(w * 3 / 4, h * 2 / 3, w / 4, h / 3, 10)
        device.waitForIdle()

        // The other tabs, which is where a new user goes next.
        for (tab in listOf("Levels", "Paths", "Ranks", "Me")) {
            device.findObject(By.text(tab))?.click()
            device.waitForIdle()
        }
    }
}
