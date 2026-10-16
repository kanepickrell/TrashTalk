package com.trashtalk

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.trashtalk.data.NetworkAssetLoader
import com.trashtalk.data.NeighborhoodNetwork
import com.trashtalk.data.TrashTalkRepository
import com.trashtalk.data.demo.DemoRepository
import com.trashtalk.data.firebase.FirestoreRepository

class TrashTalkApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/**
 * Hand-rolled dependency wiring. One graph, created once, no framework: the object
 * count here does not justify the build-time cost or the indirection of a
 * dependency-injection library.
 *
 * The backend choice happens here and nowhere else. [FirebaseApp.initializeApp]
 * returns null when no `google-services.json` was present at build time, which is
 * a reliable signal -- so the app falls back to the seeded on-device backend
 * instead of crashing or showing empty screens. Drop the config file in and the
 * same build switches to live Firestore with no code change.
 */
class AppContainer(private val app: Application) {

    val network: NeighborhoodNetwork by lazy {
        NetworkAssetLoader.load(app)
    }

    val repository: TrashTalkRepository by lazy {
        val firebase = runCatching { FirebaseApp.initializeApp(app) }
            .onFailure { Log.w(TAG, "Firebase initialisation threw; using demo data", it) }
            .getOrNull()

        if (firebase != null && BuildConfig.FIREBASE_CONFIGURED) {
            runCatching {
                FirestoreRepository(
                    network = network,
                    auth = FirebaseAuth.getInstance(),
                    db = FirebaseFirestore.getInstance(),
                ).also { Log.i(TAG, "backend: live Firestore") }
            }.getOrElse {
                Log.w(TAG, "Firestore unavailable; using demo data", it)
                DemoRepository(app, network)
            }
        } else {
            Log.i(TAG, "backend: on-device demo data (no google-services.json)")
            DemoRepository(app, network)
        }
    }

    private companion object {
        const val TAG = "TrashTalk"
    }
}
