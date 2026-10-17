package com.trashtalk.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.trashtalk.ui.theme.TT
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.asBoolean
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.condition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.not
import org.maplibre.compose.expressions.dsl.exponential
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.step
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.DpValue
import org.maplibre.compose.expressions.value.FloatValue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource

/**
 * The data layers drawn over the Protomaps basemap.
 *
 * Every layer here reads raw measurements off the features (see MapSources)
 * and turns them into colour and width with style expressions. Nothing is
 * precomputed per frame, which is the point: switching between density,
 * territory, change and activity swaps expressions on layers that are already
 * uploaded, so the recolour costs a style update rather than a rebuild of
 * three hundred polylines.
 *
 * Layer order, bottom to top: hex surface, segment casings, segments, the
 * selected segment, then markers. The whole stack is anchored *below* the
 * basemap's own label layers, so street names stay readable on top of the
 * data rather than being buried by it -- which is the behaviour the Felt
 * reference has and the thing that makes a dense map navigable.
 */

/** The basemap layer the data stack slots in beneath. */
private const val BELOW_LABELS = "roads_labels_minor"

@Composable
fun MapDataLayers(
    collections: MapSources.Collections,
    layer: MapLayer,
    showMarkers: Boolean = true,
    selectedSegmentId: String? = null,
    onSegmentTap: (String) -> Unit = {},
    onBinTap: (q: Int, r: Int) -> Unit = { _, _ -> },
) {
    val segments = rememberGeoJsonSource(GeoJsonData.JsonString(collections.segments))
    val hexes = rememberGeoJsonSource(GeoJsonData.JsonString(collections.hexes))
    val markers = rememberGeoJsonSource(GeoJsonData.JsonString(collections.markers))

    Anchor.Below(BELOW_LABELS) {

        // ---- the density surface ----------------------------------------
        // Only drawn for the density question. Binning answers "where is the
        // problem" far faster than a few hundred thin strokes, but it would
        // blur a property *of a block* like ownership, so the other three
        // questions stay on the segments.
        FillLayer(
            id = "tt-hex-fill",
            source = hexes,
            visible = layer == MapLayer.DENSITY,
            color = hexClassColor(),
            // Translucent so the street grid stays legible underneath: the
            // reader needs to know *which* blocks a hot cell covers, and a
            // surface that hides the streets answers "where" with a colour
            // nobody can turn into an address.
            opacity = const(0.55f),
            onClick = { features ->
                val f = features.firstOrNull()?.properties
                val q = (f?.get("q") as? JsonPrimitive)?.intOrNull
                val r = (f?.get("r") as? JsonPrimitive)?.intOrNull
                if (q != null && r != null) {
                    onBinTap(q, r)
                    ClickResult.Consume
                } else {
                    ClickResult.Pass
                }
            },
        )
        LineLayer(
            id = "tt-hex-edge",
            source = hexes,
            visible = layer == MapLayer.DENSITY,
            // The outline is the relief that lets the lightest class be read
            // at all: that class sits at 1.6:1 on the ground, below the 2:1
            // ordinal floor, and this edge carries 11:1.
            color = const(TT.MapInk.copy(alpha = 0.28f)),
            width = const(0.6.dp),
        )

        // ---- the segments ------------------------------------------------
        // A white casing first, so a coloured segment reads as a ribbon laid
        // on the street rather than a stripe lost among the basemap's own
        // white roads.
        LineLayer(
            id = "tt-segment-casing",
            source = segments,
            color = const(Color.White),
            width = segmentWidth(pad = 2.2f),
            opacity = segmentOpacity(layer),
            cap = const(org.maplibre.compose.expressions.value.LineCap.Round),
            join = const(org.maplibre.compose.expressions.value.LineJoin.Round),
        )
        LineLayer(
            id = "tt-segment",
            source = segments,
            color = segmentColor(layer),
            width = segmentWidth(),
            opacity = segmentOpacity(layer),
            cap = const(org.maplibre.compose.expressions.value.LineCap.Round),
            join = const(org.maplibre.compose.expressions.value.LineJoin.Round),
            // The library does hit padding itself, so there is no need for the
            // invisible fat line a hand-rolled hit test would require. 22dp is
            // a shade under the 48dp minimum target, halved: two parallel
            // streets a block apart must not both answer to one tap.
            hitPadding = 22.dp,
            onClick = { features ->
                val sid = features.firstNotNullOfOrNull { f ->
                    (f.properties?.get("sid") as? JsonPrimitive)?.contentOrNull
                }
                if (sid != null) {
                    onSegmentTap(sid)
                    ClickResult.Consume
                } else {
                    ClickResult.Pass
                }
            },
        )

        // ---- never surveyed ----------------------------------------------
        // Dashed, because an unsurveyed block has no measurement behind it and
        // should not be drawn as though it does. The dash is the honest mark:
        // it reads as "no data" rather than "clean".
        LineLayer(
            id = "tt-segment-unsurveyed",
            source = segments,
            filter = !feature.get("surveyed").asBoolean(),
            color = const(TT.MapInk.copy(alpha = 0.45f)),
            width = const(1.4.dp),
            dasharray = const(listOf(1.5f, 2.5f)),
        )

        // ---- selection ----------------------------------------------------
        // A filter on the segment's own id, not on the numeric feature id:
        // style expressions carry numbers as floats, and our ids run to about
        // 2.8e14, well past the 2^24 where a float stops counting by ones. The
        // comparison would quietly match the wrong segment. The string id is
        // exact.
        //
        // Re-filtering is a style update, not a source upload, so this is no
        // more expensive than the feature-state alternative and far less code.
        LineLayer(
            id = "tt-segment-selected",
            source = segments,
            visible = selectedSegmentId != null,
            filter = feature.get("sid").asString() eq const(selectedSegmentId ?: ""),
            color = const(TT.Teal),
            width = segmentWidth(pad = 3.4f),
            opacity = const(0.9f),
            cap = const(org.maplibre.compose.expressions.value.LineCap.Round),
            join = const(org.maplibre.compose.expressions.value.LineJoin.Round),
        )

        // ---- markers -------------------------------------------------------
        if (showMarkers) {
            CircleLayer(
                id = "tt-collections",
                source = markers,
                filter = feature.get("kind").asString() eq const("collection"),
                color = const(TT.Teal),
                radius = interpolate(
                    linear(),
                    feature.get("pieces").asNumber(),
                    0 to const(2.5.dp),
                    40 to const(7.dp),
                ),
                strokeColor = const(Color.White),
                strokeWidth = const(1.2.dp),
                opacity = const(0.85f),
            )
            CircleLayer(
                id = "tt-pois",
                source = markers,
                filter = feature.get("kind").asString() eq const("poi"),
                // Litter generators: where the stuff comes from.
                color = poiColor(),
                radius = const(3.4.dp),
                strokeColor = const(Color.White),
                strokeWidth = const(1.6.dp),
                minZoom = 15f,
            )
            CircleLayer(
                id = "tt-flags",
                source = markers,
                filter = feature.get("kind").asString() eq const("flag"),
                color = flagColor(),
                radius = const(4.6.dp),
                strokeColor = const(Color.White),
                strokeWidth = const(1.6.dp),
            )
        }
    }
}

