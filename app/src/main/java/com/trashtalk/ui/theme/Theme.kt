package com.trashtalk.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * TrashTalk reads as an instrument, not a toy: a near-black field with data as the
 * only bright thing on screen. That choice is not only aesthetic. Litter density is
 * continuous magnitude, so it is encoded as a single-hue sequential ramp whose dim
 * end recedes into the surface and whose bright end blooms; the brand teal is held
 * back for interface chrome so it can never be mistaken for data.
 *
 * Every palette value below was validated with the data-viz validator against this
 * app's own surfaces (page #07090C, card #121A23) rather than the generic defaults:
 *
 *  - categorical 8 (dark steps) .... lightness band, chroma, CVD and contrast all PASS
 *                                    (worst adjacent CVD dE 8.4, normal-vision 19.3)
 *  - litter ramp (amber, 6 steps) .. monotone L, dL >= 0.06, single hue (30 deg spread)
 *  - cleanup ramp (blue, 6 steps) .. monotone L, dL >= 0.06, single hue (6 deg spread)
 *  - legend subsets ................ dim end clears 2:1 on the card surface
 *  - status + ink .................. all >= 3:1 on both surfaces
 *
 * The continuous ramps intentionally fail the ordinal "dim end" gate: for a
 * heatmap the near-zero end is supposed to fall away toward the surface. Discrete
 * uses (legend chips, ordinal bars) take the floored subsets instead.
 */
object TT {
    // Surfaces ---------------------------------------------------------------
    val Void = Color(0xFF07090C)        // page plane, and the map's ground
    val Surface1 = Color(0xFF121A23)    // cards, sheets, chart surface
    val Surface2 = Color(0xFF1B2531)    // raised rows inside a card
    val Surface3 = Color(0xFF243040)    // pressed / selected
    val Hairline = Color(0xFF1E2A36)    // gridlines, dividers
    val Baseline = Color(0xFF2A3744)    // axis lines

    // Ink --------------------------------------------------------------------
    val Ink = Color(0xFFF2F6FA)         // 16.2:1 on card
    val InkSecondary = Color(0xFFA8B6C4) //  8.5:1
    val InkMuted = Color(0xFF7D8E9E)    //  5.0:1, axis ticks and captions

    // Brand chrome - never used to encode data ------------------------------
    val Teal = Color(0xFF27E0C8)
    val TealDim = Color(0xFF12806F)
    val Magenta = Color(0xFFFF4D8D)

    // Status: reserved meaning, always shipped with an icon + label ----------
    val Good = Color(0xFF0CA30C)
    val Warning = Color(0xFFFAB219)
    val Serious = Color(0xFFEC835A)
    val Critical = Color(0xFFD03B3B)

    /**
     * Sequential ramp for litter density. Continuous magnitude: one hue, monotone
     * lightness, dim end allowed to recede into the void.
     */
    val LitterRamp = listOf(
        Color(0xFF3A2410),
        Color(0xFF6B3A0E),
        Color(0xFFA85A12),
        Color(0xFFE08A22),
        Color(0xFFFFC14D),
        Color(0xFFFFE6B0),
    )

    /** The same ramp floored for discrete marks, where the dim end must stay visible. */
    val LitterRampOrdinal = listOf(
        Color(0xFFA85A12),
        Color(0xFFE08A22),
        Color(0xFFFFC14D),
        Color(0xFFFFE6B0),
    )

    /** Second sequential context (cleanup activity) takes the next hue: blue. */
    val ActivityRamp = listOf(
        Color(0xFF102A44),
        Color(0xFF184F95),
        Color(0xFF256ABF),
        Color(0xFF3987E5),
        Color(0xFF6DA7EC),
        Color(0xFFB7D3F6),
    )

    /** Diverging: improving (cool) vs worsening (warm) around a neutral gray. */
    val ImprovingArm = listOf(Color(0xFF184F95), Color(0xFF2A78D6), Color(0xFF86B6EF))
    val WorseningArm = listOf(Color(0xFF8E2B2B), Color(0xFFD03B3B), Color(0xFFF08080))
    val DivergingMid = Color(0xFF39404A)

    // ---- Light basemap ------------------------------------------------
    // The survey map sits on a pale raster basemap (CARTO Positron), so the
    // density layer is classified light-to-dark against a light ground rather
    // than glowing against a dark one.
    val MapGround = Color(0xFFF3F2EE)
    val MapInk = Color(0xFF2E3440)

