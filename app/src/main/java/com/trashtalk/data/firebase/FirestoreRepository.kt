package com.trashtalk.data.firebase

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.persistentCacheSettings
import com.trashtalk.core.LatLng
import com.trashtalk.data.AdvocacyAction
import com.trashtalk.data.NeighborhoodNetwork
import com.trashtalk.data.TrashTalkRepository
import com.trashtalk.data.content.LevelContent
import com.trashtalk.data.model.BackendMode
import com.trashtalk.data.model.CollectionEvent
import com.trashtalk.data.model.EarnedBadge
import com.trashtalk.data.model.FlagKind
import com.trashtalk.data.model.LeaderboardEntry
import com.trashtalk.data.model.LeaderboardScope
import com.trashtalk.data.model.LitterType
import com.trashtalk.data.model.MapFlag
import com.trashtalk.data.model.Segment
import com.trashtalk.data.model.Team
import com.trashtalk.data.model.TrashPath
import com.trashtalk.data.model.UserProfile
import com.trashtalk.data.model.UserStats
import com.trashtalk.domain.BadgeEngine
import com.trashtalk.domain.CollectionDraft
import com.trashtalk.domain.ProgressDelta
import com.trashtalk.domain.ScoreBreakdown
import com.trashtalk.domain.ScoreEngine
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/**
 * The live backend: Cloud Firestore plus anonymous Firebase Auth.
 *
 * One structural decision worth stating, because it shapes every read here:
 * **Firestore stores observations, not geometry.** The 184 segment polylines ship
 * in the APK and never change, so uploading them would cost a document read per
 * segment per launch to transfer data the client already has. Instead each
 * segment has a small `segment_state` document holding only what people have
 * changed -- current litter index, survey and cleanup counts, guardian -- which
 * the client merges onto the bundled geometry. Launch cost stays flat as the
 * network grows, and the map still draws instantly offline.
 *
 * Firestore's persistent cache is enabled, so a volunteer who walks out of
 * coverage keeps reading and writing; the SDK reconciles when signal returns.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FirestoreRepository(
    override val network: NeighborhoodNetwork,
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore,
) : TrashTalkRepository {

    override val mode = BackendMode.LIVE

    private val uid = MutableStateFlow<String?>(auth.currentUser?.uid)

    init {
        runCatching {
            db.firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(persistentCacheSettings {})
                .build()
        }.onFailure { Log.w(TAG, "could not enable the offline cache", it) }
    }

    /**
     * The signed-in uid as a flow, so reads can wait for identity.
     *
     * Firestore listeners attached before anonymous sign-in completes are
     * rejected with PERMISSION_DENIED, and a rejected listener is *dead* -- the
     * SDK does not retry it when auth later arrives. Gating every read on this
     * flow is what stops a cold start from permanently showing empty screens.
     */
    private fun authUid(): Flow<String?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }.distinctUntilChanged()

    // ---------------------------------------------------------------- reads

    /**
     * Segment state merged onto bundled geometry. Emits the bundled network first
     * so the map has something to draw before the first snapshot lands.
     */
    override fun observeSegments(): Flow<List<Segment>> = authUid().flatMapLatest { uid ->
        if (uid == null) return@flatMapLatest flowOf(network.segments)
        callbackFlow {
        trySend(network.segments)
        val reg = db.collection(SEGMENT_STATE).addSnapshotListener { snap, err ->
            if (err != null) {
                Log.w(TAG, "segment_state listener failed", err)
                return@addSnapshotListener
            }
            val states = snap?.documents?.associateBy { it.id } ?: emptyMap()
            trySend(
                network.segments.map { base ->
                    val d = states[base.id] ?: return@map base
                    base.copy(
                        litterIndex = (d.getDouble("litterIndex") ?: base.litterIndex.toDouble())
                            .toFloat().coerceIn(0f, 1f),
                        surveyCount = (d.getLong("surveyCount") ?: base.surveyCount.toLong()).toInt(),
                        cleanupCount = (d.getLong("cleanupCount") ?: base.cleanupCount.toLong()).toInt(),
                        piecesRemoved = (d.getLong("piecesRemoved") ?: 0L).toInt(),
                        lastSurveyedAt = d.getLong("lastSurveyedAt") ?: 0L,
                        guardianUserId = d.getString("guardianUserId"),
                        guardianName = d.getString("guardianName"),
                        guardianSince = d.getLong("guardianSince") ?: 0L,
                    )
                },
            )
        }
        awaitClose { reg.remove() }
        }
    }

    override fun observeProfile(): Flow<UserProfile> = callbackFlow {
        var reg: com.google.firebase.firestore.ListenerRegistration? = null
        val authListener = FirebaseAuth.AuthStateListener { a ->
            reg?.remove()
            val id = a.currentUser?.uid
            uid.value = id
            if (id == null) {
                trySend(UserProfile())
                return@AuthStateListener
            }
            reg = db.collection(USERS).document(id).addSnapshotListener { doc, err ->
                if (err != null) {
                    Log.w(TAG, "profile listener failed", err)
                    return@addSnapshotListener
                }
                trySend(doc?.let { decodeProfile(id, it.data) } ?: UserProfile(id = id))
            }
        }
        auth.addAuthStateListener(authListener)
        awaitClose {
            reg?.remove()
            auth.removeAuthStateListener(authListener)
        }
    }

    override fun observeCollections(limit: Int): Flow<List<CollectionEvent>> =
        authUid().flatMapLatest { uid ->
        if (uid == null) return@flatMapLatest flowOf(emptyList())
        callbackFlow {
        val reg = db.collection(COLLECTIONS)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(limit.toLong())
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.w(TAG, "collections listener failed", err)
                    return@addSnapshotListener
                }
                trySend(snap?.documents?.map { decodeCollection(it.id, it.data.orEmpty()) }.orEmpty())
            }
        awaitClose { reg.remove() }
        }
    }

    override fun observeTeams(): Flow<List<Team>> = authUid().flatMapLatest { uid ->
        if (uid == null) return@flatMapLatest flowOf(emptyList())
        callbackFlow {
        val reg = db.collection(TEAMS).addSnapshotListener { snap, err ->
            if (err != null) {
                Log.w(TAG, "teams listener failed", err)
                return@addSnapshotListener
            }
            trySend(
                snap?.documents?.map { d ->
                    Team(
                        id = d.id,
                        name = d.getString("name") ?: d.id,
                        cityId = d.getString("cityId") ?: network.cityId,
                        blurb = d.getString("blurb") ?: "",
                        memberCount = (d.getLong("memberCount") ?: 0L).toInt(),
                        weeklyPoints = (d.getLong("weeklyPoints") ?: 0L).toInt(),
                        totalPieces = (d.getLong("totalPieces") ?: 0L).toInt(),
                        totalPoints = (d.getLong("totalPoints") ?: 0L).toInt(),
                    )
                }.orEmpty(),
            )
        }
        awaitClose { reg.remove() }
        }
    }

    override fun observeFlags(): Flow<List<MapFlag>> = authUid().flatMapLatest { uid ->
        if (uid == null) return@flatMapLatest flowOf(emptyList())
        callbackFlow {
        val reg = db.collection(FLAGS)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(400)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.w(TAG, "flags listener failed", err)
                    return@addSnapshotListener
                }
                trySend(
                    snap?.documents?.map { d ->
                        MapFlag(
                            id = d.id,
                            userId = d.getString("userId") ?: "",
                            userName = d.getString("userName") ?: "Anonymous",
                            kind = runCatching { FlagKind.valueOf(d.getString("kind") ?: "") }
                                .getOrDefault(FlagKind.HOTSPOT_GUESS),
                            location = LatLng(
                                d.getDouble("lat") ?: 0.0,
                                d.getDouble("lng") ?: 0.0,
                            ),
                            segmentId = d.getString("segmentId"),
                            note = d.getString("note") ?: "",
                            createdAt = d.getLong("createdAt") ?: 0L,
                            confirmed = d.getBoolean("confirmed") ?: false,
                            anonymous = d.getBoolean("anonymous") ?: false,
                        )
                    }.orEmpty(),
                )
            }
        awaitClose { reg.remove() }
        }
    }

    /**
     * Leaderboards read from the users collection, ordered server-side. Weekly
     * boards use a denormalised `weeklyPoints` field that a scheduled function
     * resets on Mondays -- see docs/FIREBASE_SETUP.md. Until that function is
     * deployed the weekly figures simply track all-time, which is wrong but
     * harmless, and visibly so.
     */
    override fun observeLeaderboard(scope: LeaderboardScope): Flow<List<LeaderboardEntry>> {
        val field = if (scope == LeaderboardScope.ALL_TIME) "totalPoints" else "weeklyPoints"
        return authUid().flatMapLatest { uid ->
            if (uid == null) return@flatMapLatest flowOf(emptyList<Pair<String, Map<String, Any?>>>())
            callbackFlow {
            val reg = db.collection(USERS)
                .orderBy(field, Query.Direction.DESCENDING)
                .limit(100)
                .addSnapshotListener { snap, err ->
                    if (err != null) {
                        Log.w(TAG, "leaderboard listener failed", err)
                        return@addSnapshotListener
                    }
                    trySend(snap?.documents.orEmpty().map { d -> d.id to d.data.orEmpty() })
                }
            awaitClose { reg.remove() }
            }
        }.combine(observeProfile()) { rows, me ->
            rows
                .filter { (_, data) ->
                    scope != LeaderboardScope.TEAM_WEEK ||
                        (me.teamId != null && data["teamId"] == me.teamId)
                }
                .map { (id, data) ->
                    val path = TrashPath.from(data["path"] as? String)
                    LeaderboardEntry(
                        id = id,
                        name = data["displayName"] as? String ?: "Collector",
                        subtitle = data["teamName"] as? String ?: "No crew",
                        points = (data[field] as? Number)?.toInt() ?: 0,
                        pieces = ((data["stats"] as? Map<*, *>)?.get("pieces") as? Number)?.toInt() ?: 0,
                        isCurrentUser = id == me.id,
                        pathLabel = path?.label,
                        level = (data["narrativeLevel"] as? Number)?.toInt()?.plus(1) ?: 1,
                    )
                }
                .sortedByDescending { it.points }
                .mapIndexed { i, e -> e.copy(rank = i + 1) }
        }
    }

    // --------------------------------------------------------------- writes

    override suspend fun ensureSignedIn() {
        auth.currentUser?.let {
            uid.value = it.uid
            ensureProfileDocument(it.uid)
            return
        }
        val result = auth.signInAnonymously().await()
        val id = result.user?.uid ?: error("anonymous sign-in returned no user")
        uid.value = id
        ensureProfileDocument(id)
    }

    private suspend fun ensureProfileDocument(id: String) {
        val doc = db.collection(USERS).document(id)
        val snap = doc.get().await()
        if (snap.exists()) return
        doc.set(
            mapOf(
                "displayName" to "Collector",
                "cityId" to network.cityId,
                "createdAt" to System.currentTimeMillis(),
                "totalPoints" to 0,
                "weeklyPoints" to 0,
                "narrativeLevel" to 0,
                "completedLevels" to emptyList<Int>(),
                "badgeTiers" to emptyMap<String, Int>(),
                "streakDays" to 0,
                "referralCode" to "TT-" + (100000..999999).random(),
                "stats" to emptyMap<String, Any>(),
            ),
            SetOptions.merge(),
        ).await()
    }

    override suspend fun submitCollection(draft: CollectionDraft): ProgressDelta {
        val id = requireUid()
        val now = System.currentTimeMillis()
        val profile = loadProfile(id)
        val segment = currentSegment(draft.segmentId)
        val breakdown = ScoreEngine.score(draft, segment, profile)
        val wasFirstSurvey = segment != null && !segment.hasBeenSurveyed

        db.collection(COLLECTIONS).add(
            mapOf(
                "userId" to id,
                "userName" to profile.displayName,
                "teamId" to profile.teamId,
                "segmentId" to draft.segmentId,
                "segmentName" to draft.segmentName,
                "lat" to draft.lat,
                "lng" to draft.lng,
                "counts" to draft.counts.mapKeys { it.key.name },
                "photoUri" to draft.photoUri,
                "notes" to draft.notes,
                "createdAt" to now,
                "points" to breakdown.total,
                "wasFirstSurvey" to wasFirstSurvey,
                "minutes" to draft.minutes,
            ),
        ).await()

        // The segment's standing density falls in proportion to the share of its
        // estimated load that was actually removed. A transaction keeps two
        // volunteers working the same block from clobbering each other.
        val stateDoc = db.collection(SEGMENT_STATE).document(draft.segmentId)
        val baseIndex = segment?.litterIndex ?: 0f
        val load = (segment?.estimatedPieces ?: 1).coerceAtLeast(1)
        db.runTransaction { tx ->
            val snap = tx.get(stateDoc)
            val current = (snap.getDouble("litterIndex") ?: baseIndex.toDouble()).toFloat()
            val cleared = (draft.pieces.toFloat() / load).coerceIn(0f, 1f)
            val next = (current * (1f - 0.75f * cleared)).coerceIn(0f, 1f)
            tx.set(
                stateDoc,
                mapOf(
                    "litterIndex" to next.toDouble(),
                    "surveyCount" to FieldValue.increment(1),
                    "cleanupCount" to FieldValue.increment(1),
                    "piecesRemoved" to FieldValue.increment(draft.pieces.toLong()),
                    "lastSurveyedAt" to now,
                ),
                SetOptions.merge(),
            )
            null
        }.await()

        val streak = ScoreEngine.nextStreak(profile, now)
        val stats = recomputeStats(profile.stats, id, now, draft, wasFirstSurvey)
        val updated = profile.copy(
            totalPoints = profile.totalPoints + breakdown.total,
            streakDays = streak,
            lastCollectionDay = ScoreEngine.dayIndex(now),
        )
        return commit(updated, stats, now, breakdown.total, breakdown, streak > profile.streakDays, wasFirstSurvey)
    }

    override suspend fun plantFlag(
        kind: FlagKind,
        lat: Double,
        lng: Double,
        segmentId: String?,
        note: String,
        anonymous: Boolean,
    ): ProgressDelta {
        val id = requireUid()
        val now = System.currentTimeMillis()
        val profile = loadProfile(id)
        val segment = segmentId?.let { currentSegment(it) }
        val confirmed = kind == FlagKind.HOTSPOT_GUESS && (segment?.litterIndex ?: 0f) >= 0.45f

        db.collection(FLAGS).add(
            mapOf(
                "userId" to id,
                "userName" to if (anonymous) "Anonymous" else profile.displayName,
                "kind" to kind.name,
                "lat" to lat,
                "lng" to lng,
                "segmentId" to segmentId,
                "note" to note,
                "createdAt" to now,
                "confirmed" to confirmed,
                "anonymous" to anonymous,
            ),
        ).await()

        val stats = profile.stats.copy(
            flagsPlanted = profile.stats.flagsPlanted + 1,
            hotspotsConfirmed = profile.stats.hotspotsConfirmed + if (confirmed) 1 else 0,
        )
        val award = if (confirmed) 60 else 15
        return commit(profile.copy(totalPoints = profile.totalPoints + award), stats, now, award)
    }

    override suspend fun setGuardian(
        segmentId: String,
        adopt: Boolean,
        anonymous: Boolean,
    ): ProgressDelta {
        val id = requireUid()
        val now = System.currentTimeMillis()
        val profile = loadProfile(id)
        db.collection(SEGMENT_STATE).document(segmentId).set(
            mapOf(
                "guardianUserId" to if (adopt) id else null,
                "guardianName" to if (adopt) {
                    if (anonymous) "Anonymous" else profile.displayName
                } else null,
                "guardianSince" to if (adopt) now else null,
            ),
            SetOptions.merge(),
        ).await()
        val award = if (adopt) 40 else 0
        return commit(profile.copy(totalPoints = profile.totalPoints + award), profile.stats, now, award)
    }

    override suspend fun completeLevel(levelIndex: Int): ProgressDelta {
        val id = requireUid()
        val now = System.currentTimeMillis()
        val profile = loadProfile(id)
        if (levelIndex in profile.completedLevels) return ProgressDelta(streakDays = profile.streakDays)
        val award = LevelContent.level(levelIndex)?.pointsAward ?: 120
        val updated = profile.copy(
            completedLevels = profile.completedLevels + levelIndex,
            narrativeLevel = maxOf(profile.narrativeLevel, levelIndex),
            totalPoints = profile.totalPoints + award,
        )
        return commit(updated, profile.stats, now, award)
    }

    override suspend fun choosePath(path: TrashPath): ProgressDelta {
        val id = requireUid()
        val profile = loadProfile(id)
        return commit(profile.copy(path = path), profile.stats, System.currentTimeMillis(), 0)
    }

    override suspend fun joinTeam(teamId: String?): ProgressDelta {
        val id = requireUid()
        val profile = loadProfile(id)
        val team = teamId?.let { db.collection(TEAMS).document(it).get().await() }
        if (profile.teamId != null && profile.teamId != teamId) {
            db.collection(TEAMS).document(profile.teamId!!)
                .set(mapOf("memberCount" to FieldValue.increment(-1)), SetOptions.merge()).await()
        }
        if (teamId != null && profile.teamId != teamId) {
            db.collection(TEAMS).document(teamId)
                .set(mapOf("memberCount" to FieldValue.increment(1)), SetOptions.merge()).await()
        }
        return commit(
            profile.copy(teamId = teamId, teamName = team?.getString("name")),
            profile.stats,
            System.currentTimeMillis(),
            0,
        )
    }

    override suspend fun setDisplayName(name: String) {
        val id = requireUid()
        db.collection(USERS).document(id)
            .set(mapOf("displayName" to name.trim().ifBlank { "Collector" }), SetOptions.merge())
            .await()
    }

    override suspend fun recordAdvocacy(action: AdvocacyAction, amount: Int): ProgressDelta {
        val id = requireUid()
        val now = System.currentTimeMillis()
        val profile = loadProfile(id)
        val s = profile.stats
        val stats = when (action) {
            AdvocacyAction.SHARE_STATSHOT -> s.copy(shares = s.shares + amount)
            AdvocacyAction.SEND_INVITE -> s.copy(invitesSent = s.invitesSent + amount)
            AdvocacyAction.LOG_REFERRAL -> s.copy(referrals = s.referrals + amount)
            AdvocacyAction.TEAM_MESSAGE -> s.copy(motivationMessages = s.motivationMessages + amount)
            AdvocacyAction.FINISH_TRIVIA -> s.copy(triviaWeeks = s.triviaWeeks + amount)
            AdvocacyAction.LABEL_IMAGES -> s.copy(imagesLabelled = s.imagesLabelled + amount)
            AdvocacyAction.PEER_RESURVEY -> s.copy(peerResurveys = s.peerResurveys + amount)
            AdvocacyAction.ANSWER_REMINDER -> s.copy(remindersAnswered = s.remindersAnswered + amount)
        }
        val award = when (action) {
            AdvocacyAction.LOG_REFERRAL -> 150
            AdvocacyAction.FINISH_TRIVIA -> 80
            AdvocacyAction.LABEL_IMAGES -> 2 * amount
            AdvocacyAction.PEER_RESURVEY -> 45
            else -> 20
        }
        return commit(profile.copy(totalPoints = profile.totalPoints + award), stats, now, award)
    }

    /** Live progress belongs to the account, so this is intentionally a no-op. */
    override suspend fun resetProgress() = Unit

    // ------------------------------------------------------------ internals

    private fun requireUid(): String =
        uid.value ?: auth.currentUser?.uid ?: error("not signed in")

    private suspend fun currentSegment(segmentId: String): Segment? {
        val base = network.segments.firstOrNull { it.id == segmentId } ?: return null
        val snap = runCatching {
            db.collection(SEGMENT_STATE).document(segmentId).get().await()
        }.getOrNull() ?: return base
        if (!snap.exists()) return base
        return base.copy(
            litterIndex = (snap.getDouble("litterIndex") ?: base.litterIndex.toDouble())
                .toFloat().coerceIn(0f, 1f),
            surveyCount = (snap.getLong("surveyCount") ?: base.surveyCount.toLong()).toInt(),
            cleanupCount = (snap.getLong("cleanupCount") ?: base.cleanupCount.toLong()).toInt(),
            guardianUserId = snap.getString("guardianUserId"),
            guardianName = snap.getString("guardianName"),
        )
    }

    private suspend fun loadProfile(id: String): UserProfile {
        val snap = db.collection(USERS).document(id).get().await()
        return decodeProfile(id, snap.data)
    }

    /**
     * Derives the log-backed metrics by querying this user's own collections.
     * Costs one indexed query per submission, which buys stats that cannot drift
     * away from the visible history.
     */
    private suspend fun recomputeStats(
        current: UserStats,
        userId: String,
        now: Long,
        justSubmitted: CollectionDraft,
        wasFirstSurvey: Boolean,
    ): UserStats {
        val mine = runCatching {
            db.collection(COLLECTIONS)
                .whereEqualTo("userId", userId)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(500)
                .get()
                .await()
                .documents
                .map { decodeCollection(it.id, it.data.orEmpty()) }
        }.getOrElse {
            Log.w(TAG, "stats query failed; falling back to incremental counters", it)
            return current.copy(
                pieces = current.pieces + justSubmitted.pieces,
                collections = current.collections + 1,
                minutes = current.minutes + justSubmitted.minutes,
                segmentsTouched = current.segmentsTouched + justSubmitted.segmentId,
                segmentsFirstSurveyed = current.segmentsFirstSurveyed + if (wasFirstSurvey) 1 else 0,
            )
        }

        val byDay = mine.groupBy { ScoreEngine.dayIndex(it.createdAt) }
        var longest = 0
        var run = 0
        var prev: Long? = null
        for (d in byDay.keys.sorted()) {
            run = if (prev != null && d == prev + 1) run + 1 else 1
            longest = maxOf(longest, run)
            prev = d
        }
        val bestHour = mine.maxOfOrNull { c ->
            if (c.minutes > 0) (c.pieces * 60.0 / c.minutes).roundToInt() else c.pieces
        } ?: 0

        return current.copy(
            pieces = mine.sumOf { it.pieces },
            collections = mine.size,
            grams = mine.sumOf { it.grams },
            minutes = mine.sumOf { it.minutes },
            segmentsTouched = mine.map { it.segmentId }.toSet(),
            segmentsFirstSurveyed = mine.count { it.wasFirstSurvey },
            bestDaySegments = byDay.values.maxOfOrNull { day ->
                day.map { it.segmentId }.distinct().size
            } ?: 0,
            personalBestHour = bestHour,
            bestHourPieces = bestHour,
            longestStreakDays = maxOf(longest, current.longestStreakDays),
        )
    }

    private suspend fun commit(
        profile: UserProfile,
        stats: UserStats,
        now: Long,
        pointsAwarded: Int,
        breakdown: ScoreBreakdown? = null,
        streakExtended: Boolean = false,
        wasFirstSurvey: Boolean = false,
    ): ProgressDelta {
        val levelBefore = profile.path?.let { profile.pathLevel(it) }
        val (withBadges, newBadges) = BadgeEngine.evaluate(profile, stats, now)
        val badgePoints = newBadges.sumOf { BadgeEngine.pointsFor(it) }
        val final = withBadges.copy(totalPoints = withBadges.totalPoints + badgePoints)

        db.collection(USERS).document(final.id).set(encodeProfile(final, pointsAwarded + badgePoints), SetOptions.merge()).await()

        val levelAfter = final.path?.let { final.pathLevel(it) }
        return ProgressDelta(
            pointsAwarded = pointsAwarded + badgePoints,
            breakdown = breakdown,
            newBadges = newBadges,
            pathLevelUp = if (levelAfter != null && levelBefore != null && levelAfter > levelBefore) levelAfter else null,
            streakDays = final.streakDays,
            streakExtended = streakExtended,
            wasFirstSurvey = wasFirstSurvey,
        )
    }

    private fun encodeProfile(p: UserProfile, weeklyDelta: Int): Map<String, Any?> = mapOf(
        "displayName" to p.displayName,
        "cityId" to p.cityId,
        "teamId" to p.teamId,
        "teamName" to p.teamName,
        "totalPoints" to p.totalPoints,
        // Incremented rather than set, so the Monday reset function owns the field.
        "weeklyPoints" to FieldValue.increment(weeklyDelta.toLong()),
        "narrativeLevel" to p.narrativeLevel,
        "completedLevels" to p.completedLevels.toList(),
        "path" to p.path?.id,
        "badgeTiers" to p.badgeTiers,
        "earned" to p.earned.map {
            mapOf("badgeId" to it.badgeId, "tier" to it.tier, "earnedAt" to it.earnedAt)
        },
        "streakDays" to p.streakDays,
        "lastCollectionDay" to p.lastCollectionDay,
        "referralCode" to p.referralCode,
        "stats" to encodeStats(p.stats),
        "updatedAt" to System.currentTimeMillis(),
    )

    private fun encodeStats(s: UserStats): Map<String, Any?> = mapOf(
        "pieces" to s.pieces,
        "collections" to s.collections,
        "grams" to s.grams,
        "minutes" to s.minutes,
        "segmentsTouched" to s.segmentsTouched.toList(),
        "hotspotsConfirmed" to s.hotspotsConfirmed,
        "flagsPlanted" to s.flagsPlanted,
        "segmentsFirstSurveyed" to s.segmentsFirstSurveyed,
        "imagesLabelled" to s.imagesLabelled,
        "peerResurveys" to s.peerResurveys,
        "cityTop20Weeks" to s.cityTop20Weeks,
        "teamLeadWeeks" to s.teamLeadWeeks,
        "guardianWeeksHeld" to s.guardianWeeksHeld,
        "weekendRecords" to s.weekendRecords,
        "bestHourPieces" to s.bestHourPieces,
        "shares" to s.shares,
        "referrals" to s.referrals,
        "triviaWeeks" to s.triviaWeeks,
        "motivationMessages" to s.motivationMessages,
        "invitesSent" to s.invitesSent,
        "bestDaySegments" to s.bestDaySegments,
        "personalBestHour" to s.personalBestHour,
        "longestStreakDays" to s.longestStreakDays,
        "teamStreakCrownDays" to s.teamStreakCrownDays,
        "remindersAnswered" to s.remindersAnswered,
    )

    @Suppress("UNCHECKED_CAST")
    private fun decodeProfile(id: String, data: Map<String, Any?>?): UserProfile {
        if (data == null) return UserProfile(id = id)
        val statsMap = data["stats"] as? Map<String, Any?> ?: emptyMap()
        fun stat(key: String): Int = (statsMap[key] as? Number)?.toInt() ?: 0
        return UserProfile(
            id = id,
            displayName = data["displayName"] as? String ?: "Collector",
            cityId = data["cityId"] as? String ?: network.cityId,
            teamId = data["teamId"] as? String,
            teamName = data["teamName"] as? String,
            createdAt = (data["createdAt"] as? Number)?.toLong() ?: 0L,
            totalPoints = (data["totalPoints"] as? Number)?.toInt() ?: 0,
            narrativeLevel = (data["narrativeLevel"] as? Number)?.toInt() ?: 0,
            completedLevels = (data["completedLevels"] as? List<*>)
                ?.mapNotNull { (it as? Number)?.toInt() }?.toSet() ?: emptySet(),
            path = TrashPath.from(data["path"] as? String),
            badgeTiers = (data["badgeTiers"] as? Map<String, Any?>)
                ?.mapValues { (it.value as? Number)?.toInt() ?: 0 } ?: emptyMap(),
            earned = (data["earned"] as? List<Map<String, Any?>>)?.map {
                EarnedBadge(
                    badgeId = it["badgeId"] as? String ?: "",
                    tier = (it["tier"] as? Number)?.toInt() ?: 0,
                    earnedAt = (it["earnedAt"] as? Number)?.toLong() ?: 0L,
                )
            } ?: emptyList(),
            stats = UserStats(
                pieces = stat("pieces"),
                collections = stat("collections"),
                grams = (statsMap["grams"] as? Number)?.toDouble() ?: 0.0,
                minutes = stat("minutes"),
                segmentsTouched = (statsMap["segmentsTouched"] as? List<*>)
                    ?.mapNotNull { it as? String }?.toSet() ?: emptySet(),
                hotspotsConfirmed = stat("hotspotsConfirmed"),
                flagsPlanted = stat("flagsPlanted"),
                segmentsFirstSurveyed = stat("segmentsFirstSurveyed"),
                imagesLabelled = stat("imagesLabelled"),
                peerResurveys = stat("peerResurveys"),
                cityTop20Weeks = stat("cityTop20Weeks"),
                teamLeadWeeks = stat("teamLeadWeeks"),
                guardianWeeksHeld = stat("guardianWeeksHeld"),
                weekendRecords = stat("weekendRecords"),
                bestHourPieces = stat("bestHourPieces"),
                shares = stat("shares"),
                referrals = stat("referrals"),
                triviaWeeks = stat("triviaWeeks"),
                motivationMessages = stat("motivationMessages"),
                invitesSent = stat("invitesSent"),
                bestDaySegments = stat("bestDaySegments"),
                personalBestHour = stat("personalBestHour"),
                longestStreakDays = stat("longestStreakDays"),
                teamStreakCrownDays = stat("teamStreakCrownDays"),
                remindersAnswered = stat("remindersAnswered"),
            ),
            streakDays = (data["streakDays"] as? Number)?.toInt() ?: 0,
            lastCollectionDay = (data["lastCollectionDay"] as? Number)?.toLong() ?: 0L,
            referralCode = data["referralCode"] as? String ?: "",
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun decodeCollection(id: String, data: Map<String, Any?>): CollectionEvent {
        val counts = (data["counts"] as? Map<String, Any?>)?.mapNotNull { (k, v) ->
            runCatching { LitterType.valueOf(k) }.getOrNull()?.let { it to ((v as? Number)?.toInt() ?: 0) }
        }?.toMap() ?: emptyMap()
        return CollectionEvent(
            id = id,
            userId = data["userId"] as? String ?: "",
            userName = data["userName"] as? String ?: "Collector",
            teamId = data["teamId"] as? String,
            segmentId = data["segmentId"] as? String ?: "",
            segmentName = data["segmentName"] as? String ?: "",
            location = LatLng(
                (data["lat"] as? Number)?.toDouble() ?: 0.0,
                (data["lng"] as? Number)?.toDouble() ?: 0.0,
            ),
            counts = counts,
            photoUri = data["photoUri"] as? String,
            notes = data["notes"] as? String ?: "",
            createdAt = (data["createdAt"] as? Number)?.toLong() ?: 0L,
            points = (data["points"] as? Number)?.toInt() ?: 0,
            wasFirstSurvey = data["wasFirstSurvey"] as? Boolean ?: false,
            minutes = (data["minutes"] as? Number)?.toInt() ?: 0,
        )
    }

    companion object {
        private const val TAG = "TrashTalkFirestore"
        const val USERS = "users"
        const val COLLECTIONS = "collections"
        const val SEGMENT_STATE = "segment_state"
        const val TEAMS = "teams"
        const val FLAGS = "flags"

        /** Seed documents a fresh project needs; see docs/FIREBASE_SETUP.md. */
        val DEFAULT_TEAMS = listOf(
            "team_trashers" to "Terrell Heights Trashers",
            "team_austin" to "Austin Hwy Alliance",
            "team_wilshire" to "Wilshire Walkers",
            "team_night" to "Nacogdoches Night Crew",
        )
    }
}

/**
 * Bridges a Play Services [Task] to a coroutine without pulling in
 * kotlinx-coroutines-play-services for four call sites.
 */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val e = task.exception
        when {
            e != null -> cont.resumeWithException(e)
            task.isCanceled -> cont.cancel()
            else -> @Suppress("UNCHECKED_CAST") cont.resume(task.result as T)
        }
    }
}