// ------------------------------------------------------------- expressions

/**
 * Segment width: thicker for bigger streets, and growing with zoom.
 *
 * Exponential in zoom rather than linear, because a map's scale is
 * exponential -- a linear width ramp looks right at one zoom and wrong at
 * every other.
 */
private fun segmentWidth(pad: Float = 0f): Expression<DpValue> =
    interpolate(
        exponential(1.6f),
        zoom(),
        13 to const((0.8f + pad * 0.3f).dp),
        16 to const((2.6f + pad * 0.6f).dp),
        19 to const((9f + pad).dp),
    )

/**
 * How loud the segments are.
 *
 * Quiet under the density surface, where the hexes carry the answer and the
 * lines are only there to say which streets a cell covers. Full strength for
 * the three questions that live on the segments themselves.
 */
private fun segmentOpacity(layer: MapLayer): Expression<FloatValue> =
    if (layer == MapLayer.DENSITY) const(0.22f) else const(0.95f)

private fun segmentColor(layer: MapLayer): Expression<ColorValue> = when (layer) {
    MapLayer.DENSITY -> const(TT.MapInk)

    // Diverging: two hues around a neutral midpoint, never a hue at the
    // middle. The stops mirror TT.mapChangeColor so the legend swatches and
    // the map agree exactly.
    MapLayer.CHANGE -> interpolate(
        linear(),
        feature.get("change").asNumber(),
        -0.25 to const(TT.MapImprovingArm[2]),
        -0.125 to const(TT.MapImprovingArm[1]),
        -0.02 to const(TT.MapImprovingArm[0]),
        0.0 to const(TT.MapDivergingMid),
        0.02 to const(TT.MapWorseningArm[0]),
        0.125 to const(TT.MapWorseningArm[1]),
        0.25 to const(TT.MapWorseningArm[2]),
    )

    // Sequential: one hue, light to dark, six cleanups being the top of the
    // range the ramp was built for.
    MapLayer.ACTIVITY -> interpolate(
        linear(),
        feature.get("cleanups").asNumber(),
        0 to const(TT.MapActivityRamp[0]),
        2 to const(TT.MapActivityRamp[1]),
        4 to const(TT.MapActivityRamp[2]),
        6 to const(TT.MapActivityRamp[3]),
    )

    // Categorical: the viewer's own holdings in the brand teal, everyone
    // else's in their crew's fixed palette slot, unclaimed left as quiet ink.
    MapLayer.TERRITORY -> switch(
        conditions = listOf(
            condition(
                feature.get("owned").asNumber() eq const(MapSources.OWNED_ME.toFloat()),
                const(TT.TealDim),
            ),
            condition(
                feature.get("owned").asNumber() eq const(MapSources.OWNED_OTHER.toFloat()),
                teamSlotColor(),
            ),
        ),
        fallback = const(TT.MapInk.copy(alpha = 0.35f)),
    )
}

