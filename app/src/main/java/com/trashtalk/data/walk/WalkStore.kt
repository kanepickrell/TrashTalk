package com.trashtalk.data.walk

import android.content.Context
import androidx.room.Room
import com.trashtalk.data.NetworkAssetLoader
import com.trashtalk.data.model.Segment

/**
 * The walk database and the geometry the recorder stamps against.
 *
 * A plain object rather than an injected dependency because the recording
 * service is started by the system -- after a process death, nothing of ours is
 * there to hand it anything. It has to be able to find these on its own.
 */
object WalkStore {

    @Volatile private var db: WalkDatabase? = null
    @Volatile private var network: List<Segment>? = null

    fun database(context: Context): WalkDatabase =
        db ?: synchronized(this) {
            db ?: Room.databaseBuilder(
                context.applicationContext,
                WalkDatabase::class.java,
                "walks.db",
            ).build().also { db = it }
        }

    /**
     * The street network, parsed once.
     *
     * The service needs it to stamp pickups, and it is the same bundled asset
     * the map reads, so a pickup's segment is the same segment the user can
     * see themselves standing on.
     */
    fun segments(context: Context): List<Segment> =
        network ?: synchronized(this) {
            network ?: NetworkAssetLoader.load(context).segments.also { network = it }
        }
}
