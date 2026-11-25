package com.lagradost.nicehttp

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

val Response.cookies: Map<String, String>
    get() = this.headers.getCookies("set-cookie")

val okhttp3.Request.cookies: Map<String, String>
    get() = this.headers.getCookies("Cookie")

class NiceResponse(val okhttpResponse: Response, val parser: ResponseParser?) {
    companion object {
        const val MAX_TEXT_SIZE: Long = 5_000_000L
    }

    private val rawBytes: ByteArray by lazy {
        val body = okhttpResponse.body
        try {
            body?.bytes() ?: ByteArray(0)
        } finally {
            runCatching { body?.close() }
        }
    }

    val text: String by lazy {
        val size = size
        if (size != null && size > MAX_TEXT_SIZE) {
            throw IllegalStateException(
                "Called .text on a text file with Content-Length header > $MAX_TEXT_SIZE bytes, this throws an exception to prevent OOM. " +
                    "If you know what you are doing, use .body to download larger files",
            )
        }
        rawBytes.toString(Charsets.UTF_8)
    }

    val url by lazy { okhttpResponse.request.url.toString() }
    val cookies by lazy { okhttpResponse.cookies }

    val body: okhttp3.ResponseBody? by lazy {
        rawBytes.toResponseBodyLenient()
    }

    suspend fun body(): NiceBody = NiceBody(rawBytes)

    val code: Int = okhttpResponse.code
    val headers: okhttp3.Headers = okhttpResponse.headers

    val size: Long? by lazy {
        okhttpResponse.headers["content-length"]?.toLongOrNull()
    }

    val isSuccessful: Boolean = okhttpResponse.isSuccessful

    val document: Document by lazy { Jsoup.parse(text) }

    fun header(name: String): String? = headers[name]

    inline fun <reified T : Any> parsed(): T {
        return parser!!.parse(this.text, T::class)
    }

    inline fun <reified T : Any> parsedSafe(): T? {
        return try {
            parser!!.parseSafe(this.text, T::class)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend inline fun <reified T : Any> parsedSuspended(): T {
        return parser!!.parse(text(), T::class)
    }

    suspend inline fun <reified T : Any> parsedSafeSuspended(): T? {
        return try {
            parser!!.parseSafe(text(), T::class)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun text(): String {
        val size = size
        if (size != null && size > MAX_TEXT_SIZE) {
            throw IllegalStateException("Response exceeded $MAX_TEXT_SIZE bytes. Use textLarge() instead.")
        }
        return rawBytes.toString(Charsets.UTF_8)
    }

    suspend fun textLarge(): String = rawBytes.toString(Charsets.UTF_8)

    suspend fun documentLarge(): Document = Jsoup.parse(textLarge())

    inline fun <reified T : Any> parsedLarge(): T {
        return parser!!.parse(this.textLarge, T::class)
    }

    inline fun <reified T : Any> parsedSafeLarge(): T? {
        return try {
            parser!!.parseSafe(this.textLarge, T::class)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend inline fun <reified T : Any> parsedLargeSuspended(): T {
        return parser!!.parse(textLarge(), T::class)
    }

    suspend inline fun <reified T : Any> parsedSafeLargeSuspended(): T? {
        return try {
            parser!!.parseSafe(textLarge(), T::class)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    val textLarge: String by lazy { rawBytes.toString(Charsets.UTF_8) }

    val documentLarge: Document by lazy { Jsoup.parse(textLarge) }

    override fun toString(): String = text
}

class NiceBody(private val data: ByteArray) {
    fun bytes(): ByteArray = data
    fun string(): String = data.toString(Charsets.UTF_8)
    fun contentLength(): Long = data.size.toLong()
    fun byteStream(): java.io.InputStream = java.io.ByteArrayInputStream(data)
    fun close() = Unit
}

private fun ByteArray.toResponseBodyLenient(): okhttp3.ResponseBody {
    val bytes = this
    val type = "application/octet-stream".toMediaTypeOrNull()
    return object : okhttp3.ResponseBody() {
        override fun contentType(): okhttp3.MediaType? = type
        override fun contentLength(): Long = bytes.size.toLong()
        override fun source(): okio.BufferedSource = okio.Buffer().write(bytes)
    }
}
