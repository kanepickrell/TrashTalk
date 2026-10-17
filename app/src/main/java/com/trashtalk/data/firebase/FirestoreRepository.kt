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
import com.trashtalk.data.SubmittedSegment
import com.trashtalk.data.TrackFix
import com.trashtalk.data.SubmissionRejected
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

    /** Guards the one-time crew seeding check per process. */
    @Volatile
    private var crewsChecked = false

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
        // Reuse the existing anonymous session when there is one; it persists
        // across launches, so this is the usual path after first run.
        val id = auth.currentUser?.uid
            ?: auth.signInAnonymously().await().user?.uid
            ?: error("anonymous sign-in returned no user")

        uid.value = id
        ensureProfileDocument(id)
        // Deliberately outside the sign-in branch: the roster has to be checked
        // on every launch, not only the one that creates the account. An early
        // return here is why a returning user saw an empty crew list.
        ensureCrewsSeeded()
    }

    /**
     * Creates the starting crews if the roster is empty.
     *
     * A brand-new project would otherwise show an empty Crews list until
     * somebody hand-created four documents in the console, which is a poor
     * first run and an easy step to get subtly wrong. The security rules permit
     * creation of exactly these four ids, so this can seed the roster it ships
     * with and cannot invent crews. Writes use merge, so a crew that already
     * exists keeps its accumulated counters.
     */
    private suspend fun ensureCrewsSeeded() {
        if (crewsChecked) return
        crewsChecked = true
        runCatching {
            val existing = db.collection(TEAMS).limit(1).get().await()
            if (!existing.isEmpty) return

            for ((id, name, blurb) in DEFAULT_TEAMS) {
                db.collection(TEAMS).document(id).set(
                    mapOf(
                        "name" to name,
                        "blurb" to blurb,
                        "cityId" to network.cityId,
                        "memberCount" to 0,
                        "weeklyPoints" to 0,
                        "totalPieces" to 0,
                        "totalPoints" to 0,
                    ),
                    SetOptions.merge(),
                ).await()
            }
            Log.i(TAG, "seeded ${DEFAULT_TEAMS.size} crews")
        }.onFailure { Log.w(TAG, "crew seeding skipped: ${it.message}") }
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
                "narrativeLevel" to 0,
                "completedLevels" to emptyList<Int>(),
                "badgeTiers" to emptyMap<String, Int>(),
                "referralCode" to "TT-" + (100000..999999).random(),
                "stats" to emptyMap<String, Any>(),
            ),
            SetOptions.merge(),
        ).await()
    }

    /**
     * Describes a cleanup to the server and waits for what it was worth.
     *
     * The client used to compute the points and write them, along with the
     * segment's new density and its own running total. That made every score
     * a claim the client made about itself, which is fine right up until
     * anything is competitive.
     *
     * Now it writes one document saying what it did, and the scoring function
     * decides. Nothing here is trusted: not the points, not the ownership, not
     * whether it was a first survey. The client's own ScoreEngine still runs,
     * but only to show a number immediately -- a submission that scores in
     * silence reads as broken -- and that number is a preview, not the award.
     *
     * The submission id comes from the caller and is what makes a retry safe.
     */
    override suspend fun submitCollection(draft: CollectionDraft): ProgressDelta =
        submitToServer(
            submissionId = draft.submissionId,
            segments = listOf(
                SubmittedSegment(
                    segmentId = draft.segmentId,
                    segmentName = draft.segmentName,
                    counts = draft.counts,
                    lat = draft.lat,
                    lng = draft.lng,
                    minutes = draft.minutes,
                ),
            ),
            track = emptyList(),
            evidencePath = draft.photoUri,
        )

    override suspend fun submitToServer(
        submissionId: String,
        segments: List<SubmittedSegment>,
        track: List<TrackFix>,
        evidencePath: String?,
    ): ProgressDelta {
        val uid = requireUid()
        val now = System.currentTimeMillis()

        // Written once, and a second attempt is expected rather than
        // exceptional: the offline queue retries anything it could not
        // distinguish from a failure, and the rules make submissions
        // immutable so that a document cannot be edited after the function
        // has read it.
        //
        // So a refused write here most likely means this submission already
        // landed, and the right response is to go and wait for its result --
        // not to report that the server refused the cleanup, which is what
        // used to happen.
        val write = runCatching {
            db.collection(SUBMISSIONS).document(submissionId).set(
            mapOf(
                "userId" to uid,
                "createdAt" to now,
                "evidencePath" to evidencePath,
                "segments" to segments.map { seg ->
                    mapOf(
                        "segmentId" to seg.segmentId,
                        "segmentName" to seg.segmentName,
                        "counts" to seg.counts.mapKeys { it.key.name },
                        "lat" to seg.lat,
                        "lng" to seg.lng,
                        "minutes" to seg.minutes,
                    )
                },
                "track" to track.map {
                    mapOf(
                        "lat" to it.lat,
                        "lng" to it.lng,
                        "at" to it.at,
                        "accuracyMetres" to it.accuracyMeters.toDouble(),
                        "isMock" to it.isMock,
                    )
                },
            ),
            ).await()
        }
        if (write.isFailure) {
            Log.i(TAG, "submission $submissionId was already written; awaiting its result")
        }

        // The function answers in `results/{id}`. Waiting on a listener rather
        // than polling, so the celebration fires the moment it lands.
        val result = awaitResult(submissionId)
            ?: throw IllegalStateException("the server did not answer in time")

        if (result["status"] == "REJECTED") {
            throw SubmissionRejected(
                (result["reason"] as? String)
                    ?: "This cleanup could not be scored.",
            )
        }

        val awarded = (result["pointsAwarded"] as? Number)?.toInt() ?: 0
        val profile = loadProfile(uid)
        val firstSurvey = ((result["awards"] as? List<*>)?.firstOrNull() as? Map<*, *>)
            ?.get("wasFirstSurvey") as? Boolean ?: false

        return ProgressDelta(
            pointsAwarded = awarded,
            wasFirstSurvey = firstSurvey,
            streakDays = profile.streakDays,
            shareCard = com.trashtalk.ui.share.ShareCardData.from(
                segment = currentSegment(segments.firstOrNull()?.segmentId ?: ""),
                counts = segments.firstOrNull()?.counts ?: emptyMap(),
                wasFirstSurvey = firstSurvey,
                neighborhood = network.neighborhood,
            ),
        )
    }

    /** Listens for the scoring function's answer. */
    private suspend fun awaitResult(submissionId: String): Map<String, Any?>? =
        kotlinx.coroutines.withTimeoutOrNull(RESULT_TIMEOUT_MS) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                val reg = db.collection(RESULTS).document(submissionId)
                    .addSnapshotListener { snap, error ->
                        when {
                            error != null -> if (cont.isActive) cont.resumeWith(Result.failure(error))
                            snap != null && snap.exists() ->
                                if (cont.isActive) cont.resumeWith(Result.success(snap.data))
                        }
                    }
                cont.invokeOnCancellation { reg.remove() }
            }
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
        shareCard: com.trashtalk.ui.share.ShareCardData? = null,
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
            shareCard = shareCard,
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
        // totalPoints, weeklyPoints and streakDays are deliberately absent:
        // they are what the leaderboard ranks on and what the scoring
        // multiplier reads, so the server owns them. Writing them here would
        // be refused by the rules, and should be.
        "narrativeLevel" to p.narrativeLevel,
        "completedLevels" to p.completedLevels.toList(),
        "path" to p.path?.id,
        "badgeTiers" to p.badgeTiers,
        "earned" to p.earned.map {
            mapOf("badgeId" to it.badgeId, "tier" to it.tier, "earnedAt" to it.earnedAt)
        },
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
        const val SUBMISSIONS = "submissions"
        const val RESULTS = "results"

        /**
         * How long to wait for the scoring function.
         *
         * Longer than the client-side timeout used to be, because a cold
         * function start is seconds before it has even read anything. Past
         * this the walk stays PENDING and the queue tries again, which is the
         * same path a dead spot takes.
         */
        const val RESULT_TIMEOUT_MS = 45_000L

        /**
         * The crews a fresh project starts with. The ids are referenced by the
         * security rules, so adding one here means updating
         * `isKnownCrew` in docs/firestore.rules too.
         */
        val DEFAULT_TEAMS = listOf(
            Triple(
                "team_trashers",
                "Terrell Heights Trashers",
                "The original crew. Meets Saturdays at the park.",
            ),
            Triple(
                "team_austin",
                "Austin Hwy Alliance",
                "Arterial specialists. Somebody has to do it.",
            ),
            Triple(
                "team_wilshire",
                "Wilshire Walkers",
                "Parents doing the school run with grabbers.",
            ),
            Triple(
                "team_night",
                "Nacogdoches Night Crew",
                "Evening shift, high-vis vests, strong opinions.",
            ),
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
