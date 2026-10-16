package com.trashtalk.data.demo

import android.content.Context
import com.trashtalk.core.LatLng
import com.trashtalk.data.model.CollectionEvent
import com.trashtalk.data.model.EarnedBadge
import com.trashtalk.data.model.FlagKind
import com.trashtalk.data.model.MapFlag
import com.trashtalk.data.model.TrashPath
import com.trashtalk.data.model.UserProfile
import com.trashtalk.data.model.UserStats
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the player's own progress for demo mode.
 *
 * Deliberately hand-rolled JSON over SharedPreferences rather than a schema-bound
 * serializer: the demo store has to tolerate being read back after the models
 * change during development, so every field is read with a default and an
 * unreadable blob is discarded rather than crashing the app on launch.
 */
internal class DemoStore(context: Context) {

    private val prefs = context.getSharedPreferences("trashtalk_demo", Context.MODE_PRIVATE)

    data class Snapshot(
        val profile: UserProfile = UserProfile(),
        val collections: List<CollectionEvent> = emptyList(),
        val overrides: Map<String, SegmentOverride> = emptyMap(),
        val flags: List<MapFlag> = emptyList(),
    )

    fun load(): Snapshot {
        val raw = prefs.getString(KEY, null) ?: return Snapshot()
        return runCatching { decode(JSONObject(raw)) }.getOrElse { Snapshot() }
    }

