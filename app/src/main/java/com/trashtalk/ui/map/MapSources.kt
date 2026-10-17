package com.trashtalk.ui.map

import com.trashtalk.core.LatLng
import com.trashtalk.core.Mercator
import com.trashtalk.ui.theme.TT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

/**
 * Turns [MapData] into the GeoJSON the renderer draws.
 *
 * Three sources, because they change on different clocks: segment geometry is
 * fixed for the life of the app, the hex surface is rebuilt whenever the
 * question or the scrubbed day changes, and markers move with live data.
 * Keeping them apart means a scrub does not re-upload three hundred polylines.
 *
 * **Features carry data, not style.** Every property here is a raw measurement
 * -- a density, a change, a count -- and the ramps that turn those into colour
 * live in the layer expressions (see MapLayers.kt). That split is what lets the
 * layer switcher recolour the whole map on the GPU without rebuilding or
 * re-uploading a single feature.
 */
object MapSources {

    const val SEGMENTS = "tt-segments"
    const val HEXES = "tt-hexes"
    const val MARKERS = "tt-markers"

    /** Ownership, flattened for the style: it depends on who is signed in. */
    const val OWNED_NONE = 0
    const val OWNED_ME = 1
    const val OWNED_OTHER = 2

    /**
     * The segment's id as an integer the renderer can use for feature state.
     *
     * MapLibre keys feature state by numeric id, and our ids are strings. No new
     * hash is needed: `generate_street_network.py` already builds them as
     * `s_` plus twelve hex characters of a SHA-1 over the sorted node pair, and
     * twelve hex characters is forty-eight bits, which fits a Long with room to
     * spare. So the integer id is the same identity the string id carries, read
     * in the base it was written in.
     *
     * Returns 0 for anything not in that shape, which the caller treats as "no
     * feature state" rather than silently colliding every odd segment onto one id.
     */
    fun featureId(segmentId: String): Long =
        segmentId.removePrefix("s_").toLongOrNull(16) ?: 0L

    /** The three collections, as the JSON the renderer wants. */
    data class Collections(
        val segments: String,
        val hexes: String,
        val markers: String,
    )

    /**
     * Builds every collection. Call off the main thread -- [buildAsync] does.
     */
    fun build(data: MapData, surface: HexBins.Surface): Collections = Collections(
        segments = segments(data),
        hexes = hexes(surface),
        markers = markers(data),
    )

    suspend fun buildAsync(data: MapData, surface: HexBins.Surface): Collections =
        withContext(Dispatchers.Default) { build(data, surface) }

    // ------------------------------------------------------------- segments

    fun segments(data: MapData): String {
        val features = JSONArray()
        for (seg in data.segments) {
            if (seg.path.size < 2) continue

            val owned = when {
                seg.guardianUserId == null -> OWNED_NONE
                seg.guardianUserId == data.currentUserId -> OWNED_ME
                else -> OWNED_OTHER
            }

            val props = JSONObject()
                .put("sid", seg.id)
                .put("name", seg.name)
                .put("weight", seg.streetClass.strokeWeight)
                .put("density", seg.litterIndex)
                .put("change", seg.sevenDayChange)
                .put("cleanups", seg.cleanupCount)
                .put("owned", owned)
                .put("surveyed", seg.surveyCount > 0)

            // The crew's categorical slot, not its colour. Crews are assigned
            // hues from TT.Categorical in fixed order, so the slot is the
            // stable identity and the style holds the palette -- which keeps
            // "fixed order, never cycled" visible in the style rather than
            // buried in whatever colour happened to be serialised.
            data.teamColors[seg.guardianTeamId]?.let {
                props.put("teamSlot", TT.Categorical.indexOf(it))
            }

            features.put(
                JSONObject()
                    .put("type", "Feature")
                    .put("id", featureId(seg.id))
                    .put("properties", props)
                    .put("geometry", lineString(seg.path)),
            )
        }
        return collection(features)
    }

    // ---------------------------------------------------------------- hexes

    fun hexes(surface: HexBins.Surface): String {
        val features = JSONArray()
        for (bin in surface.bins) {
            val ring = JSONArray()
            val corners = HexBins.corners(bin, surface.hexRadiusWorld)
            for ((wx, wy) in corners) {
                ring.put(position(Mercator.lat(wy), Mercator.lng(wx)))
            }
            // GeoJSON polygons must close: the last position repeats the first.
            ring.put(position(Mercator.lat(corners[0].second), Mercator.lng(corners[0].first)))

            features.put(
                JSONObject()
                    .put("type", "Feature")
                    .put("id", bin.featureId())
                    .put("properties", JSONObject()
                        // q and r rather than the packed id: these are small
                        // ints, so they survive the float round-trip that
                        // style expressions put every number through.
                        .put("q", bin.q)
                        .put("r", bin.r)
                        .put("cls", bin.classIndex)
                        .put("pieces", round(bin.pieces))
                        .put("segments", bin.segmentCount))
                    .put("geometry", JSONObject()
                        .put("type", "Polygon")
                        .put("coordinates", JSONArray().put(ring))),
            )
        }
        return collection(features)
    }

    // -------------------------------------------------------------- markers

    /**
     * Flags, generators and recent collections in one collection.
     *
     * One source rather than three: they are all small point sets drawn at the
     * same moment, and a single `kind` property lets each get its own layer
     * with a filter, which is cheaper than three uploads.
     */
    fun markers(data: MapData): String {
        val features = JSONArray()

        for (flag in data.flags) {
            features.put(
                point(flag.location, JSONObject()
                    .put("kind", "flag")
                    .put("flagKind", flag.kind.name)
                    .put("confirmed", flag.confirmed)),
            )
        }
        for (poi in data.pois) {
            features.put(
                point(poi.location, JSONObject()
                    .put("kind", "poi")
                    .put("poiKind", poi.kind)
                    .put("name", poi.name)),
            )
        }
        for (event in data.collections) {
            features.put(
                point(event.location, JSONObject()
                    .put("kind", "collection")
                    .put("pieces", event.counts.values.sum())),
            )
        }
        return collection(features)
    }

    // ----------------------------------------------------------- primitives

    private fun collection(features: JSONArray): String =
        JSONObject()
            .put("type", "FeatureCollection")
            .put("features", features)
            .toString()

    private fun point(at: LatLng, props: JSONObject): JSONObject =
        JSONObject()
            .put("type", "Feature")
            .put("properties", props)
            .put("geometry", JSONObject()
                .put("type", "Point")
                .put("coordinates", position(at.lat, at.lng)))

    private fun lineString(path: List<LatLng>): JSONObject {
        val coords = JSONArray()
        for (p in path) coords.put(position(p.lat, p.lng))
        return JSONObject().put("type", "LineString").put("coordinates", coords)
    }

    /**
     * A GeoJSON position, rounded to six decimals.
     *
     * Six decimals is about 11 cm, which is finer than any GPS fix this app
     * will ever see, and it keeps the serialised network to roughly half the
     * size that full double precision would produce.
     */
    private fun position(lat: Double, lng: Double): JSONArray =
        JSONArray().put(round(lng)).put(round(lat))

    private fun round(v: Double): Double = (v * 1e6).roundToLong() / 1e6
}

/** Stable integer id for a bin, from its axial coordinates. */
private fun HexBins.Bin.featureId(): Long =
    (q.toLong() and 0xFFFFFFFFL) shl 32 or (r.toLong() and 0xFFFFFFFFL)
