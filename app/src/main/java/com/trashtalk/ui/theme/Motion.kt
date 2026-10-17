package com.trashtalk.ui.theme

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * The app's motion and touch-feedback vocabulary.
 *
 * Two reasons this is a token object rather than numbers at call sites. Timings
 * that drift apart read as sloppiness even when nobody can say why -- a 180 ms
 * chip beside a 220 ms chip looks broken. And reduce-motion has to be honoured
 * everywhere or nowhere; routing every duration through [duration] makes
 * "animations off" a property of the system rather than a flag each animation
 * remembers to check.
 *
 * Haptics are paired with the visual feedback deliberately. A tap that changes
 * a number but produces no physical response feels unregistered, which is why
 * people press buttons twice.
 */
@Immutable
class Motion(
    private val reduceMotion: Boolean,
    private val view: View,
) {

    // ------------------------------------------------------------ durations

    /** Taps, chips, small state flips. */
    val fast: Int get() = duration(150)

    /** Cards, tabs, anything the size of a panel. */
    val standard: Int get() = duration(250)

    /** Bottom sheets. */
    val sheet: Int get() = duration(320)

    /** Number roll-ups. */
    val countUp: Int get() = duration(600)

    /** The big reward moment. */
    val capture: Int get() = duration(900)

    /** Skippable replays. */
    val replay: Int get() = duration(1500)

    /**
     * A duration in milliseconds, or zero when the viewer has asked the system
     * to stop animating. Zero rather than "skip the animation" so call sites
     * keep one code path: the animation still runs, it just lands immediately.
     */
    fun duration(base: Int): Int = if (reduceMotion) 0 else base

    /** True when animation is suppressed; for the cases that need a branch. */
    val isReduced: Boolean get() = reduceMotion

    // --------------------------------------------------------------- curves

    /** Decelerating; the default for things arriving. */
    val easeOut: Easing = EaseOutCubic

    /** Emphasised deceleration, for the moments meant to be noticed. */
    val emphasized: Easing = EmphasizedEasing

    /** Overshoots and settles; for a reward landing. */
    val bounceOut: Easing = EaseOutBack

    // -------------------------------------------------------------- haptics

    /** A value ticked past: counters, scrubbers, each line of a breakdown. */
    fun selectionTick() = perform(HapticFeedbackConstants.CLOCK_TICK)

    /** A button or card accepted the touch. */
    fun lightTick() = perform(HapticFeedbackConstants.KEYBOARD_TAP)

    /** A mode changed: layer switch, segment selected. */
    fun mediumTick() = perform(HapticFeedbackConstants.VIRTUAL_KEY)

    /** The payoff: a submit landing, a badge unlocking. */
    fun heavyBurst() {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            // CONFIRM arrived in API 30; LONG_PRESS is the closest thing with
            // comparable weight on the versions below it.
            HapticFeedbackConstants.LONG_PRESS
        }
        perform(constant)
    }

    private fun perform(constant: Int) {
        // Reduce-motion is about animation, not touch feedback, so haptics are
        // deliberately left on: it is the channel that still works when motion
        // is off. The system's own "touch vibration" setting governs these, and
        // IGNORE_GLOBAL_SETTING is pointedly not passed.
        runCatching { view.performHapticFeedback(constant) }
    }
}

/** Matches Material 3's emphasised deceleration curve. */
private val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * The motion vocabulary for the current composition.
 *
 * Read once near the top of a screen and passed down, or read at the call site
 * for one-off feedback: `val motion = rememberMotion()` then `motion.lightTick()`.
 */
@Composable
fun rememberMotion(): Motion {
    val reduce = LocalReduceMotion.current
    val view = LocalView.current
    return remember(reduce, view) { Motion(reduce, view) }
}
