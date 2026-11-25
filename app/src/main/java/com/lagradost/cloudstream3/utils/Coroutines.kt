package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.mvvm.launchSafe
import com.lagradost.cloudstream3.mvvm.logError
import kotlinx.coroutines.*

fun runOnMainThreadNative(work: (() -> Unit)) {
    javax.swing.SwingUtilities.invokeLater { runCatching { work() } }
}

val workerDispatcher: CoroutineDispatcher = Dispatchers.IO

annotation class WorkerThread

object Coroutines {
    fun <T> T.main(work: suspend ((T) -> Unit)): Job {
        val value = this
        return CoroutineScope(Dispatchers.Main).launchSafe { work(value) }
    }

    fun <T> T.ioSafe(work: suspend CoroutineScope.(T) -> Unit): Job {
        val value = this
        return CoroutineScope(workerDispatcher).launchSafe { work(value) }
    }

    suspend fun <T, V> V.ioWorkSafe(work: suspend CoroutineScope.(V) -> T): T? {
        val value = this
        return withContext(workerDispatcher) {
            try {
                work(value)
            } catch (e: Exception) {
                logError(e)
                null
            }
        }
    }

    suspend fun <T, V> V.ioWork(work: suspend CoroutineScope.(V) -> T): T {
        val value = this
        return withContext(workerDispatcher) { work(value) }
    }

    suspend fun <T, V> V.mainWork(work: suspend CoroutineScope.(V) -> T): T {
        val value = this
        return withContext(Dispatchers.Main) { work(value) }
    }

    fun runOnMainThread(work: (() -> Unit)) {
        runOnMainThreadNative(work)
    }

    fun <T> atomicListOf(vararg items: T): AtomicMutableList<T> {
        return AtomicMutableList(items.toMutableList())
    }

    @Deprecated(
        message = "Use atomicListOf() instead.",
        replaceWith = ReplaceWith("atomicListOf(*items)"),
        level = DeprecationLevel.WARNING,
    )
    fun <T> threadSafeListOf(vararg items: T): MutableList<T> = atomicListOf(*items)
}
