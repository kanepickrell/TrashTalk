package com.trashtalk.data.walk

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.trashtalk.MainActivity
import com.trashtalk.R
import com.trashtalk.core.LatLng
import com.trashtalk.core.distanceToPolylineMeters
import com.trashtalk.core.haversineMeters
import com.trashtalk.data.model.LitterType
import com.trashtalk.data.model.Segment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "CleanupRecording"

/**
 * How many litter types get a notification button.
 *
 * Android shows three actions; Finish takes one.
 */
private const val QUICK_ADD_BUTTONS = 2

/** For a first walk, before there is any history to learn from. */
private val DEFAULT_QUICK_ADD = listOf(
    LitterType.CIGARETTE,
    LitterType.PLASTIC_BOTTLE,
    LitterType.FOOD_PACKAGING,
)

/**
 * Records a cleanup walk with the screen off.
 *
 * A foreground service because that is the only honest way to do this on
 * modern Android: the work continues while the phone is in a pocket, and the
 * user can see at a glance that it is running and what it has recorded. The
 * notification is not a formality, it is the contract.
 *
 * Shaped after OpenTracks' TrackRecordingService, with two deliberate
 * differences:
 *
 *  - Location only. OpenTracks also declares `connectedDevice` for heart-rate
 *    sensors; TrashTalk has nothing to connect to, and asking for a
 *    foreground-service type you do not use is asking for a permission you do
 *    not need.
 *  - Idle does not end or split the walk. In a fitness tracker, standing still
 *    means the activity paused. Here it means the opposite -- somebody has
 *    stopped to pick something up, which is the entire point -- so idle time
 *    is accumulated as time spent working a segment rather than treated as a
 *    gap.
 *
 * Nothing is held only in memory. Every accepted fix and every pickup is
 * written to Room as it arrives, so a kill mid-walk costs at most the last
 * few metres.
 */
class CleanupRecordingService : Service() {