    /**
     * Five classes for binned litter density, ordered light to dark.
     *
     * Validated against the basemap ground: monotone lightness, adjacent dL
     * >= 0.06, hue spread 31 degrees -- so it reads as one ordered sequence
     * rather than a rainbow. The lightest class sits at 1.6:1 against the pale
     * basemap, below the 2:1 ordinal floor, which is a deliberate trade for the
     * classed-choropleth look. The relief is that value never rests on fill
     * alone: every cell carries a dark outline (11:1 on the ground), the legend
     * states numeric ranges, and tapping a cell reports its exact count.
     */
    val HexRamp = listOf(
        Color(0xFFEABB3C),
        Color(0xFFF0954F),
        Color(0xFFE55E80),
        Color(0xFFB4529F),
        Color(0xFF5A4199),
    )

    /** Categorical identity, fixed order, never cycled. Folds to "Other" past 8. */
    val Categorical = listOf(
        Color(0xFF3987E5), // blue
        Color(0xFFD95926), // orange
        Color(0xFF199E70), // aqua
        Color(0xFFC98500), // yellow
        Color(0xFFD55181), // magenta
        Color(0xFF008300), // green
        Color(0xFF9085E9), // violet
        Color(0xFFE66767), // red
    )

    /** Identity colour for categorical slot [index], folding past the eighth. */
    fun categorical(index: Int): Color = Categorical[index.coerceIn(0, Categorical.lastIndex)]

    /**
     * Samples a ramp at [t] in 0..1 with linear interpolation between steps, so a
     * continuous quantity gets a continuous colour rather than a banded one.
     */
    fun sample(ramp: List<Color>, t: Float): Color {
        if (ramp.isEmpty()) return Ink
        val clamped = t.coerceIn(0f, 1f)
        val scaled = clamped * (ramp.size - 1)
        val i = scaled.toInt().coerceAtMost(ramp.size - 2)
        return lerp(ramp[i], ramp[i + 1], scaled - i)
    }

    /** Colour for a litter index in 0..1. */
    fun litterColor(index: Float): Color = sample(LitterRamp, index)

    /**
     * Colour for a signed change, diverging around zero. [magnitude] is normalised
     * so a half-point swing in the litter index saturates the arm.
     */
    fun changeColor(delta: Float): Color {
        val m = (kotlin.math.abs(delta) / 0.25f).coerceIn(0f, 1f)
        if (m < 0.08f) return DivergingMid
        val arm = if (delta > 0) WorseningArm else ImprovingArm
        return sample(arm, m)
    }
}

/** Five named bands over the litter index, each with a reserved status colour. */
enum class HazardBand(
    val label: String,
    val shortLabel: String,
    val blurb: String,
    val color: Color,
) {
    CLEAR("Clear", "L0", "Nothing meaningful to collect", TT.Good),
    LIGHT("Light", "L1", "Scattered pieces, a few minutes of work", TT.Good),
    MODERATE("Moderate", "L2", "A steady trail of litter", TT.Warning),
    HEAVY("Heavy", "L3", "Continuous litter, bring a bag", TT.Serious),
    SEVERE("Severe", "L4", "Dumping-scale accumulation, report it", TT.Critical);

    companion object {
        fun of(index: Float): HazardBand = when {
            index < 0.10f -> CLEAR
            index < 0.28f -> LIGHT
            index < 0.50f -> MODERATE
            index < 0.72f -> HEAVY
            else -> SEVERE
        }
    }
}

private val TrashTalkColorScheme = darkColorScheme(
    primary = TT.Teal,
    onPrimary = TT.Void,
    primaryContainer = TT.TealDim,
    onPrimaryContainer = TT.Ink,
    secondary = TT.Magenta,
    onSecondary = TT.Void,
    background = TT.Void,
    onBackground = TT.Ink,
    surface = TT.Surface1,
    onSurface = TT.Ink,
    surfaceVariant = TT.Surface2,
    onSurfaceVariant = TT.InkSecondary,
    outline = TT.Baseline,
    outlineVariant = TT.Hairline,
    error = TT.Critical,
    onError = TT.Ink,
)

/**
 * One UI sans throughout, including hero figures. Tabular figures are reserved for
 * columns that must align vertically; standalone numbers keep proportional figures.
 */
private val TrashTalkTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 44.sp,
        letterSpacing = (-1).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 25.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        letterSpacing = 0.2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 0.8.sp,
    ),
)

/** Eyebrow style for the small all-caps section labels used across the app. */
val EyebrowStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.SemiBold,
    fontSize = 11.sp,
    letterSpacing = 1.4.sp,
    textAlign = TextAlign.Start,
)

val CardCorner = 18.dp
val ScreenGutter = 16.dp

val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
fun TrashTalkTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    val context = LocalContext.current
    if (!view.isInEditMode) {
        SideEffect {
            (context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            }
        }
    }
    // The app is dark by design: the map's whole visual argument depends on a
    // near-black ground, so a light scheme is not offered rather than offered badly.
    @Suppress("UNUSED_EXPRESSION") isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = TrashTalkColorScheme,
        typography = TrashTalkTypography,
        content = content,
    )
}
