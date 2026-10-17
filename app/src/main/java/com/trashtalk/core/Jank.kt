package com.trashtalk.core

import android.app.Activity
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState

private const val TAG = "TTJank"

/**
 * Per-screen jank measurement.
 *
 * "Snappy" is a feeling until someone puts a number on it. This reports the
 * share of frames that missed their deadline, attributed to whatever the user
 * was doing at the time -- panning the map is a different budget from opening
 * a sheet, and an average across both hides whichever one is broken.
 *
 * Only the counts are kept. Frame timing is not interesting as a stream; what
 * matters is "map pan: 0.4% janky over 2,300 frames", which is a claim that can
 * be checked against a budget.
 */
object Jank {

    private val counters = mutableMapOf<String, Counter>()

    private class Counter {
        var frames = 0L
        var janky = 0L
        var worstNanos = 0L
    }

    internal fun record(stateName: String, isJank: Boolean, durationNanos: Long) {
        val c = counters.getOrPut(stateName) { Counter() }
        c.frames++
        if (isJank) {
            c.janky++
            if (durationNanos > c.worstNanos) c.worstNanos = durationNanos
        }
    }

    /** Percentage of janky frames for [stateName], or null if nothing was seen. */
    fun percent(stateName: String): Double? {
        val c = counters[stateName] ?: return null
        if (c.frames == 0L) return null
        return c.janky * 100.0 / c.frames
    }

    /** Dumps every counter. Called from the debug build; cheap and rare. */
    fun report(): String = counters.entries
        .sortedByDescending { it.value.janky }
        .joinToString("\n") { (name, c) ->
            "%-22s %5.2f%% janky  (%d/%d frames, worst %.1f ms)".format(
                name,
                c.janky * 100.0 / c.frames.coerceAtLeast(1),
                c.janky,
                c.frames,
                c.worstNanos / 1_000_000.0,
            )
        }

    fun reset() = counters.clear()
}

/**
 * Attaches JankStats to the window for as long as this composable is present.
 *
 * One per activity, not per screen: the window has a single frame stream, and
 * screens distinguish themselves with [TrackJank] instead.
 */
@Composable
fun JankMonitor() {
    val context = LocalContext.current
    val activity = context as? Activity ?: return

    DisposableEffect(activity) {
        val stats = JankStats.createAndTrack(activity.window) { frame ->
            // The state the app put on the frame, so a janky frame is
            // attributable rather than just a number.
            val state = frame.states.firstOrNull()?.value ?: "unattributed"
            Jank.record(state, frame.isJank, frame.frameDurationUiNanos)
        }
        onDispose { stats.isTrackingEnabled = false }
    }
}

/**
 * Names what the user is doing, so frames rendered now are attributed to it.
 *
 * Wrap the screen or the gesture: `TrackJank("map")` for a screen,
 * `TrackJank("map-pan")` while a drag is in flight.
 */
@Composable
fun TrackJank(name: String, key: String = "screen") {
    val context = LocalContext.current
    val activity = context as? Activity ?: return

    DisposableEffect(activity, key, name) {
        val holder = PerformanceMetricsState.getHolderForHierarchy(
            activity.window.decorView,
        )
        holder.state?.putState(key, name)
        onDispose { holder.state?.removeState(key) }
    }
}

/** Logs the jank report. Debug builds only; the caller checks. */
fun logJankReport() {
    val report = Jank.report()
    if (report.isNotBlank()) Log.i(TAG, "\n$report")
}