    fun save(snapshot: Snapshot) {
        val encoded = runCatching { encode(snapshot).toString() }.getOrNull() ?: return
        prefs.edit().putString(KEY, encoded).apply()
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    // ------------------------------------------------------------------ encode

    private fun encode(s: Snapshot): JSONObject = JSONObject().apply {
        put("v", VERSION)
        put("profile", encodeProfile(s.profile))
        put("collections", JSONArray().also { arr ->
            s.collections.forEach { arr.put(encodeCollection(it)) }
        })
        put("overrides", JSONObject().also { obj ->
            s.overrides.forEach { (id, o) -> obj.put(id, encodeOverride(o)) }
        })
        put("flags", JSONArray().also { arr -> s.flags.forEach { arr.put(encodeFlag(it)) } })
    }

    private fun encodeProfile(p: UserProfile) = JSONObject().apply {
        put("id", p.id)
        put("displayName", p.displayName)
        put("cityId", p.cityId)
        p.teamId?.let { put("teamId", it) }
        p.teamName?.let { put("teamName", it) }
        put("createdAt", p.createdAt)
        put("totalPoints", p.totalPoints)
        put("narrativeLevel", p.narrativeLevel)
        put("completedLevels", JSONArray(p.completedLevels.toList()))
        p.path?.let { put("path", it.id) }
        put("badgeTiers", JSONObject(p.badgeTiers.mapValues { it.value }))
        put("earned", JSONArray().also { arr ->
            p.earned.forEach { e ->
                arr.put(
                    JSONObject().apply {
                        put("badgeId", e.badgeId); put("tier", e.tier); put("earnedAt", e.earnedAt)
                    },
                )
            }
        })
        put("streakDays", p.streakDays)
        put("lastCollectionDay", p.lastCollectionDay)
        put("referralCode", p.referralCode)
        put("stats", encodeStats(p.stats))
    }

    private fun encodeStats(s: UserStats) = JSONObject().apply {
        put("pieces", s.pieces)
        put("collections", s.collections)
        put("grams", s.grams)
        put("minutes", s.minutes)
        put("segmentsTouched", JSONArray(s.segmentsTouched.toList()))
        put("hotspotsConfirmed", s.hotspotsConfirmed)
        put("flagsPlanted", s.flagsPlanted)
        put("segmentsFirstSurveyed", s.segmentsFirstSurveyed)
        put("imagesLabelled", s.imagesLabelled)
        put("peerResurveys", s.peerResurveys)
        put("cityTop20Weeks", s.cityTop20Weeks)
        put("teamLeadWeeks", s.teamLeadWeeks)
        put("guardianWeeksHeld", s.guardianWeeksHeld)
        put("weekendRecords", s.weekendRecords)
        put("bestHourPieces", s.bestHourPieces)
        put("shares", s.shares)
        put("referrals", s.referrals)
        put("triviaWeeks", s.triviaWeeks)
        put("motivationMessages", s.motivationMessages)
        put("invitesSent", s.invitesSent)
        put("bestDaySegments", s.bestDaySegments)
        put("personalBestHour", s.personalBestHour)
        put("longestStreakDays", s.longestStreakDays)
        put("teamStreakCrownDays", s.teamStreakCrownDays)
        put("remindersAnswered", s.remindersAnswered)
    }

    private fun encodeCollection(c: CollectionEvent) = JSONObject().apply {
        put("id", c.id)
        put("userId", c.userId)
        put("userName", c.userName)
        c.teamId?.let { put("teamId", it) }
        put("segmentId", c.segmentId)
        put("segmentName", c.segmentName)
        put("lat", c.location.lat)
        put("lng", c.location.lng)
        put("counts", JSONObject(c.counts.toJsonMap().mapValues { it.value }))
        c.photoUri?.let { put("photoUri", it) }
        put("notes", c.notes)
        put("createdAt", c.createdAt)
        put("points", c.points)
        put("wasFirstSurvey", c.wasFirstSurvey)
        put("minutes", c.minutes)
    }

    private fun encodeOverride(o: SegmentOverride) = JSONObject().apply {
        o.litterIndex?.let { put("litterIndex", it.toDouble()) }
        put("surveyCountDelta", o.surveyCountDelta)
        put("cleanupDelta", o.cleanupDelta)
        put("piecesRemoved", o.piecesRemoved)
        o.lastSurveyedAt?.let { put("lastSurveyedAt", it) }
        o.guardianUserId?.let { put("guardianUserId", it) }
        o.guardianName?.let { put("guardianName", it) }
        o.guardianSince?.let { put("guardianSince", it) }
    }

    private fun encodeFlag(f: MapFlag) = JSONObject().apply {
        put("id", f.id)
        put("userId", f.userId)
        put("userName", f.userName)
        put("kind", f.kind.name)
        put("lat", f.location.lat)
        put("lng", f.location.lng)
        f.segmentId?.let { put("segmentId", it) }
        put("note", f.note)
        put("createdAt", f.createdAt)
        put("confirmed", f.confirmed)
        put("anonymous", f.anonymous)
    }

    // ------------------------------------------------------------------ decode

    private fun decode(root: JSONObject): Snapshot {
        val profile = root.optJSONObject("profile")?.let(::decodeProfile) ?: UserProfile()

        val collections = root.optJSONArray("collections").orEmptyArray().mapJson { decodeCollection(it) }

        val overridesObj = root.optJSONObject("overrides")
        val overrides = mutableMapOf<String, SegmentOverride>()
        overridesObj?.keys()?.forEach { key ->
            overridesObj.optJSONObject(key)?.let { overrides[key] = decodeOverride(it) }
        }

        val flags = root.optJSONArray("flags").orEmptyArray().mapJson { decodeFlag(it) }

        return Snapshot(profile, collections, overrides, flags)
    }

    private fun decodeProfile(o: JSONObject): UserProfile {
        val levels = mutableSetOf<Int>()
        o.optJSONArray("completedLevels")?.let { arr ->
            for (i in 0 until arr.length()) levels += arr.optInt(i)
        }
        val tiers = mutableMapOf<String, Int>()
        o.optJSONObject("badgeTiers")?.let { obj ->
            obj.keys().forEach { k -> tiers[k] = obj.optInt(k) }
        }
        val earned = o.optJSONArray("earned").orEmptyArray().mapJson {
            EarnedBadge(
                badgeId = it.optString("badgeId"),
                tier = it.optInt("tier"),
                earnedAt = it.optLong("earnedAt"),
            )
        }
        return UserProfile(
            id = o.optString("id", "local_player"),
            displayName = o.optString("displayName", "You"),
            cityId = o.optString("cityId", "san-antonio-tx"),
            teamId = o.optStringOrNull("teamId"),
            teamName = o.optStringOrNull("teamName"),
            createdAt = o.optLong("createdAt"),
            totalPoints = o.optInt("totalPoints"),
            narrativeLevel = o.optInt("narrativeLevel"),
            completedLevels = levels,
            path = TrashPath.from(o.optStringOrNull("path")),
            badgeTiers = tiers,
            earned = earned,
            stats = o.optJSONObject("stats")?.let(::decodeStats) ?: UserStats(),
            streakDays = o.optInt("streakDays"),
            lastCollectionDay = o.optLong("lastCollectionDay"),
            referralCode = o.optString("referralCode", ""),
        )
    }

    private fun decodeStats(o: JSONObject): UserStats {
        val touched = mutableSetOf<String>()
        o.optJSONArray("segmentsTouched")?.let { arr ->
            for (i in 0 until arr.length()) touched += arr.optString(i)
        }
        return UserStats(
            pieces = o.optInt("pieces"),
            collections = o.optInt("collections"),
            grams = o.optDouble("grams", 0.0),
            minutes = o.optInt("minutes"),
            segmentsTouched = touched,
            hotspotsConfirmed = o.optInt("hotspotsConfirmed"),
            flagsPlanted = o.optInt("flagsPlanted"),
            segmentsFirstSurveyed = o.optInt("segmentsFirstSurveyed"),
            imagesLabelled = o.optInt("imagesLabelled"),
            peerResurveys = o.optInt("peerResurveys"),
            cityTop20Weeks = o.optInt("cityTop20Weeks"),
            teamLeadWeeks = o.optInt("teamLeadWeeks"),
            guardianWeeksHeld = o.optInt("guardianWeeksHeld"),
            weekendRecords = o.optInt("weekendRecords"),
            bestHourPieces = o.optInt("bestHourPieces"),
            shares = o.optInt("shares"),
            referrals = o.optInt("referrals"),
            triviaWeeks = o.optInt("triviaWeeks"),
            motivationMessages = o.optInt("motivationMessages"),
            invitesSent = o.optInt("invitesSent"),
            bestDaySegments = o.optInt("bestDaySegments"),
            personalBestHour = o.optInt("personalBestHour"),
            longestStreakDays = o.optInt("longestStreakDays"),
            teamStreakCrownDays = o.optInt("teamStreakCrownDays"),
            remindersAnswered = o.optInt("remindersAnswered"),
        )
    }

    private fun decodeCollection(o: JSONObject): CollectionEvent {
        val counts = mutableMapOf<String, Int>()
        o.optJSONObject("counts")?.let { obj ->
            obj.keys().forEach { k -> counts[k] = obj.optInt(k) }
        }
        return CollectionEvent(
            id = o.optString("id"),
            userId = o.optString("userId"),
            userName = o.optString("userName", "You"),
            teamId = o.optStringOrNull("teamId"),
            segmentId = o.optString("segmentId"),
            segmentName = o.optString("segmentName"),
            location = LatLng(o.optDouble("lat", 0.0), o.optDouble("lng", 0.0)),
            counts = counts.toLitterMap(),
            photoUri = o.optStringOrNull("photoUri"),
            notes = o.optString("notes", ""),
            createdAt = o.optLong("createdAt"),
            points = o.optInt("points"),
            wasFirstSurvey = o.optBoolean("wasFirstSurvey"),
            minutes = o.optInt("minutes"),
        )
    }

    private fun decodeOverride(o: JSONObject) = SegmentOverride(
        litterIndex = if (o.has("litterIndex")) o.optDouble("litterIndex").toFloat() else null,
        surveyCountDelta = o.optInt("surveyCountDelta"),
        cleanupDelta = o.optInt("cleanupDelta"),
        piecesRemoved = o.optInt("piecesRemoved"),
        lastSurveyedAt = if (o.has("lastSurveyedAt")) o.optLong("lastSurveyedAt") else null,
        guardianUserId = o.optStringOrNull("guardianUserId"),
        guardianName = o.optStringOrNull("guardianName"),
        guardianSince = if (o.has("guardianSince")) o.optLong("guardianSince") else null,
    )

    private fun decodeFlag(o: JSONObject) = MapFlag(
        id = o.optString("id"),
        userId = o.optString("userId"),
        userName = o.optString("userName"),
        kind = runCatching { FlagKind.valueOf(o.optString("kind")) }
            .getOrDefault(FlagKind.HOTSPOT_GUESS),
        location = LatLng(o.optDouble("lat", 0.0), o.optDouble("lng", 0.0)),
        segmentId = o.optStringOrNull("segmentId"),
        note = o.optString("note", ""),
        createdAt = o.optLong("createdAt"),
        confirmed = o.optBoolean("confirmed"),
        anonymous = o.optBoolean("anonymous"),
    )

    private companion object {
        const val KEY = "snapshot"
        const val VERSION = 1
    }
}

private fun JSONArray?.orEmptyArray(): JSONArray = this ?: JSONArray()

private fun <T> JSONArray.mapJson(transform: (JSONObject) -> T): List<T> =
    (0 until length()).mapNotNull { i -> optJSONObject(i)?.let(transform) }

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null
