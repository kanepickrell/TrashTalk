package com.trashtalk.data.demo

import android.content.Context
import com.trashtalk.core.LatLng
import com.trashtalk.data.AdvocacyAction
import com.trashtalk.data.NeighborhoodNetwork
import com.trashtalk.data.SubmittedSegment
import com.trashtalk.data.TrackFix
import com.trashtalk.data.TrashTalkRepository
import com.trashtalk.data.model.BackendMode
import com.trashtalk.data.model.CollectionEvent
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
import com.trashtalk.domain.ScoreEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * The on-device backend.
 *
 * This is not a stub. It runs the same scoring, badge and streak logic as live
 * mode, seeds a full neighbourhood of other collectors, and persists the player's
 * own progress to disk, so a reviewer can install the APK and experience the whole
 * product -- surveys, adoption, levels, paths, leaderboards, the assessment --
 * without anyone creating a Firebase project first.
 *
 * Only the player's own data is written; the seeded community is regenerated
 * deterministically from [DemoSeed.SEED] on every launch.
 */
class DemoRepository(
    context: Context,
    override val network: NeighborhoodNetwork,
) : TrashTalkRepository {

    override val mode = BackendMode.DEMO

    private val store = DemoStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val writeLock = Mutex()

    private val community = DemoSeed.build(network, System.currentTimeMillis())

    private val _segments = MutableStateFlow(emptyList<Segment>())
    private val _profile = MutableStateFlow(UserProfile())
    private val _playerCollections = MutableStateFlow(emptyList<CollectionEvent>())
    private val _flags = MutableStateFlow(community.flags)
    private val _teams = MutableStateFlow(community.teams)

    /** Player-applied changes to segments, kept separately so seeds stay pristine. */
    private val _overrides = MutableStateFlow(emptyMap<String, SegmentOverride>())

    init {
        val saved = store.load()
        _profile.value = saved.profile
        _playerCollections.value = saved.collections
        _overrides.value = saved.overrides
        _flags.value = community.flags + saved.flags
        recomputeSegments()

        // Any player write refreshes derived segment state and persists.
        scope.launch {
            combine(_overrides, _playerCollections) { _, _ -> Unit }.collect {
                recomputeSegments()
            }
        }
    }

    // ---------------------------------------------------------------- reads

    override fun observeSegments(): Flow<List<Segment>> = _segments.asStateFlow()

    override fun observeProfile(): Flow<UserProfile> = _profile.asStateFlow()

    override fun observeCollections(limit: Int): Flow<List<CollectionEvent>> =
        _playerCollections.map { mine ->
            (mine + community.collections)
                .sortedByDescending { it.createdAt }
                .take(limit)
        }

    override fun observeTeams(): Flow<List<Team>> = _teams.asStateFlow()

    override fun observeFlags(): Flow<List<MapFlag>> = _flags.asStateFlow()

    override fun observeLeaderboard(scope: LeaderboardScope): Flow<List<LeaderboardEntry>> =
        combine(_profile, _playerCollections) { profile, mine ->
            buildLeaderboard(scope, profile, mine)
        }

    override suspend fun ensureSignedIn() {
        if (_profile.value.id.isNotEmpty()) return
        writeLock.withLock {
            if (_profile.value.id.isNotEmpty()) return
            val now = System.currentTimeMillis()
            _profile.value = UserProfile(
                id = "local_player",
                displayName = "You",
                cityId = network.cityId,
                createdAt = now,
                referralCode = "TT-" + (100000..999999).random(),
            )
            persist()
        }
    }

    // --------------------------------------------------------------- writes

    /**
     * Demo mode has no server, so a walk is submitted segment by segment
     * through the same local path a quick log takes.
     *
     * The awards are folded into one, because the user did one walk and
     * should see one answer whichever backend is behind it.
     */
    override suspend fun submitToServer(
        submissionId: String,
        segments: List<SubmittedSegment>,
        track: List<TrackFix>,
        evidencePath: String?,
        walkStartedAt: Long?,
        walkEndedAt: Long?,
    ): ProgressDelta {
        var total = 0
        var firstSurvey = false
        var last = ProgressDelta()
        for (seg in segments) {
            val delta = submitCollection(
                CollectionDraft(
                    submissionId = "${submissionId}_${seg.segmentId}",
                    segmentId = seg.segmentId,
                    segmentName = seg.segmentName,
                    lat = seg.lat,
                    lng = seg.lng,
                    counts = seg.counts,
                    minutes = seg.minutes,
                    photoUri = evidencePath,
                ),
            )
            total += delta.pointsAwarded
            firstSurvey = firstSurvey || delta.wasFirstSurvey
            last = delta
        }
        return last.copy(pointsAwarded = total, wasFirstSurvey = firstSurvey)
    }

    override suspend fun submitCollection(draft: CollectionDraft): ProgressDelta =
        writeLock.withLock {
            val now = System.currentTimeMillis()
            val segment = _segments.value.firstOrNull { it.id == draft.segmentId }
            val profile = _profile.value
            val breakdown = ScoreEngine.score(draft, segment, profile)
            val wasFirstSurvey = segment != null && !segment.hasBeenSurveyed

            val event = CollectionEvent(
                id = "local_${now}",
                userId = profile.id,
                userName = profile.displayName.ifBlank { "You" },
                teamId = profile.teamId,
                segmentId = draft.segmentId,
                segmentName = draft.segmentName,
                location = LatLng(draft.lat, draft.lng),
                counts = draft.counts,
                photoUri = draft.photoUri,
                notes = draft.notes,
                createdAt = now,
                points = breakdown.total,
                wasFirstSurvey = wasFirstSurvey,
                minutes = draft.minutes,
            )

            val collections = listOf(event) + _playerCollections.value
            _playerCollections.value = collections

            // A pickup reduces the segment's standing density in proportion to how
            // much of its estimated load was actually removed.
            val before = segment?.litterIndex ?: 0f
            val load = segment?.estimatedPieces?.coerceAtLeast(1) ?: 1
            val cleared = (draft.pieces.toFloat() / load).coerceIn(0f, 1f)
            val after = (before * (1f - 0.75f * cleared)).coerceIn(0f, 1f)

            applyOverride(draft.segmentId) {
                it.copy(
                    litterIndex = after,
                    surveyCountDelta = it.surveyCountDelta + 1,
                    cleanupDelta = it.cleanupDelta + 1,
                    piecesRemoved = it.piecesRemoved + draft.pieces,
                    lastSurveyedAt = now,
                )
            }

            val streak = ScoreEngine.nextStreak(profile, now)
            val extended = streak > profile.streakDays

            val stats = recomputeStats(profile.stats, collections)
            val withPoints = profile.copy(
                totalPoints = profile.totalPoints + breakdown.total,
                streakDays = streak,
                lastCollectionDay = ScoreEngine.dayIndex(now),
            )
            finish(
                withPoints, stats, now, breakdown.total, breakdown, extended, wasFirstSurvey,
                shareCard = com.trashtalk.ui.share.ShareCardData.from(
                    segment = segment,
                    counts = draft.counts,
                    wasFirstSurvey = wasFirstSurvey,
                    neighborhood = network.neighborhood,
                ),
            )
        }

    override suspend fun plantFlag(
        kind: FlagKind,
        lat: Double,
        lng: Double,
        segmentId: String?,
        note: String,
        anonymous: Boolean,
    ): ProgressDelta = writeLock.withLock {
        val now = System.currentTimeMillis()
        val profile = _profile.value
        val segment = segmentId?.let { id -> _segments.value.firstOrNull { it.id == id } }

        // A hotspot guess is confirmed by the data, not by the guesser: it counts
        // only where the segment is genuinely carrying load.
        val confirmed = kind == FlagKind.HOTSPOT_GUESS && (segment?.litterIndex ?: 0f) >= 0.45f

        val flag = MapFlag(
            id = "local_flag_$now",
            userId = profile.id,
            userName = if (anonymous) "Anonymous" else profile.displayName.ifBlank { "You" },
            kind = kind,
            location = LatLng(lat, lng),
            segmentId = segmentId,
            note = note,
            createdAt = now,
            confirmed = confirmed,
            anonymous = anonymous,
        )
        _flags.value = _flags.value + flag

        val stats = profile.stats.copy(
            flagsPlanted = profile.stats.flagsPlanted + 1,
            hotspotsConfirmed = profile.stats.hotspotsConfirmed + if (confirmed) 1 else 0,
        )
        val award = if (confirmed) 60 else 15
        finish(profile.copy(totalPoints = profile.totalPoints + award), stats, now, award)
    }

    override suspend fun setGuardian(
        segmentId: String,
        adopt: Boolean,
        anonymous: Boolean,
    ): ProgressDelta = writeLock.withLock {
        val now = System.currentTimeMillis()
        val profile = _profile.value
        applyOverride(segmentId) {
            it.copy(
                guardianUserId = if (adopt) profile.id else null,
                guardianName = if (adopt) {
                    if (anonymous) "Anonymous" else profile.displayName.ifBlank { "You" }
                } else null,
                guardianSince = if (adopt) now else 0L,
            )
        }
        if (adopt) {
            _flags.value = _flags.value + MapFlag(
                id = "local_guard_$now",
                userId = profile.id,
                userName = if (anonymous) "Anonymous" else profile.displayName.ifBlank { "You" },
                kind = FlagKind.GUARDIAN,
                location = _segments.value.firstOrNull { it.id == segmentId }?.midpoint
                    ?: LatLng(0.0, 0.0),
                segmentId = segmentId,
                note = "Adopted under Protect the Flag",
                createdAt = now,
                anonymous = anonymous,
            )
        } else {
            _flags.value = _flags.value.filterNot {
                it.kind == FlagKind.GUARDIAN && it.segmentId == segmentId && it.userId == profile.id
            }
        }
        val award = if (adopt) 40 else 0
        finish(profile.copy(totalPoints = profile.totalPoints + award), profile.stats, now, award)
    }

    override suspend fun completeLevel(levelIndex: Int): ProgressDelta = writeLock.withLock {
        val now = System.currentTimeMillis()
        val profile = _profile.value
        if (levelIndex in profile.completedLevels) {
            return@withLock ProgressDelta(streakDays = profile.streakDays)
        }
        val award = com.trashtalk.data.content.LevelContent.level(levelIndex)?.pointsAward ?: 120
        val updated = profile.copy(
            completedLevels = profile.completedLevels + levelIndex,
            narrativeLevel = maxOf(profile.narrativeLevel, levelIndex),
            totalPoints = profile.totalPoints + award,
        )
        finish(updated, profile.stats, now, award)
    }

    override suspend fun choosePath(path: TrashPath): ProgressDelta = writeLock.withLock {
        val now = System.currentTimeMillis()
        val profile = _profile.value
        finish(profile.copy(path = path), profile.stats, now, 0)
    }

    override suspend fun joinTeam(teamId: String?): ProgressDelta = writeLock.withLock {
        val now = System.currentTimeMillis()
        val profile = _profile.value
        val team = _teams.value.firstOrNull { it.id == teamId }
        finish(
            profile.copy(teamId = team?.id, teamName = team?.name),
            profile.stats,
            now,
            0,
        )
    }

    override suspend fun setDisplayName(name: String) {
        writeLock.withLock {
            _profile.value = _profile.value.copy(displayName = name.trim().ifBlank { "You" })
            persist()
        }
    }

    override suspend fun recordAdvocacy(action: AdvocacyAction, amount: Int): ProgressDelta =
        writeLock.withLock {
            val now = System.currentTimeMillis()
            val profile = _profile.value
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
            finish(profile.copy(totalPoints = profile.totalPoints + award), stats, now, award)
        }

    override suspend fun resetProgress() {
        writeLock.withLock {
            store.clear()
            _playerCollections.value = emptyList()
            _overrides.value = emptyMap()
            _flags.value = community.flags
            _profile.value = UserProfile(
                id = "local_player",
                displayName = "You",
                cityId = network.cityId,
                createdAt = System.currentTimeMillis(),
                referralCode = "TT-" + (100000..999999).random(),
            )
            recomputeSegments()
            persist()
        }
    }

    // ------------------------------------------------------------- internals

    /**
     * Shared tail of every write: evaluate badges against the new stats, award
     * their points, detect a path level-up, persist, and report what changed.
     */
    private fun finish(
        profile: UserProfile,
        stats: UserStats,
        now: Long,
        pointsAwarded: Int,
        breakdown: com.trashtalk.domain.ScoreBreakdown? = null,
        streakExtended: Boolean = false,
        wasFirstSurvey: Boolean = false,
        shareCard: com.trashtalk.ui.share.ShareCardData? = null,
    ): ProgressDelta {
        val levelBefore = profile.path?.let { profile.pathLevel(it) }
        val (withBadges, newBadges) = BadgeEngine.evaluate(profile, stats, now)
        val badgePoints = newBadges.sumOf { BadgeEngine.pointsFor(it) }
        val final = withBadges.copy(totalPoints = withBadges.totalPoints + badgePoints)
        _profile.value = final
        persist()

        val levelAfter = final.path?.let { final.pathLevel(it) }
        return ProgressDelta(
            pointsAwarded = pointsAwarded + badgePoints,
            breakdown = breakdown,
            shareCard = shareCard,
            newBadges = newBadges,
            pathLevelUp = if (levelAfter != null && levelBefore != null && levelAfter > levelBefore) {
                levelAfter
            } else null,
            streakDays = final.streakDays,
            streakExtended = streakExtended,
            wasFirstSurvey = wasFirstSurvey,
        )
    }

    /**
     * Recomputes the metrics that are derivable from the collection log, leaving
     * the manually incremented counters (shares, invites, labels) untouched.
     * Deriving rather than incrementing means these can never drift out of step
     * with the log the user can actually see.
     */
    private fun recomputeStats(current: UserStats, collections: List<CollectionEvent>): UserStats {
        val byDay = collections.groupBy { ScoreEngine.dayIndex(it.createdAt) }
        val days = byDay.keys.sorted()

        var longest = 0
        var run = 0
        var prev: Long? = null
        for (d in days) {
            run = if (prev != null && d == prev + 1) run + 1 else 1
            longest = maxOf(longest, run)
            prev = d
        }

        val bestDaySegments = byDay.values.maxOfOrNull { day ->
            day.map { it.segmentId }.distinct().size
        } ?: 0

        // Pieces per hour, from sessions that reported a duration.
        val bestHour = collections.maxOfOrNull { c ->
            if (c.minutes > 0) (c.pieces * 60.0 / c.minutes).roundToInt() else c.pieces
        } ?: 0

        return current.copy(
            pieces = collections.sumOf { it.pieces },
            collections = collections.size,
            grams = collections.sumOf { it.grams },
            minutes = collections.sumOf { it.minutes },
            segmentsTouched = collections.map { it.segmentId }.toSet(),
            segmentsFirstSurveyed = collections.count { it.wasFirstSurvey },
            bestDaySegments = bestDaySegments,
            personalBestHour = bestHour,
            bestHourPieces = bestHour,
            longestStreakDays = maxOf(longest, current.longestStreakDays),
        )
    }

    private fun applyOverride(segmentId: String, transform: (SegmentOverride) -> SegmentOverride) {
        val existing = _overrides.value[segmentId] ?: SegmentOverride()
        _overrides.value = _overrides.value + (segmentId to transform(existing))
    }

    /** Folds seed removals and player overrides onto the bundled geometry. */
    private fun recomputeSegments() {
        val overrides = _overrides.value
        val seedRemoved = community.removedBySegment
        _segments.value = network.segments.map { base ->
            val o = overrides[base.id]
            val seeded = seedRemoved[base.id] ?: 0
            // A player override always wins over the seeded claim, including when
            // it releases a segment: "adopted by nobody" has to be expressible.
            val claim = community.claims[base.id]
            val claimed = o?.guardianUserId != null || (claim != null && o == null)
            base.copy(
                litterIndex = o?.litterIndex ?: base.litterIndex,
                surveyCount = base.surveyCount + (o?.surveyCountDelta ?: 0),
                cleanupCount = base.cleanupCount + (o?.cleanupDelta ?: 0),
                piecesRemoved = seeded + (o?.piecesRemoved ?: 0),
                lastSurveyedAt = o?.lastSurveyedAt ?: base.lastSurveyedAt,
                guardianUserId = if (claimed) o?.guardianUserId ?: claim?.userId else null,
                guardianName = if (claimed) o?.guardianName ?: claim?.userName else null,
                guardianTeamId = if (claimed) {
                    if (o?.guardianUserId != null) _profile.value.teamId else claim?.teamId
                } else null,
                guardianSince = o?.guardianSince ?: claim?.since ?: 0L,
            )
        }
    }

    private fun buildLeaderboard(
        scope: LeaderboardScope,
        profile: UserProfile,
        mine: List<CollectionEvent>,
    ): List<LeaderboardEntry> {
        val weekAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
        val myWeekPoints = mine.filter { it.createdAt >= weekAgo }.sumOf { it.points }
        val myWeekPieces = mine.filter { it.createdAt >= weekAgo }.sumOf { it.pieces }

        val rows = mutableListOf<LeaderboardEntry>()

        val pool = when (scope) {
            LeaderboardScope.TEAM_WEEK ->
                community.collectors.filter { it.teamId == profile.teamId }
            else -> community.collectors
        }

        pool.forEach { c ->
            val team = community.teams.firstOrNull { it.id == c.teamId }
            rows += LeaderboardEntry(
                id = c.id,
                name = c.name,
                subtitle = team?.name ?: "No crew",
                points = if (scope == LeaderboardScope.ALL_TIME) c.points else c.weeklyPoints,
                pieces = c.pieces,
                pathLabel = c.path.label,
                level = c.level,
            )
        }

        // The player only appears on a team board once they have actually joined one.
        if (scope != LeaderboardScope.TEAM_WEEK || profile.teamId != null) {
            rows += LeaderboardEntry(
                id = profile.id,
                name = profile.displayName.ifBlank { "You" },
                subtitle = profile.teamName ?: "No crew",
                points = if (scope == LeaderboardScope.ALL_TIME) profile.totalPoints else myWeekPoints,
                pieces = if (scope == LeaderboardScope.ALL_TIME) profile.stats.pieces else myWeekPieces,
                isCurrentUser = true,
                pathLabel = profile.path?.label,
                level = profile.path?.let { profile.pathLevel(it) } ?: profile.displayLevel,
            )
        }

        return rows
            .sortedByDescending { it.points }
            .mapIndexed { i, e -> e.copy(rank = i + 1) }
    }

    private fun persist() {
        store.save(
            DemoStore.Snapshot(
                profile = _profile.value,
                collections = _playerCollections.value,
                overrides = _overrides.value,
                flags = _flags.value.filter { it.userId == _profile.value.id },
            ),
        )
    }
}

/** Player-applied delta on top of a bundled segment. */
data class SegmentOverride(
    val litterIndex: Float? = null,
    val surveyCountDelta: Int = 0,
    val cleanupDelta: Int = 0,
    val piecesRemoved: Int = 0,
    val lastSurveyedAt: Long? = null,
    val guardianUserId: String? = null,
    val guardianName: String? = null,
    val guardianSince: Long? = null,
)

/** Helper so collection counts survive a round trip through JSON. */
internal fun Map<LitterType, Int>.toJsonMap(): Map<String, Int> =
    mapKeys { it.key.name }

internal fun Map<String, Int>.toLitterMap(): Map<LitterType, Int> =
    mapNotNull { (k, v) ->
        runCatching { LitterType.valueOf(k) }.getOrNull()?.let { it to v }
    }.toMap()

internal suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
