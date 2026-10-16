package com.trashtalk.data

import android.content.Context
import com.trashtalk.core.LatLng
import com.trashtalk.core.LatLngBounds
import com.trashtalk.data.model.Poi
import com.trashtalk.data.model.Segment
import com.trashtalk.data.model.StreetClass
import org.json.JSONObject

/** The bundled survey network for one neighbourhood. */
data class NeighborhoodNetwork(
    val neighborhood: String,
    val city: String,
    val state: String,
    val cityId: String,
    val historyDays: Int,
    val bounds: LatLngBounds,
    val note: String,
    val segments: List<Segment>,
    val pois: List<Poi>,
) {
    val label: String get() = "$neighborhood, $city $state"
}

/**
 * Reads `assets/terrell_heights.json`, produced by
 * `tools/generate_street_network.py`.
 *
 * The network ships with the APK rather than being fetched, for three reasons: the
 * map must draw something the instant the app opens, a survey network is the one
 * piece of data that does not change day to day, and a field app whose basemap
 * depends on connectivity is useless in exactly the places volunteers walk.
 * Firestore then holds the *observations* layered on top of it.
 */
object NetworkAssetLoader {

    const val ASSET_NAME = "terrell_heights.json"

    fun load(context: Context): NeighborhoodNetwork {
        val raw = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        return parse(raw)
    }

    fun parse(raw: String): NeighborhoodNetwork {
        val root = JSONObject(raw)
        val meta = root.getJSONObject("meta")
        val b = meta.getJSONObject("bounds")

        val segmentsJson = root.getJSONArray("segments")
        val segments = ArrayList<Segment>(segmentsJson.length())
        for (i in 0 until segmentsJson.length()) {
            val s = segmentsJson.getJSONObject(i)

            val coords = s.getJSONArray("coords")
            val path = ArrayList<LatLng>(coords.length())
            for (j in 0 until coords.length()) {
                val pair = coords.getJSONArray(j)
                path += LatLng(pair.getDouble(0), pair.getDouble(1))
            }

            val historyJson = s.optJSONArray("history")
            val history = ArrayList<Float>(historyJson?.length() ?: 0)
            if (historyJson != null) {
                for (j in 0 until historyJson.length()) {
                    history += historyJson.getDouble(j).toFloat()
                }
            }

            val compJson = s.optJSONObject("composition")
            val composition = mutableMapOf<String, Float>()
            compJson?.keys()?.forEach { key ->
                composition[key] = compJson.getDouble(key).toFloat()
            }

            segments += Segment(
                id = s.getString("id"),
                name = s.getString("name"),
                streetClass = StreetClass.from(s.optString("cls")),
                path = path,
                litterIndex = s.getDouble("litterIndex").toFloat(),
                propensity = s.optDouble("propensity", 0.0).toFloat(),
                lengthMeters = s.optDouble("lengthM", 0.0),
                estimatedPieces = s.optInt("estimatedPieces"),
                surveyCount = s.optInt("surveyCount"),
                cleanupCount = s.optInt("cleanups"),
                history = history,
                composition = composition,
            )
        }

        val poisJson = root.optJSONArray("pois")
        val pois = ArrayList<Poi>(poisJson?.length() ?: 0)
        if (poisJson != null) {
            for (i in 0 until poisJson.length()) {
                val p = poisJson.getJSONObject(i)
                pois += Poi(
                    id = p.getString("id"),
                    name = p.getString("name"),
                    kind = p.getString("kind"),
                    location = LatLng(p.getDouble("lat"), p.getDouble("lng")),
                )
            }
        }

        return NeighborhoodNetwork(
            neighborhood = meta.optString("neighborhood", "Unnamed"),
            city = meta.optString("city", ""),
            state = meta.optString("state", ""),
            cityId = meta.optString("cityId", "unknown"),
            historyDays = meta.optInt("historyDays", 14),
            bounds = LatLngBounds(
                minLat = b.getDouble("minLat"),
                minLng = b.getDouble("minLng"),
                maxLat = b.getDouble("maxLat"),
                maxLng = b.getDouble("maxLng"),
            ),
            note = meta.optString("note", ""),
            segments = segments,
            pois = pois,
        )
    }
}
