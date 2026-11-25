package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.mvvm.logError
import kotlinx.coroutines.*
import kotlin.coroutines.cancellation.CancellationException

@Throws(CancellationException::class)
suspend fun <K, V, R> Map<out K, V>.amap(f: suspend (Map.Entry<K, V>) -> R): List<R> =
    coroutineScope {
        ensureActive()
        map { async { f(it) } }.map { it.await() }
    }

@Deprecated(
    "This blocks with runBlocking, and should not be used inside a suspended context",
    replaceWith = ReplaceWith("amap(f)", "com.lagradost.cloudstream3.amap"),
    level = DeprecationLevel.WARNING,
)
@Throws(CancellationException::class)
fun <K, V, R> Map<out K, V>.apmap(f: suspend (Map.Entry<K, V>) -> R): List<R> = runBlocking {
    map { async { f(it) } }.map { it.await() }
}

@Throws(CancellationException::class)
suspend fun <A, B> List<A>.amap(f: suspend (A) -> B): List<B> =
    coroutineScope {
        ensureActive()
        map { async { f(it) } }.map { it.await() }
    }

@Deprecated(
    "This blocks with runBlocking, and should not be used inside a suspended context",
    replaceWith = ReplaceWith("amap(f)", "com.lagradost.cloudstream3.amap"),
    level = DeprecationLevel.WARNING,
)
@Throws(CancellationException::class)
fun <A, B> List<A>.apmap(f: suspend (A) -> B): List<B> = runBlocking {
    map { async { f(it) } }.map { it.await() }
}

@Throws(CancellationException::class)
suspend fun <A, B> Array<A>.amap(f: suspend (A) -> B): List<B> =
    coroutineScope {
        ensureActive()
        map { async { f(it) } }.map { it.await() }
    }

@Throws(CancellationException::class)
suspend fun <A, B> Iterable<A>.amapIndexed(f: suspend (Int, A) -> B): List<B> =
    coroutineScope {
        ensureActive()
        mapIndexed { i, v -> async { f(i, v) } }.map { it.await() }
    }