    companion object {
        const val ACTION_START = "com.trashtalk.walk.START"
        const val ACTION_STOP = "com.trashtalk.walk.STOP"
        const val ACTION_QUICK_ADD = "com.trashtalk.walk.QUICK_ADD"
        const val EXTRA_LITTER_TYPE = "litterType"

        private const val CHANNEL_ID = "cleanup_recording"
        private const val NOTIFICATION_ID = 1821

        /**
         * Fixes worse than this are dropped outright.
         *
         * 30 m is roughly a city block's width here. A fix vaguer than that
         * cannot say which side of the street somebody is on, so recording it
         * would be inventing precision the data does not have.
         */
        const val MIN_ACCURACY_METERS = 30f

        /**
         * How far someone must move before another point is stored.
         *
         * Without a gate, standing still for ten minutes writes six hundred
         * points describing a stationary smudge, and the GPS noise in them
         * would read as distance travelled. Five metres is below the width of
         * a street, so the shape of the walk survives intact.
         */
        const val MIN_DISTANCE_METERS = 5.0

        /**
         * Below this speed the walker counts as stopped, which here means
         * working rather than paused.
         */
        private const val IDLE_AFTER_MILLIS = 20_000L

        fun start(context: Context) {
            val intent = Intent(context, CleanupRecordingService::class.java)
                .setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CleanupRecordingService::class.java)
                .setAction(ACTION_STOP)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dao: WalkDao
    private lateinit var locationManager: LocationManager

    /** The walk being recorded. Null until START, or until an orphan is found. */
    @Volatile private var walkId: String? = null

    /** Last stored point, for the distance gate. Never the last *received* fix. */
    @Volatile private var lastStored: TrackPointRow? = null

    /**
     * The last fix good enough to use, stored or not.
     *
     * Kept separately from [lastStored] because the distance gate deliberately
     * discards most fixes, and a pickup needs a position now rather than the
     * last one that happened to be five metres from its predecessor. Without
     * this, tapping a quick-add button in the first seconds of a walk -- before
     * anything has passed the gate -- would silently record nothing.
     */
    @Volatile private var lastFix: Location? = null

    @Volatile private var lastMovedAt: Long = 0L
    @Volatile private var pieces: Int = 0
    @Volatile private var covered: MutableSet<String> = mutableSetOf()
    @Volatile private var startedAt: Long = 0L

    /** Segment geometry, for stamping pickups as they happen. */
    @Volatile private var segments: List<Segment> = emptyList()

    // LocationListenerCompat rather than the platform interface: the compat
    // request builder only accepts the former, and it back-fills the callbacks
    // older API levels made abstract.
    private val listener = LocationListenerCompat { location -> onFix(location) }

    override fun onCreate() {
        super.onCreate()
        dao = WalkStore.database(this).walks()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                finishWalk()
                return START_NOT_STICKY
            }

            ACTION_QUICK_ADD -> {
                intent.getStringExtra(EXTRA_LITTER_TYPE)?.let { quickAdd(it) }
                return START_STICKY
            }

            else -> beginOrResume()
        }
        return START_STICKY
    }

    /**
     * Starts a walk, or picks up the one this process was killed in the middle
     * of.
     *
     * The second case is why `activeWalk()` is asked first. A restarted service
     * that began a fresh walk would split one cleanup into two, and the user
     * would have no idea why.
     */
    private fun beginOrResume() {
        if (walkId != null) return

        scope.launch {
            val existing = dao.activeWalk()
            val walk = existing ?: WalkRow(
                id = UUID.randomUUID().toString(),
                startedAt = System.currentTimeMillis(),
            ).also { dao.insertWalk(it) }

            walkId = walk.id
            startedAt = walk.startedAt
            lastStored = dao.lastPoint(walk.id)
            lastMovedAt = lastStored?.at ?: walk.startedAt
            pieces = dao.pickups(walk.id).sumOf { it.count }
            covered = dao.pickups(walk.id).mapNotNull { it.segmentId }.toMutableSet()
            segments = WalkStore.segments(this@CleanupRecordingService)
            quickAddTypes = resolveQuickAddTypes()

            if (existing != null) {
                Log.i(TAG, "resumed walk ${walk.id} after process death")
            }

            startForegroundWithNotification()
            requestUpdates()
        }
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun requestUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "no location permission; stopping")
            stopSelf()
            return
        }

        // One second nominal, but the distance gate below is what actually
        // decides what gets stored. Asking for less often would make the
        // recorded corners square.
        val request = LocationRequestCompat.Builder(1_000L)
            .setMinUpdateDistanceMeters(0f)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .build()

        try {
            LocationManagerCompat.requestLocationUpdates(
                locationManager,
                LocationManager.GPS_PROVIDER,
                request,
                listener,
                Looper.getMainLooper(),
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "location permission revoked mid-walk", e)
            stopSelf()
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "GPS provider unavailable", e)
            stopSelf()
        }
    }

    private fun onFix(location: Location) {
        val id = walkId ?: return

        // Vague fixes are dropped rather than smoothed. A smoothed bad fix is
        // still a bad fix, with its badness hidden.
        if (!location.hasAccuracy() || location.accuracy > MIN_ACCURACY_METERS) return
        lastFix = location

        val now = System.currentTimeMillis()
        val previous = lastStored
        val moved = previous?.let {
            haversineMeters(
                LatLng(it.lat, it.lng),
                LatLng(location.latitude, location.longitude),
            )
        } ?: Double.MAX_VALUE

        if (moved < MIN_DISTANCE_METERS) {
            // Standing still is working, not pausing: bank the time against the
            // walk and leave the track alone.
            if (now - lastMovedAt >= IDLE_AFTER_MILLIS) {
                val idle = now - lastMovedAt
                lastMovedAt = now
                scope.launch { dao.addProgress(id, 0.0, idle) }
                notifyProgress()
            }
            return
        }

        val point = TrackPointRow(
            walkId = id,
            at = now,
            lat = location.latitude,
            lng = location.longitude,
            accuracyMeters = location.accuracy,
            isMock = location.isFromMockProvider,
        )
        lastStored = point
        lastMovedAt = now

        val advanced = if (moved == Double.MAX_VALUE) 0.0 else moved
        scope.launch {
            dao.insertPoint(point)
            dao.addProgress(id, advanced, 0L)
        }
        notifyProgress()
    }

    /** Logs one piece of the given type at the current position. */
    private fun quickAdd(typeName: String) {
        val id = walkId ?: return
        val type = runCatching { LitterType.valueOf(typeName) }.getOrNull() ?: return
        val fix = lastFix
        if (fix == null) {
            // Nothing is recorded without a position: a pickup with no place
            // is not evidence of anything, and guessing one would put a piece
            // of litter on a street nobody walked.
            Log.w(TAG, "quick-add ignored: no fix yet")
            return
        }

        scope.launch {
            val at = LatLng(fix.latitude, fix.longitude)
            val nearest = nearestSegment(at)
            dao.insertPickup(
                PickupRow(
                    walkId = id,
                    at = System.currentTimeMillis(),
                    lat = fix.latitude,
                    lng = fix.longitude,
                    accuracyMeters = fix.accuracy,
                    litterType = type.name,
                    count = 1,
                    segmentId = nearest?.first?.id,
                    segmentDistanceMeters = nearest?.second,
                ),
            )
            pieces += 1
            nearest?.first?.id?.let { covered += it }
            notifyProgress()
        }
    }

    /**
     * The closest segment to a point, with how far away it was.
     *
     * Reuses the same polyline distance the map uses for tapping, so "which
     * street is this" gets the same answer in both places. The distance comes
     * back with it because a pickup forty metres from any segment is worth
     * flagging rather than silently attributing.
     */
    private fun nearestSegment(at: LatLng): Pair<Segment, Double>? =
        segments
            .asSequence()
            .map { it to distanceToPolylineMeters(at, it.path) }
            .minByOrNull { it.second }
            ?.takeIf { it.second < 60.0 }

    private fun finishWalk() {
        val id = walkId
        runCatching { locationManager.removeUpdates(listener) }
        walkId = null

        scope.launch {
            if (id != null) dao.finish(id, System.currentTimeMillis())
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        runCatching { locationManager.removeUpdates(listener) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------- the notification

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Cleanup recording",
            // Low: this is a status surface, not an interruption. It must be
            // visible for the whole walk and must never buzz.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows your walk while it is being recorded"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notifyProgress() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val elapsed = System.currentTimeMillis() - startedAt
        val minutes = elapsed / 60_000

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_walk)
            .setContentTitle("Recording a cleanup")
            .setContentText(
                "$minutes min · $pieces ${if (pieces == 1) "piece" else "pieces"} · " +
                    "${covered.size} ${if (covered.size == 1) "segment" else "segments"}",
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(
                0,
                "Finish",
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, CleanupRecordingService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )

        // Quick-add buttons for whatever this user actually picks up.
        //
        // Two, not three. Android's standard template renders at most three
        // actions and silently drops the rest, and Finish has to be one of
        // them -- a recording you cannot stop without unlocking the phone is a
        // worse failure than one fewer shortcut. Verified on device: with four
        // added, the third litter type never appeared.
        quickAddTypes.take(QUICK_ADD_BUTTONS).forEachIndexed { i, type ->
            builder.addAction(
                0,
                type.label,
                PendingIntent.getService(
                    this,
                    100 + i,
                    Intent(this, CleanupRecordingService::class.java)
                        .setAction(ACTION_QUICK_ADD)
                        .putExtra(EXTRA_LITTER_TYPE, type.name),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        return builder.build()
    }

    /**
     * The three types to offer as one-tap buttons.
     *
     * Three, because Android starts collapsing actions past that, and a button
     * you have to expand a notification to reach is no faster than opening the
     * app.
     */
    @Volatile private var quickAddTypes: List<LitterType> = DEFAULT_QUICK_ADD

    /**
     * Whatever this user actually picks up, most first.
     *
     * Falls back to the three commonest street materials for somebody who has
     * never logged anything, and tops up from that list if their history is
     * shorter than three types -- so the notification always offers three
     * buttons rather than one on a first walk.
     */
    private suspend fun resolveQuickAddTypes(): List<LitterType> {
        val fromHistory = dao.mostLoggedTypes(QUICK_ADD_BUTTONS)
            .mapNotNull { name -> runCatching { LitterType.valueOf(name) }.getOrNull() }
        return (fromHistory + DEFAULT_QUICK_ADD).distinct().take(QUICK_ADD_BUTTONS)
    }
}
