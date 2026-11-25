package com.lagradost.nicehttp

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

interface ResponseParser {
    fun <T : Any> parse(text: String, kClass: KClass<T>): T

    fun <T : Any> parseSafe(text: String, kClass: KClass<T>): T?

    fun writeValueAsString(obj: Any): String
}

data class JsonAsString(val string: String)

object RequestBodyTypes {
    const val JSON = "application/json;charset=utf-8"
    const val TEXT = "text/plain;charset=utf-8"
}

fun requestCreator(
    method: String,
    url: String,
    headers: Map<String, String> = emptyMap(),
    referer: String? = null,
    params: Map<String, String> = emptyMap(),
    cookies: Map<String, String> = emptyMap(),
    data: Map<String, String>? = null,
    files: List<NiceFile>? = null,
    json: Any? = null,
    requestBody: okhttp3.RequestBody? = null,
    cacheTime: Int? = null,
    cacheUnit: TimeUnit? = null,
    responseParser: ResponseParser? = null,
): Request {
    return Request.Builder()
        .url(addParamsToUrl(url, params))
        .headers(getHeaders(headers, referer, cookies))
        .method(method, getData(method, data, files, json, requestBody, responseParser))
        .apply {
            if (cacheTime != null && cacheUnit != null) this.cacheControl(getCache(cacheTime, cacheUnit))
        }
        .build()
}

