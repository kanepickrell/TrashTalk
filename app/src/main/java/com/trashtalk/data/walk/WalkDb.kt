package com.trashtalk.data.walk

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * On-device storage for a cleanup walk.
 *
 * Everything is written as it happens rather than assembled at the end. A walk
 * is an hour of someone's Saturday; if the process is killed -- and a
 * screen-off recording is exactly the kind of process Android kills -- nothing
 * about that hour should be lost. There is no in-memory accumulator here that a
 * crash could take with it.
 *
 * This is also the queue Phase 5 drains: a finished walk sits in `walks` with
 * status PENDING until the server accepts it, which is why status lives in the
 * database and not in a view model.
 */

/** Where a walk is in its life. */
enum class WalkStatus {
    /** Being recorded now. At most one walk is ever in this state. */
    RECORDING,

    /** Finished and waiting for the sync queue. */
    PENDING,

    /** Uploaded; waiting on the server's result. */
    SYNCING,

    /** The server scored it. */
    SCORED,

    /** The server refused it. Kept, with its reason, so the user can see why. */
    REJECTED,
}

@Entity(tableName = "walks")
data class WalkRow(
    /**
     * Generated on the client, before anything is recorded.
     *
     * The submission id has to come from here rather than from the server, so
     * that a retry after a timeout cannot score the same walk twice. See
     * Phase 5.
     */
    @PrimaryKey val id: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val status: WalkStatus = WalkStatus.RECORDING,
    /** Metres travelled, accumulated from the gated points. */
    val distanceMeters: Double = 0.0,
    /** Milliseconds spent stationary, which is the time spent picking up. */
    val idleMillis: Long = 0,
    /** Server verdict once there is one; plain language, shown to the user. */
    val rejectionReason: String? = null,
    /** Set when the server returns, so the client can stop guessing. */
    val awardedPoints: Int? = null,
)

@Entity(
    tableName = "track_points",
    indices = [Index("walkId"), Index(value = ["walkId", "at"])],
)
data class TrackPointRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val walkId: String,
    val at: Long,
    val lat: Double,
    val lng: Double,
    @ColumnInfo(name = "accuracy_m") val accuracyMeters: Float,
    /**
     * Whether Android flagged this fix as mock.
     *
     * Recorded, never acted on here. The client is not a trustworthy place to
     * enforce anything, so this is evidence for the server (Phase 4.3), not a
     * gate.
     */
    val isMock: Boolean = false,
)

@Entity(
    tableName = "pickups",
    indices = [Index("walkId"), Index("segmentId")],
)
data class PickupRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val walkId: String,
    val at: Long,
    val lat: Double,
    val lng: Double,
    @ColumnInfo(name = "accuracy_m") val accuracyMeters: Float,
    /** The litter type's enum name. */
    val litterType: String,
    val count: Int,
    /**
     * The nearest segment, stamped on-device at the moment of the pickup.
     *
     * A guess, and labelled one: the server re-derives it by map matching the
     * whole track (Phase 4.2), because a single fix next to a corner cannot
     * tell which of two streets someone was walking. Keeping the client's
     * guess is still worth it -- it is what the walk screen shows live, and
     * disagreement between the two is a useful signal.
     */
    val segmentId: String? = null,
    val segmentDistanceMeters: Double? = null,
    /** Local file, until Phase 4E.1 uploads it and replaces this with a path. */
    val photoUri: String? = null,
)

/** A walk with everything recorded under it. */
data class WalkWithData(
    val walk: WalkRow,
    val points: List<TrackPointRow>,
    val pickups: List<PickupRow>,
)

@Dao
interface WalkDao {

    // ---- recording -------------------------------------------------------

    @Insert
    suspend fun insertWalk(walk: WalkRow)

    @Insert
    suspend fun insertPoint(point: TrackPointRow)

    @Insert
    suspend fun insertPickup(pickup: PickupRow)

    @Query(
        """
        UPDATE walks
           SET distanceMeters = distanceMeters + :addedMeters,
               idleMillis = idleMillis + :addedIdleMillis
         WHERE id = :walkId
        """,
    )
    suspend fun addProgress(walkId: String, addedMeters: Double, addedIdleMillis: Long)

