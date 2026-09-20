package org.softosaurus.reactionspeed.games

import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Minimal `Task<T>` -> coroutine bridge.
 *
 * `kotlinx-coroutines-play-services` (which would give us `Task.await()`) is *not* a dependency of
 * this project and pulling it in for two suspend functions is not worth a new artifact. This
 * extension is `internal` and named `awaitResult` rather than `await` precisely so that adding
 * that library later cannot produce an ambiguous-overload clash.
 *
 * Cancelling the coroutine does not cancel the underlying [Task] (GMS tasks are not cancellable
 * from the outside); it only detaches the continuation.
 */
@Suppress("UNCHECKED_CAST")
internal suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val error = task.exception
        when {
            task.isCanceled -> cont.cancel()
            error != null -> cont.resumeWithException(error)
            else -> cont.resume(task.result as T)
        }
    }
}