open class Requests(
    var baseClient: OkHttpClient = OkHttpClient(),
    var defaultHeaders: Map<String, String> = mapOf("user-agent" to "NiceHttp"),
    var defaultReferer: String? = null,
    var defaultData: Map<String, String> = emptyMap(),
    var defaultCookies: Map<String, String> = emptyMap(),
    var defaultCacheTime: Int = 0,
    var defaultCacheTimeUnit: TimeUnit = TimeUnit.MINUTES,
    var defaultTimeOut: Long = 0L,
    var responseParser: ResponseParser? = null,
) {
    companion object {
        suspend fun Call.await(): Response {
            return suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { runCatching { cancel() } }
                enqueue(object : Callback {
                    override fun onFailure(call: Call, e: java.io.IOException) {
                        if (!cont.isCompleted) cont.resumeWith(Result.failure(e))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (!cont.isCompleted) cont.resumeWith(Result.success(response))
                    }
                })
            }
        }
    }

    protected open fun buildClient(
        timeout: Long,
        allowRedirects: Boolean,
        verify: Boolean,
        interceptor: okhttp3.Interceptor?,
        cacheTime: Int,
    ): OkHttpClient {
        val builder = baseClient.newBuilder()
            .followRedirects(allowRedirects)
            .followSslRedirects(allowRedirects)
            .callTimeout(timeout.coerceAtLeast(1L).let { if (timeout > 0) timeout else 60L }, TimeUnit.SECONDS)
        if (timeout > 0) {
            builder.connectTimeout(timeout, TimeUnit.SECONDS)
                .writeTimeout(timeout, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
        }
        if (!verify) builder.ignoreAllSSLErrors()
        if (interceptor != null) builder.addInterceptor(interceptor)
        return builder.build()
    }

    protected open suspend fun execute(request: Request): Response {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            baseClient.newCall(request).execute()
        }
    }

    open suspend fun custom(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        params: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
        data: Map<String, String>? = defaultData,
        files: List<NiceFile>? = null,
        json: Any? = null,
        requestBody: okhttp3.RequestBody? = null,
        allowRedirects: Boolean = true,
        cacheTime: Int = defaultCacheTime,
        cacheUnit: TimeUnit = defaultCacheTimeUnit,
        timeout: Long = defaultTimeOut,
        interceptor: okhttp3.Interceptor? = null,
        verify: Boolean = true,
        responseParser: ResponseParser? = this.responseParser,
    ): NiceResponse {
        val client = buildClient(timeout, allowRedirects, verify, interceptor, cacheTime)
        val request = requestCreator(method, url, defaultHeaders + headers, referer ?: defaultReferer, params, defaultCookies + cookies, data, files, json, requestBody, cacheTime, cacheUnit, responseParser)
        val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            client.newCall(request).execute()
        }
        return NiceResponse(response, responseParser)
    }

    suspend fun get(
        url: String,
        headers: Map<String, String> = mapOf(),
        referer: String? = null,
        params: Map<String, String> = mapOf(),
        cookies: Map<String, String> = mapOf(),
        allowRedirects: Boolean = true,
        cacheTime: Int = defaultCacheTime,
        cacheUnit: TimeUnit = defaultCacheTimeUnit,
        timeout: Long = defaultTimeOut,
        interceptor: okhttp3.Interceptor? = null,
        verify: Boolean = true,
        responseParser: ResponseParser? = this.responseParser,
    ): NiceResponse {
        return custom("GET", url, headers, referer, params, cookies, null, null, null, null, allowRedirects, cacheTime, cacheUnit, timeout, interceptor, verify, responseParser)
    }

    suspend fun post(
        url: String,
        headers: Map<String, String> = mapOf(),
        referer: String? = null,
        params: Map<String, String> = mapOf(),
        cookies: Map<String, String> = mapOf(),
        data: Map<String, String>? = defaultData,
        files: List<NiceFile>? = null,
        json: Any? = null,
        requestBody: okhttp3.RequestBody? = null,
        allowRedirects: Boolean = true,
        cacheTime: Int = defaultCacheTime,
        cacheUnit: TimeUnit = defaultCacheTimeUnit,
        timeout: Long = defaultTimeOut,
        interceptor: okhttp3.Interceptor? = null,
        verify: Boolean = true,
        responseParser: ResponseParser? = this.responseParser,
    ): NiceResponse {
        return custom("POST", url, headers, referer, params, cookies, data, files, json, requestBody, allowRedirects, cacheTime, cacheUnit, timeout, interceptor, verify, responseParser)
    }

    suspend fun put(
        url: String,
        headers: Map<String, String> = mapOf(),
        referer: String? = null,
        params: Map<String, String> = mapOf(),
        cookies: Map<String, String> = mapOf(),
        data: Map<String, String>? = defaultData,
        files: List<NiceFile>? = null,
        json: Any? = null,
        requestBody: okhttp3.RequestBody? = null,
        allowRedirects: Boolean = true,
        cacheTime: Int = defaultCacheTime,
        cacheUnit: TimeUnit = defaultCacheTimeUnit,
        timeout: Long = defaultTimeOut,
        interceptor: okhttp3.Interceptor? = null,
        verify: Boolean = true,
        responseParser: ResponseParser? = this.responseParser,
    ): NiceResponse {
        return custom("PUT", url, headers, referer, params, cookies, data, files, json, requestBody, allowRedirects, cacheTime, cacheUnit, timeout, interceptor, verify, responseParser)
    }

    suspend fun delete(
        url: String,
        headers: Map<String, String> = mapOf(),
        referer: String? = null,
        params: Map<String, String> = mapOf(),
        cookies: Map<String, String> = mapOf(),
        data: Map<String, String>? = defaultData,
        files: List<NiceFile>? = null,
        json: Any? = null,
        requestBody: okhttp3.RequestBody? = null,
        allowRedirects: Boolean = true,
        cacheTime: Int = defaultCacheTime,
        cacheUnit: TimeUnit = defaultCacheTimeUnit,
        timeout: Long = defaultTimeOut,
        interceptor: okhttp3.Interceptor? = null,
        verify: Boolean = true,
        responseParser: ResponseParser? = this.responseParser,
    ): NiceResponse {
        return custom("DELETE", url, headers, referer, params, cookies, data, files, json, requestBody, allowRedirects, cacheTime, cacheUnit, timeout, interceptor, verify, responseParser)
    }

    suspend fun head(
        url: String,
        headers: Map<String, String> = mapOf(),
        referer: String? = null,
        params: Map<String, String> = mapOf(),
        cookies: Map<String, String> = mapOf(),
        allowRedirects: Boolean = true,
        cacheTime: Int = defaultCacheTime,
        cacheUnit: TimeUnit = defaultCacheTimeUnit,
        timeout: Long = defaultTimeOut,
        interceptor: okhttp3.Interceptor? = null,
        verify: Boolean = true,
        responseParser: ResponseParser? = this.responseParser,
    ): NiceResponse {
        return custom("HEAD", url, headers, referer, params, cookies, null, null, null, null, allowRedirects, cacheTime, cacheUnit, timeout, interceptor, verify, responseParser)
    }

    suspend fun patch(
        url: String,
        headers: Map<String, String> = mapOf(),
        referer: String? = null,
        params: Map<String, String> = mapOf(),
        cookies: Map<String, String> = mapOf(),
        data: Map<String, String>? = defaultData,
        files: List<NiceFile>? = null,
        json: Any? = null,
        requestBody: okhttp3.RequestBody? = null,
        allowRedirects: Boolean = true,
        cacheTime: Int = defaultCacheTime,
        cacheUnit: TimeUnit = defaultCacheTimeUnit,
        timeout: Long = defaultTimeOut,
        interceptor: okhttp3.Interceptor? = null,
        verify: Boolean = true,
        responseParser: ResponseParser? = this.responseParser,
    ): NiceResponse {
        return custom("PATCH", url, headers, referer, params, cookies, data, files, json, requestBody, allowRedirects, cacheTime, cacheUnit, timeout, interceptor, verify, responseParser)
    }

    suspend fun options(
        url: String,
        headers: Map<String, String> = mapOf(),
        referer: String? = null,
        params: Map<String, String> = mapOf(),
        cookies: Map<String, String> = mapOf(),
        data: Map<String, String>? = defaultData,
        files: List<NiceFile>? = null,
        json: Any? = null,
        requestBody: okhttp3.RequestBody? = null,
        allowRedirects: Boolean = true,
        cacheTime: Int = defaultCacheTime,
        cacheUnit: TimeUnit = defaultCacheTimeUnit,
        timeout: Long = defaultTimeOut,
        interceptor: okhttp3.Interceptor? = null,
        verify: Boolean = true,
        responseParser: ResponseParser? = this.responseParser,
    ): NiceResponse {
        return custom("OPTIONS", url, headers, referer, params, cookies, data, files, json, requestBody, allowRedirects, cacheTime, cacheUnit, timeout, interceptor, verify, responseParser)
    }

    private fun fromBuilder(method: String, url: String, b: RequestBuilder): NiceResponseParams {
        return NiceResponseParams(
            method = method,
            url = url,
            headers = b.headers,
            referer = b.referer,
            params = b.params,
            cookies = b.cookies,
            data = b.data,
            files = b.files,
            json = b.json,
            requestBody = b.requestBody,
            allowRedirects = b.allowRedirects,
            cacheSeconds = b.cacheTime.inWholeSeconds.toInt().coerceAtLeast(0),
            timeoutSeconds = b.timeout.inWholeSeconds.coerceAtLeast(0L),
            interceptor = null,
            verify = b.verify,
            responseParser = b.responseParser,
        )
    }

    private suspend fun runParams(p: NiceResponseParams): NiceResponse {
        return custom(
            p.method, p.url, p.headers, p.referer, p.params, p.cookies, p.data, p.files, p.json,
            p.requestBody, p.allowRedirects, p.cacheSeconds, TimeUnit.SECONDS, p.timeoutSeconds, null, p.verify, p.responseParser,
        )
    }

    open suspend fun custom(method: String, url: String, block: RequestBuilder.() -> Unit = {}): NiceResponse {
        val b = RequestBuilder(this, block)
        return runParams(fromBuilder(method, url, b))
    }

    suspend fun get(url: String, block: RequestBuilder.() -> Unit = {}): NiceResponse {
        val b = RequestBuilder(this, block)
        return runParams(fromBuilder("GET", url, b))
    }

    suspend fun post(url: String, block: RequestBuilder.() -> Unit = {}): NiceResponse {
        val b = RequestBuilder(this, block)
        return runParams(fromBuilder("POST", url, b))
    }

    suspend fun put(url: String, block: RequestBuilder.() -> Unit = {}): NiceResponse {
        val b = RequestBuilder(this, block)
        return runParams(fromBuilder("PUT", url, b))
    }

    suspend fun delete(url: String, block: RequestBuilder.() -> Unit = {}): NiceResponse {
        val b = RequestBuilder(this, block)
        return runParams(fromBuilder("DELETE", url, b))
    }

    suspend fun head(url: String, block: RequestBuilder.() -> Unit = {}): NiceResponse {
        val b = RequestBuilder(this, block)
        return runParams(fromBuilder("HEAD", url, b))
    }

    suspend fun patch(url: String, block: RequestBuilder.() -> Unit = {}): NiceResponse {
        val b = RequestBuilder(this, block)
        return runParams(fromBuilder("PATCH", url, b))
    }

    suspend fun options(url: String, block: RequestBuilder.() -> Unit = {}): NiceResponse {
        val b = RequestBuilder(this, block)
        return runParams(fromBuilder("OPTIONS", url, b))
    }

    private data class NiceResponseParams(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val referer: String?,
        val params: Map<String, String>,
        val cookies: Map<String, String>,
        val data: Map<String, String>?,
        val files: List<NiceFile>?,
        val json: Any?,
        val requestBody: RequestBody?,
        val allowRedirects: Boolean,
        val cacheSeconds: Int,
        val timeoutSeconds: Long,
        val interceptor: okhttp3.Interceptor?,
        val verify: Boolean,
        val responseParser: ResponseParser?,
    )
}