    @Query("UPDATE walks SET status = :status, endedAt = :endedAt WHERE id = :walkId")
    suspend fun finish(walkId: String, endedAt: Long, status: WalkStatus = WalkStatus.PENDING)

    @Query("UPDATE walks SET status = :status WHERE id = :walkId")
    suspend fun setStatus(walkId: String, status: WalkStatus)

    /**
     * Records what the server said.
     *
     * Points are coalesced rather than assigned: a re-submission of a walk that
     * already landed is correctly a no-op worth zero, and writing that zero
     * over the real award would show the user a walk that earned nothing. The
     * first award is the true one.
     */
    @Query(
        """
        UPDATE walks
           SET status = :status,
               rejectionReason = :reason,
               awardedPoints = COALESCE(:points, awardedPoints)
         WHERE id = :walkId
        """,
    )
    suspend fun applyServerResult(
        walkId: String,
        status: WalkStatus,
        reason: String?,
        points: Int?,
    )

    // ---- reading ---------------------------------------------------------

    /**
     * The walk being recorded, if there is one.
     *
     * Survives process death by construction: after a restart the service asks
     * this, finds its own unfinished walk, and carries on appending to it.
     */
    @Query("SELECT * FROM walks WHERE status = 'RECORDING' ORDER BY startedAt DESC LIMIT 1")
    suspend fun activeWalk(): WalkRow?

    @Query("SELECT * FROM walks WHERE status = 'RECORDING' ORDER BY startedAt DESC LIMIT 1")
    fun observeActiveWalk(): Flow<WalkRow?>

    @Query("SELECT * FROM walks WHERE id = :walkId")
    suspend fun walk(walkId: String): WalkRow?

    @Query("SELECT * FROM walks ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<WalkRow>>

    /** Oldest first: the sync queue drains in the order things happened. */
    @Query("SELECT * FROM walks WHERE status = 'PENDING' ORDER BY startedAt ASC")
    suspend fun pendingWalks(): List<WalkRow>

    @Query("SELECT * FROM track_points WHERE walkId = :walkId ORDER BY at ASC")
    suspend fun points(walkId: String): List<TrackPointRow>

    @Query("SELECT * FROM track_points WHERE walkId = :walkId ORDER BY at DESC LIMIT 1")
    suspend fun lastPoint(walkId: String): TrackPointRow?

    @Query("SELECT * FROM pickups WHERE walkId = :walkId ORDER BY at ASC")
    suspend fun pickups(walkId: String): List<PickupRow>

    @Query("SELECT * FROM pickups WHERE walkId = :walkId ORDER BY at ASC")
    fun observePickups(walkId: String): Flow<List<PickupRow>>

    /** Distinct segments touched, for the live "segments covered" counter. */
    @Query(
        "SELECT DISTINCT segmentId FROM pickups WHERE walkId = :walkId AND segmentId IS NOT NULL",
    )
    fun observeCoveredSegments(walkId: String): Flow<List<String>>

    /** The types this user logs most, for the notification's quick-add buttons. */
    @Query(
        """
        SELECT litterType FROM pickups
      GROUP BY litterType
      ORDER BY SUM(count) DESC
         LIMIT :limit
        """,
    )
    suspend fun mostLoggedTypes(limit: Int = 3): List<String>

    @Transaction
    suspend fun load(walkId: String): WalkWithData? {
        val walk = walk(walkId) ?: return null
        return WalkWithData(walk, points(walkId), pickups(walkId))
    }

    // ---- housekeeping ----------------------------------------------------

    @Query("DELETE FROM walks WHERE id = :walkId")
    suspend fun deleteWalk(walkId: String)

    /**
     * Drops the raw track of walks the server has already scored.
     *
     * A scored walk's track has done its job, and a GPS trace of where somebody
     * walks on a Saturday is the most sensitive thing this app holds. The walk
     * row and its pickup counts stay; the breadcrumbs do not.
     */
    @Query(
        """
        DELETE FROM track_points
         WHERE walkId IN (SELECT id FROM walks WHERE status = 'SCORED' AND endedAt < :before)
        """,
    )
    suspend fun pruneScoredTracks(before: Long)
}

@Database(
    entities = [WalkRow::class, TrackPointRow::class, PickupRow::class],
    version = 1,
    exportSchema = true,
)
abstract class WalkDatabase : RoomDatabase() {
    abstract fun walks(): WalkDao
}
