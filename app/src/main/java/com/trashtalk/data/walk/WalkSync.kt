package com.trashtalk.data.walk

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.firestore.FirebaseFirestoreException
import com.trashtalk.TrashTalkApp
import com.trashtalk.data.SubmissionRejected
import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "WalkSync"
private const val WORK_NAME = "walk-sync"

/**
 * The offline queue.
 *
 * Walks happen where the signal is worst -- that is more or less the definition
 * of a neglected street -- so submission cannot be something that only works
 * when the user is standing still with four bars. A finished walk is durable in
 * Room from the moment it ends; this drains it when the network allows and
 * tells the user where it stands in the meantime.
 *
 * Retries are safe because every observation carries a client-generated id, so
 * the server recognises a repeat instead of counting it twice. That property is
 * what makes a queue possible at all: without it, "retry on timeout" and
 * "double-score a cleanup" are the same action.
 */
object WalkSync {

    /**
     * Asks for a drain.
     *
     * Unique work with KEEP, so finishing three walks in a row queues one
     * drain rather than three that fight over the same rows.
     */
    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<WalkSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    // Not UNMETERED: a cleanup is a few hundred bytes and the
                    // user wants it to land, not to wait for wifi.
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }
}

/**
 * Why a submission did not land, and whether trying again could help.
 *
 * The distinction matters more than it looks: retrying something permanently
 * broken burns battery for ever, and giving up on something transient throws
 * away a walk over a dropped packet.
 */
private sealed interface Failure {
    /** Worth another go later. */
    data class Transient(val why: String) : Failure

    /** Will never succeed; tell the user plainly and stop. */
    data class Permanent(val reason: String) : Failure
}

private fun classify(cause: Throwable): Failure = when {
    // The server looked at this and said no, in words meant for the user.
    // Retrying will get the same answer, and replacing its reason with a
    // generic one would throw away the only useful thing about the refusal.
    cause is SubmissionRejected ->
        Failure.Permanent(cause.message ?: "This cleanup could not be scored.")

    cause is TimeoutCancellationException ->
        Failure.Transient("timed out")

    cause is IOException ->
        Failure.Transient("network")

    cause is FirebaseFirestoreException -> when (cause.code) {
        // The server is unreachable, busy, or the transaction lost a race.
        FirebaseFirestoreException.Code.UNAVAILABLE,
        FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
        FirebaseFirestoreException.Code.ABORTED,
        FirebaseFirestoreException.Code.INTERNAL,
        FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED,
        FirebaseFirestoreException.Code.CANCELLED,
        -> Failure.Transient(cause.code.name)

        // Rules refused it, the data is malformed, or we are not signed in.
        // None of those get better by asking again.
        FirebaseFirestoreException.Code.PERMISSION_DENIED ->
            Failure.Permanent("This cleanup was refused by the server.")

        FirebaseFirestoreException.Code.UNAUTHENTICATED ->
            Failure.Permanent("You were signed out before this could be saved.")

        FirebaseFirestoreException.Code.INVALID_ARGUMENT,
        FirebaseFirestoreException.Code.FAILED_PRECONDITION,
        FirebaseFirestoreException.Code.OUT_OF_RANGE,
        -> Failure.Permanent("This cleanup could not be read by the server.")

        else -> Failure.Transient(cause.code.name)
    }

    else -> Failure.Transient(cause::class.java.simpleName)
}

/**
 * Drains pending walks, oldest first.
 *
 * Oldest first because that is the order the work happened in, and because a
 * walk that has been waiting longest is the one the user is most likely
 * wondering about.
 */
class WalkSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? TrashTalkApp ?: return Result.failure()
        val dao = WalkStore.database(applicationContext).walks()
        val submitter = WalkSubmitter(applicationContext, WalkRepository(applicationContext), dao)
        val network = WalkStore.segments(applicationContext)

        val pending = dao.pendingWalks()
        if (pending.isEmpty()) return Result.success()

        // Sign in first. WorkManager can start this in a process where nothing
        // else has run -- after a reboot, or after the app was killed in the
        // background -- and the repository refuses to write without a uid. The
        // symptom was a worker that retried for ever on an IllegalStateException
        // whose message was "not signed in".
        val signedIn = runCatching { app.container.repository.ensureSignedIn() }
        if (signedIn.isFailure) {
            Log.i(TAG, "cannot sign in yet; will retry")
            return Result.retry()
        }

        var retryNeeded = false

        for (walk in pending) {
            val summary = submitter.summarise(walk.id, network)
            if (summary == null || !summary.hasAnything) {
                // Nothing to submit. Not a failure, and not worth keeping.
                dao.deleteWalk(walk.id)
                continue
            }

            dao.setStatus(walk.id, WalkStatus.SYNCING)
            val result = runCatching {
                // The haul photo comes off the walk row, not from a caller:
                // the finish screen is long gone by the time a retry happens,
                // and it used to be dropped silently on every queued retry.
                submitter.submit(summary, app.container.repository)
            }

            result
                .onSuccess { Log.i(TAG, "submitted ${walk.id}: ${it.pointsAwarded} pts") }
                .onFailure { cause ->
                    when (val failure = classify(cause)) {
                        is Failure.Transient -> {
                            // Back to PENDING so the next drain picks it up,
                            // rather than stranding it in SYNCING for ever.
                            dao.setStatus(walk.id, WalkStatus.PENDING)
                            retryNeeded = true
                            Log.i(TAG, "will retry ${walk.id}: ${failure.why}")
                        }

                        is Failure.Permanent -> {
                            dao.applyServerResult(
                                walkId = walk.id,
                                status = WalkStatus.REJECTED,
                                reason = failure.reason,
                                points = null,
                            )
                            Log.w(TAG, "rejected ${walk.id}: ${failure.reason}")
                        }
                    }
                }
        }

        return if (retryNeeded) Result.retry() else Result.success()
    }
}
