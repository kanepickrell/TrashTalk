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
    ) {
        val elapsedMillis: Long get() = System.currentTimeMillis() - walk.startedAt
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

                LiveWalk(
                    walk = walk,
                    pieces = pickups.sumOf { it.count },
                    byType = byType,
                    coveredSegmentIds = covered.toSet(),
                )
            }
        }
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
}
