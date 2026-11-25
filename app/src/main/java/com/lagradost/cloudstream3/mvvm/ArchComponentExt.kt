package com.lagradost.cloudstream3.mvvm

import com.lagradost.api.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

const val DEBUG_EXCEPTION = "THIS IS A DEBUG EXCEPTION!"
const val DEBUG_PRINT = "DEBUG PRINT"

class DebugException(message: String) : Exception("$DEBUG_EXCEPTION\n$message")

fun logError(throwable: Throwable) {
    Log.d("ApiError", "-------------------------------------------------------------------")
    Log.d("ApiError", "safeApiCall: " + throwable.message)
    throwable.printStackTrace()
    Log.d("ApiError", "-------------------------------------------------------------------")
}

@Deprecated("Outdated function, use `safe` instead", replaceWith = ReplaceWith("safe"), level = DeprecationLevel.ERROR)
fun <T> normalSafeApiCall(apiCall: () -> T): T? {
    return try {
        apiCall.invoke()
    } catch (throwable: Throwable) {
        logError(throwable)
        null
    }
}

fun <T> safe(apiCall: () -> T): T? {
    return try {
        apiCall.invoke()
    } catch (throwable: Throwable) {
        logError(throwable)
        null
    }
}

suspend fun <T> safeAsync(apiCall: suspend () -> T): T? {
    return try {
        apiCall.invoke()
    } catch (throwable: Throwable) {
        logError(throwable)
        null
    }
}

@Deprecated("Outdated function, use `safeAsync` instead", replaceWith = ReplaceWith("safeAsync"), level = DeprecationLevel.ERROR)
suspend fun <T> suspendSafeApiCall(apiCall: suspend () -> T): T? {
    return try {
        apiCall.invoke()
    } catch (throwable: Throwable) {
        logError(throwable)
        null
    }
}

fun Throwable.getAllMessages(): String {
    return (this.message ?: "") + (this.cause?.getAllMessages()?.let { "\n$it" } ?: "")
}

fun Throwable.getStackTracePretty(showMessage: Boolean = true): String {
    val prefix = if (showMessage) this.message?.let { "$it\n" } ?: "" else ""
    return prefix + this.stackTraceToString()
        .lines()
        .mapNotNull { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("at ")) return@mapNotNull null
            Regex("""\(([^)]+)\)$""").find(trimmed)?.groupValues?.get(1)?.replace(":", " ")
        }
        .joinToString("\n")
}

fun <T> safeFail(throwable: Throwable): Resource<T> {
    val stackTraceMsg = throwable.getStackTracePretty()
    return Resource.Failure(false, stackTraceMsg)
}

fun CoroutineScope.launchSafe(
    context: CoroutineContext = EmptyCoroutineContext,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: suspend CoroutineScope.() -> Unit,
): Job {
    val obj: suspend CoroutineScope.() -> Unit = {
        try {
            block()
        } catch (throwable: Throwable) {
            logError(throwable)
        }
    }
    return this.launch(context, start, obj)
}

fun <T> platformThrowAbleToResource(throwable: Throwable): Resource<T> {
    return throwAbleToResource(throwable)
}

fun <T> throwAbleToResource(throwable: Throwable): Resource<T> {
    return when (throwable) {
        is ErrorLoadingException -> Resource.Failure(false, throwable.message ?: "Load error")
        else -> Resource.Failure(false, throwable.getStackTracePretty())
    }
}

sealed class Resource<out T> {
    data class Success<out T>(val value: T) : Resource<T>()
    data class Failure(val isNetworkError: Boolean, val errorString: String) : Resource<Nothing>()
    data class Loading(val url: String? = null) : Resource<Nothing>()

    companion object {
        fun <T> fromResult(result: Result<T>): Resource<T> {
            val value = result.getOrNull()
            return if (value != null) {
                Success(value)
            } else {
                throwAbleToResource(result.exceptionOrNull() ?: Exception("this should not be possible"))
            }
        }
    }
}
