package com.trashtalk.data

import com.trashtalk.data.model.LitterType
import com.trashtalk.data.model.BackendMode
import com.trashtalk.data.model.CollectionEvent
import com.trashtalk.data.model.FlagKind
import com.trashtalk.data.model.LeaderboardEntry
import com.trashtalk.data.model.LeaderboardScope
import com.trashtalk.data.model.MapFlag
import com.trashtalk.data.model.Poi
import com.trashtalk.data.model.Segment
import com.trashtalk.data.model.Team
import com.trashtalk.data.model.TrashPath
import com.trashtalk.data.model.UserProfile
import com.trashtalk.domain.CollectionDraft
import com.trashtalk.domain.ProgressDelta
import kotlinx.coroutines.flow.Flow

/**
 * The single data contract the UI talks to.
 *
 * Two implementations satisfy it: [com.trashtalk.data.demo.DemoRepository], which
 * seeds a believable neighbourhood on-device, and
 * [com.trashtalk.data.firebase.FirestoreRepository], which syncs through Cloud
 * Firestore. Keeping every screen behind this interface is what lets the app be
 * fully usable before anyone has created a Firebase project -- and what makes
 * switching to live data a configuration change rather than a rewrite.
 *
 * Writes return a [ProgressDelta] rather than Unit: the caller needs to know what
 * the action earned so it can show the badge that was just unlocked.
 */
interface TrashTalkRepository {

    val mode: BackendMode

    /** The bundled neighbourhood definition: geometry, bounds, generators. */
    val network: NeighborhoodNetwork

    val pois: List<Poi> get() = network.pois

    fun observeSegments(): Flow<List<Segment>>

    fun observeProfile(): Flow<UserProfile>

    fun observeCollections(limit: Int = 100): Flow<List<CollectionEvent>>

    fun observeTeams(): Flow<List<Team>>

    fun observeFlags(): Flow<List<MapFlag>>

    fun observeLeaderboard(scope: LeaderboardScope): Flow<List<LeaderboardEntry>>

    /** Ensures a signed-in identity exists. Anonymous auth in live mode. */
    suspend fun ensureSignedIn()

    suspend fun submitCollection(draft: CollectionDraft): ProgressDelta

    /**
     * Submits a whole walk as one document for the server to score.
     *
     * One submission rather than one per street: the track is shared, the
     * anti-cheat checks apply to the walk as a whole, and the user did one
     * walk so they get one answer.
     */
    suspend fun submitToServer(
        submissionId: String,
        segments: List<SubmittedSegment>,
        track: List<TrackFix>,
        evidencePath: String?,
        /**
         * The recorder's own clock. The track stops growing once somebody
         * stops moving, so it under-reports a walk that ended standing
         * still; the server uses this only to lengthen that judgement.
         */
        walkStartedAt: Long? = null,
        walkEndedAt: Long? = null,
    ): ProgressDelta

    suspend fun plantFlag(
        kind: FlagKind,
        lat: Double,
        lng: Double,
        segmentId: String?,
        note: String,
        anonymous: Boolean,
    ): ProgressDelta

    /** Adopt a segment under Protect the Flag, or release it when [adopt] is false. */
    suspend fun setGuardian(segmentId: String, adopt: Boolean, anonymous: Boolean): ProgressDelta

    suspend fun completeLevel(levelIndex: Int): ProgressDelta

    suspend fun choosePath(path: TrashPath): ProgressDelta

    suspend fun joinTeam(teamId: String?): ProgressDelta

    suspend fun setDisplayName(name: String)

    /**
     * Advocate and Scientist actions that are not collections but still count:
     * sharing a statshot, inviting people, labelling images, finishing the trivia.
     */
    suspend fun recordAdvocacy(action: AdvocacyAction, amount: Int = 1): ProgressDelta

    /** Resets on-device progress. Demo mode only; a no-op against live data. */
    suspend fun resetProgress()
}

/** Non-collection actions that feed badge metrics. */
enum class AdvocacyAction(val label: String, val blurb: String) {
    SHARE_STATSHOT("Shared a statshot", "Advocate: Sharing"),
    SEND_INVITE("Sent an invite", "Advocate: Inspiration"),
    LOG_REFERRAL("Logged a referral", "Advocate: Recruiting"),
    TEAM_MESSAGE("Messaged your team", "Advocate: Motivation"),
    FINISH_TRIVIA("Finished weekly trivia", "Advocate: Education"),
    LABEL_IMAGES("Labelled survey images", "Scientist: Litter analysis"),
    PEER_RESURVEY("Re-surveyed a peer's segment", "Scientist: Calibration"),
    ANSWER_REMINDER("Answered a reminder", "Seeker: Diligence"),
}

/** One street's worth of a submission. */
data class SubmittedSegment(
    val segmentId: String,
    val segmentName: String,
    val counts: Map<LitterType, Int>,
    val lat: Double,
    val lng: Double,
    val minutes: Int = 0,
)

/** A recorded fix, as the server's anti-cheat expects it. */
data class TrackFix(
    val lat: Double,
    val lng: Double,
    val at: Long,
    val accuracyMeters: Float,
    val isMock: Boolean,
)

/** The server refused this cleanup, with a reason meant for the user. */
class SubmissionRejected(message: String) : Exception(message)
