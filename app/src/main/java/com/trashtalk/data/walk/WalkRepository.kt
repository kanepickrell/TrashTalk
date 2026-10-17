package com.trashtalk.data.walk

import android.content.Context
import com.trashtalk.core.LatLng
import com.trashtalk.core.distanceToPolylineMeters
import com.trashtalk.data.model.LitterType
import com.trashtalk.data.model.Segment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * What the walk UI reads and writes.
 *
 * The service owns recording; this owns the view of it. They meet only through
 * the database, which is deliberate -- binding to the service would mean the
 * UI's picture of a walk goes blank every time the process is recycled, and
 * the whole point of writing through to Room is that it does not.
 */
class WalkRepository(private val context: Context) {

    private val dao = WalkStore.database(context).walks()

    /** The walk in progress, or null. */
    val activeWalk: Flow<WalkRow?> = dao.observeActiveWalk()

    /** Finished walks, newest first, with their sync status. */
    val recentWalks: Flow<List<WalkRow>> = dao.observeRecent()

    /** Live totals for the walk screen. */
    data class LiveWalk(
        val walk: WalkRow,
        val pieces: Int,
        val byType: Map<LitterType, Int>,
        val coveredSegmentIds: Set<String>,
        /** Tags logged, as opposed to pieces: five cans in one tap is one. */
        val tagCount: Int = 0,
        /** How many of those carry a spot-check photo. */
        val spotCheckCount: Int = 0,
        /** The most recent tag, which is what a prompt would ask about. */
        val lastTagLabel: String? = null,
        val lastTagHasPhoto: Boolean = false,
    ) {
        val elapsedMillis: Long get() = System.currentTimeMillis() - walk.startedAt

        /**
         * Whether to ask for a photo of what was just tagged.
         *
         * Due once four tags have gone by since the last one that was either
         * photographed or waved away, and it then stays up until one of those
         * happens. The first version keyed on `tagCount % 4 == 0`, which put
         * the prompt on screen for exactly one tag: log a fifth thing and the
         * ask silently vanished. Six tags in two minutes produced no photo
         * and no prompt anybody saw.
         *
         * Staying up is safe because the question moves with you -- it always
         * names the most recent tag, so what it points at is whatever is
         * still at your feet, not something three streets back.
         *
         * Nothing is blocked either way. Refusing to photograph a crisp
         * packet should not cost somebody the work of having picked it up.
         */
        fun wantsSpotCheck(skipped: Int): Boolean =
            !lastTagHasPhoto &&
                tagCount >= SPOT_CHECK_EVERY * (spotCheckCount + skipped + 1)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val live: Flow<LiveWalk?> = activeWalk.flatMapLatest { walk ->
        if (walk == null) {
            flowOf(null)
        } else {
            combine(
                dao.observePickups(walk.id),
                dao.observeCoveredSegments(walk.id),
            ) { pickups, covered ->
                val byType = pickups
                    .mapNotNull { row ->
                        runCatching { LitterType.valueOf(row.litterType) }
                            .getOrNull()
                            ?.let { it to row.count }
                    }
                    .groupBy({ it.first }, { it.second })
                    .mapValues { (_, counts) -> counts.sum() }

                val last = pickups.maxByOrNull { it.at }
                LiveWalk(
                    walk = walk,
                    pieces = pickups.sumOf { it.count },
                    byType = byType,
                    coveredSegmentIds = covered.toSet(),
                    tagCount = pickups.size,
                    spotCheckCount = pickups.count { it.photoUri != null },
                    lastTagLabel = last?.let { row ->
                        runCatching { LitterType.valueOf(row.litterType).label }
                            .getOrDefault(row.litterType.lowercase().replace('_', ' '))
                    },
                    lastTagHasPhoto = last?.photoUri != null,
                )
            }
        }
    }

    /**
     * Attaches a spot-check photo to the tag it was taken for.
     *
     * The pickup is found by recency rather than passed in, because the
     * prompt is always about the last thing tagged and the camera round trip
     * can outlive the composable that launched it.
     */
    suspend fun attachSpotCheck(uri: String): Boolean = withContext(Dispatchers.IO) {
        val walk = dao.activeWalk() ?: return@withContext false
        val pickup = dao.lastPickup(walk.id) ?: return@withContext false
        dao.setPickupPhoto(pickup.id, uri)
        true
    }

    fun start() = CleanupRecordingService.start(context)

    fun finish() = CleanupRecordingService.stop(context)

    /**
     * Logs a pickup from inside the app, as opposed to from the notification.
     *
     * Takes the position from the caller rather than the service, because the
     * walk screen already has a fix in hand and routing it back through the
     * service would mean a round trip for no gain.
     */
    suspend fun logPickup(
        type: LitterType,
        count: Int,
        at: LatLng,
        accuracyMeters: Float,
        segments: List<Segment>,
    ): Boolean = withContext(Dispatchers.IO) {
        val walk = dao.activeWalk() ?: return@withContext false
        val nearest = segments
            .asSequence()
            .map { it to distanceToPolylineMeters(at, it.path) }
            .minByOrNull { it.second }
            ?.takeIf { it.second < 60.0 }

        dao.insertPickup(
            PickupRow(
                walkId = walk.id,
                at = System.currentTimeMillis(),
                lat = at.lat,
                lng = at.lng,
                accuracyMeters = accuracyMeters,
                litterType = type.name,
                count = count,
                segmentId = nearest?.first?.id,
                segmentDistanceMeters = nearest?.second,
            ),
        )
        true
    }

    /** Everything recorded under a walk, for the finish screen and the queue. */
    suspend fun load(walkId: String): WalkWithData? =
        withContext(Dispatchers.IO) { dao.load(walkId) }

    /** The track so far, for drawing the walked line on the map. */
    suspend fun track(walkId: String): List<LatLng> = withContext(Dispatchers.IO) {
        dao.points(walkId).map { LatLng(it.lat, it.lng) }
    }

    companion object {
        /**
         * Ask for a photo of every fourth thing tagged.
         *
         * Spot checks exist so a walk's tally can be believed without
         * photographing all of it, which nobody would do. Four is often
         * enough that a fabricated walk would have to fabricate pictures
         * too, and rare enough that a forty-piece walk asks ten times rather
         * than forty -- the point is a sample, not a inventory.
         */
        const val SPOT_CHECK_EVERY = 4
    }
}