/** The density class, as the five-step classed ramp. */
private fun hexClassColor(): Expression<ColorValue> =
    step(
        feature.get("cls").asNumber(),
        const(TT.HexRamp[0]),
        1 to const(TT.HexRamp[1]),
        2 to const(TT.HexRamp[2]),
        3 to const(TT.HexRamp[3]),
        4 to const(TT.HexRamp[4]),
    )

/** Crew identity: fixed slot order, folding past the eighth. */
private fun teamSlotColor(): Expression<ColorValue> =
    switch(
        input = feature.get("teamSlot"),
        cases = TT.Categorical.mapIndexed { i, c -> case(i, const(c)) },
        fallback = const(TT.InkMuted),
    )

private fun poiColor(): Expression<ColorValue> =
    switch(
        input = feature.get("poiKind"),
        cases = listOf(
            case("commercial", const(Color(0xFFB4529F))),
            case("transit", const(Color(0xFF5A4199))),
            case("school", const(Color(0xFF127A4F))),
            case("park", const(Color(0xFF127A4F))),
        ),
        fallback = const(TT.MapInk),
    )

private fun flagColor(): Expression<ColorValue> =
    switch(
        input = feature.get("flagKind"),
        cases = listOf(
            case("HAZARD", const(Color(0xFFC62828))),
            case("GUARDIAN", const(TT.TealDim)),
        ),
        fallback = const(Color(0xFFB4529F)),
    )
