package com.lagradost.nicehttp

import okhttp3.*
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private val mustHaveBody = listOf("POST", "PUT")
private val cantHaveBody = listOf("GET", "HEAD")

fun getData(
    method: String,
    data: Map<String, String>?,
    files: List<NiceFile>?,
    json: Any?,
    requestBody: okhttp3.RequestBody?,
    responseParser: ResponseParser?,
): okhttp3.RequestBody? {
    if (cantHaveBody.contains(method.uppercase())) return null
    if (requestBody != null) return requestBody

    val body = if (!data.isNullOrEmpty()) {
        val builder = FormBody.Builder()
        data.forEach { builder.addEncoded(it.key, it.value) }
        builder.build()
    } else if (json != null) {
        val jsonString = when {
            json is JSONObject -> json.toString()
            json is JSONArray -> json.toString()
            json is String -> json
            json is JsonAsString -> json.string
            else -> (responseParser?.writeValueAsString(json) ?: json.toString())
        }
        jsonString.toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
    } else if (!files.isNullOrEmpty()) {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        files.forEach { file ->
            val f = file.file
            if (f != null && f.exists()) {
                builder.addFormDataPart(file.name, file.fileName, f.asRequestBody(file.fileType?.toMediaTypeOrNull()))
            } else {
                builder.addFormDataPart(file.name, file.fileName)
            }
        }
        builder.build()
    } else {
        if (mustHaveBody.contains(method.uppercase())) {
            byteArrayOf().toRequestBody(null)
        } else {
            null
        }
    }
    return body
}

fun getHeaders(headers: Map<String, String>, referer: String?, cookies: Map<String, String>): Headers {
    val map = headers.toMutableMap()
    if (referer != null) map["referer"] = referer
    if (cookies.isNotEmpty()) {
        val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        map["Cookie"] = listOfNotNull(map["Cookie"], cookieHeader).joinToString("; ")
    }
    return map.toHeaders()
}

fun OkHttpClient.Builder.ignoreAllSSLErrors(): OkHttpClient.Builder {
    return try {
        val trustAll = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            },
        )
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, trustAll, SecureRandom())
        sslSocketFactory(ssl.socketFactory, trustAll[0] as X509TrustManager)
        hostnameVerifier { _, _ -> true }
        this
    } catch (t: Throwable) {
        this
    }
}

fun Headers.getCookies(cookieKey: String): Map<String, String> {
    return values(cookieKey).flatMap { it.split(";") }.mapNotNull {
        val parts = it.split("=", limit = 2)
        val k = parts.getOrNull(0)?.trim() ?: return@mapNotNull null
        val v = parts.getOrNull(1)?.trim() ?: return@mapNotNull null
        if (k.isBlank() || v.isBlank()) null else k to v
    }.toMap()
}

internal fun appendUri(uri: String, appendQuery: String): String {
    return if (uri.contains("?")) "$uri&$appendQuery" else "$uri?$appendQuery"
}

internal fun addParamsToUrl(url: String, params: Map<String, String?>): String {
    if (params.isEmpty()) return url
    val query = params.entries.joinToString("&") { (k, v) ->
        "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v ?: "", "UTF-8")}"
    }
    return appendUri(url, query)
}

internal fun getCache(cacheTime: Int, cacheUnit: TimeUnit): CacheControl {
    return CacheControl.Builder().maxAge(cacheTime, cacheUnit).build()
}
