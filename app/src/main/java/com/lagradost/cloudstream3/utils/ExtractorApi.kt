package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AudioFile
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.IDownloadableMinimum
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class ExtractorLinkType {
    VIDEO,
    M3U8,
    DASH,
    TORRENT,
    MAGNET;

    fun getMimeType(): String {
        return when (this) {
            VIDEO -> "video/mp4"
            M3U8 -> "application/x-mpegURL"
            DASH -> "application/dash+xml"
            TORRENT -> "application/x-bittorrent"
            MAGNET -> "application/x-bittorrent"
        }
    }
}

private fun inferTypeFromUrl(url: String): ExtractorLinkType {
    return try {
        val path = java.net.URI(url).path ?: ""
        when {
            path.endsWith(".m3u8") -> ExtractorLinkType.M3U8
            path.endsWith(".mpd") -> ExtractorLinkType.DASH
            path.endsWith(".torrent") -> ExtractorLinkType.TORRENT
            url.startsWith("magnet:") -> ExtractorLinkType.MAGNET
            else -> ExtractorLinkType.VIDEO
        }
    } catch (_: Throwable) {
        if (url.startsWith("magnet:")) ExtractorLinkType.MAGNET else ExtractorLinkType.VIDEO
    }
}

val INFER_TYPE: ExtractorLinkType? = null

val CLEARKEY_DRM_UUID = kotlin.uuid.Uuid.fromLongs(-0x1d8e62a7567a4c37L, 0x781AB030AF78D30EL)
val WIDEVINE_DRM_UUID = kotlin.uuid.Uuid.fromLongs(-0x121074568629b532L, -0x5c37d8232ae2de13L)
val PLAYREADY_DRM_UUID = kotlin.uuid.Uuid.fromLongs(-0x65fb0f8667bfbd7aL, -0x546d19a41f77a06bL)

val CLEARKEY_UUID = java.util.UUID(CLEARKEY_DRM_UUID.mostSignificantBits, CLEARKEY_DRM_UUID.leastSignificantBits)
val WIDEVINE_UUID = java.util.UUID(WIDEVINE_DRM_UUID.mostSignificantBits, WIDEVINE_DRM_UUID.leastSignificantBits)
val PLAYREADY_UUID = java.util.UUID(PLAYREADY_DRM_UUID.mostSignificantBits, PLAYREADY_DRM_UUID.leastSignificantBits)

fun kotlin.uuid.Uuid.toJavaUuid(): java.util.UUID =
    java.util.UUID(mostSignificantBits, leastSignificantBits)

fun java.util.UUID.toKotlinUuid(): kotlin.uuid.Uuid =
    kotlin.uuid.Uuid.fromLongs(mostSignificantBits, leastSignificantBits)

suspend fun newExtractorLink(
    source: String,
    name: String,
    url: String,
    type: ExtractorLinkType? = null,
    initializer: suspend ExtractorLink.() -> Unit = { },
): ExtractorLink {
    @Suppress("DEPRECATION_ERROR")
    val builder = ExtractorLink(source = source, name = name, url = url, type = type ?: INFER_TYPE)
    builder.initializer()
    return builder
}

suspend fun newDrmExtractorLink(
    source: String,
    name: String,
    url: String,
    type: ExtractorLinkType? = null,
    uuid: java.util.UUID,
    initializer: suspend DrmExtractorLink.() -> Unit = { },
): DrmExtractorLink {
    @Suppress("DEPRECATION_ERROR")
    val builder = DrmExtractorLink(
        source = source, name = name, url = url,
        uuid = kotlin.uuid.Uuid.fromLongs(uuid.mostSignificantBits, uuid.leastSignificantBits),
        type = type ?: INFER_TYPE,
    )
    builder.initializer()
    return builder
}

suspend fun newDrmExtractorLink(
    source: String,
    name: String,
    url: String,
    type: ExtractorLinkType? = null,
    uuid: kotlin.uuid.Uuid,
    initializer: suspend DrmExtractorLink.() -> Unit = {},
): DrmExtractorLink {
    @Suppress("DEPRECATION_ERROR")
    val builder = DrmExtractorLink(source = source, name = name, url = url, uuid = uuid, type = type ?: INFER_TYPE)
    builder.initializer()
    return builder
}

@Suppress("DEPRECATION")
open class DrmExtractorLink private constructor(
    override val source: String,
    override val name: String,
    override val url: String,
    override var referer: String,
    override var quality: Int,
    override var headers: Map<String, String> = mapOf(),
    override var extractorData: String? = null,
    override var type: ExtractorLinkType,
    open var kid: String? = null,
    open var key: String? = null,
    open var uuid: kotlin.uuid.Uuid,
    open var kty: String? = null,
    open var keyRequestParameters: HashMap<String, String>,
    open var licenseUrl: String? = null,
    override var audioTracks: List<AudioFile> = emptyList(),
) : ExtractorLink(source, name, url, referer, quality, headers, extractorData, type, audioTracks) {
    @Deprecated("Use newDrmExtractorLink", level = DeprecationLevel.ERROR)
    constructor(
        source: String, name: String, url: String, referer: String? = null, quality: Int? = null,
        type: ExtractorLinkType? = INFER_TYPE, headers: Map<String, String> = mapOf(),
        extractorData: String? = null, kid: String? = null, key: String? = null,
        uuid: kotlin.uuid.Uuid = CLEARKEY_DRM_UUID, kty: String? = "oct",
        keyRequestParameters: HashMap<String, String> = hashMapOf(), licenseUrl: String? = null,
    ) : this(source, name, url, referer ?: "", quality ?: Qualities.Unknown.value, headers, extractorData,
        type ?: inferTypeFromUrl(url), kid, key, uuid, keyRequestParameters, kty, licenseUrl)

    @Deprecated("Use newDrmExtractorLink", level = DeprecationLevel.ERROR)
    constructor(
        source: String, name: String, url: String, referer: String, quality: Int,
        type: ExtractorLinkType?, headers: Map<String, String> = mapOf(), extractorData: String? = null,
        kid: String? = null, key: String? = null, uuid: kotlin.uuid.Uuid = CLEARKEY_DRM_UUID,
        kty: String? = "oct", keyRequestParameters: HashMap<String, String> = hashMapOf(), licenseUrl: String? = null,
    ) : this(source, name, url, referer, quality, headers, extractorData, type ?: inferTypeFromUrl(url),
        kid, key, uuid, keyRequestParameters, kty, licenseUrl)

    @Deprecated(message = "Use Kotlin Uuid", level = DeprecationLevel.HIDDEN)
    fun setUuid(uuid: java.util.UUID) {
        this.uuid = uuid.toKotlinUuid()
    }

    @Deprecated(message = "Use Kotlin Uuid", level = DeprecationLevel.HIDDEN)
    fun getUuid(): java.util.UUID = this.uuid.toJavaUuid()
}

@Serializable
open class ExtractorLink
@Deprecated("Use newExtractorLink", level = DeprecationLevel.WARNING)
constructor(
    @SerialName("source") open val source: String,
    @SerialName("name") open val name: String,
    @SerialName("url") override val url: String,
    @SerialName("referer") override var referer: String,
    @SerialName("quality") open var quality: Int,
    @SerialName("headers") override var headers: Map<String, String> = mapOf(),
    @SerialName("extractorData") open var extractorData: String? = null,
    @SerialName("type") open var type: ExtractorLinkType,
    @SerialName("audioTracks") open var audioTracks: List<AudioFile> = emptyList(),
) : IDownloadableMinimum {
    val isM3u8: Boolean get() = type == ExtractorLinkType.M3U8
    val isDash: Boolean get() = type == ExtractorLinkType.DASH

    @Transient
    private var videoSize: Long? = null

    suspend fun getVideoSize(timeoutSeconds: Long = 3L): Long? {
        if (this.type != ExtractorLinkType.VIDEO) return null
        videoSize = videoSize ?: runCatching {
            val response = app.head(this.url, headers = headers, referer = referer, timeout = timeoutSeconds)
            response.headers["Content-Length"]?.toLong()
        }.getOrNull()
        return videoSize
    }

    fun getAllHeaders(): Map<String, String> {
        if (referer.isBlank()) return headers
        else if (headers.keys.none { it.equals("referer", ignoreCase = true) }) return headers + mapOf("referer" to referer)
        return headers
    }

    @Suppress("DEPRECATION")
    @Deprecated("Use newExtractorLink", level = DeprecationLevel.ERROR)
    constructor(
        source: String, name: String, url: String, referer: String? = null, quality: Int? = null,
        type: ExtractorLinkType? = INFER_TYPE, headers: Map<String, String> = mapOf(), extractorData: String? = null,
    ) : this(source, name, url, referer ?: "", quality ?: Qualities.Unknown.value, headers, extractorData, type ?: inferTypeFromUrl(url))

    @Suppress("DEPRECATION")
    @Deprecated("Use newExtractorLink", level = DeprecationLevel.ERROR)
    constructor(
        source: String, name: String, url: String, referer: String, quality: Int,
        type: ExtractorLinkType?, headers: Map<String, String> = mapOf(), extractorData: String? = null,
    ) : this(source, name, url, referer, quality, headers, extractorData, type ?: inferTypeFromUrl(url))

    @Suppress("DEPRECATION_ERROR")
    @Deprecated("Use newExtractorLink", level = DeprecationLevel.ERROR)
    constructor(
        source: String, name: String, url: String, referer: String, quality: Int,
        isM3u8: Boolean = false, headers: Map<String, String> = mapOf(), extractorData: String? = null,
    ) : this(source, name, url, referer, quality, isM3u8, headers, extractorData, false)

    @Suppress("DEPRECATION")
    @Deprecated("Use newExtractorLink", level = DeprecationLevel.ERROR)
    constructor(
        source: String, name: String, url: String, referer: String, quality: Int,
        isM3u8: Boolean = false, headers: Map<String, String> = mapOf(), extractorData: String? = null, isDash: Boolean,
    ) : this(
        source, name, url, referer, quality, headers, extractorData,
        if (isDash) ExtractorLinkType.DASH else if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
    )

    override fun toString(): String = "ExtractorLink(name=$name, url=$url, referer=$referer, type=$type)"
}

val schemaStripRegex = Regex("""^(https:|)//(www\.|)""")

enum class Qualities(var value: Int, val defaultPriority: Int) {
    Unknown(400, 4),
    P144(144, 0),
    P240(240, 2),
    P360(360, 3),
    P480(480, 4),
    P720(720, 5),
    P1080(1080, 6),
    P1440(1440, 7),
    P2160(2160, 8);

    companion object {
        fun getStringByInt(qual: Int?): String {
            return when (qual) {
                0 -> "Auto"
                Unknown.value -> ""
                P2160.value -> "4K"
                null -> ""
                else -> "${qual}p"
            }
        }

        fun getStringByIntFull(quality: Int): String {
            return when (quality) {
                0 -> "Auto"
                Unknown.value -> "Unknown"
                P2160.value -> "4K"
                else -> "${quality}p"
            }
        }
    }
}

fun getQualityFromName(qualityName: String?): Int {
    if (qualityName == null) return Qualities.Unknown.value
    val match = qualityName.lowercase().replace("p", "").trim()
    return when (match) {
        "4k" -> Qualities.P2160
        else -> null
    }?.value ?: match.toIntOrNull() ?: Qualities.Unknown.value
}

private val packedRegex = Regex("""eval\(function\(p,a,c,k,e,.*\)\)""")

fun getPacked(string: String): String? = packedRegex.find(string)?.value

fun getAndUnpack(string: String): String = getPacked(string) ?: string

suspend fun unshortenLinkSafe(url: String): String {
    return try {
        if (ShortLink.isShortLink(url)) ShortLink.unshorten(url) else url
    } catch (e: Exception) {
        logError(e)
        url
    }
}

suspend fun loadExtractor(url: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
    return loadExtractor(url = url, referer = null, subtitleCallback = subtitleCallback, callback = callback)
}

@Throws(CancellationException::class)
suspend fun loadExtractor(url: String, referer: String? = null, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
    coroutineScope { ensureActive() }
    val currentUrl = unshortenLinkSafe(url)
    val compareUrl = currentUrl.lowercase().replace(schemaStripRegex, "")
    for (index in extractorApis.lastIndex downTo 0) {
        val extractor = extractorApis[index]
        if (compareUrl.startsWith(extractor.mainUrl.replace(schemaStripRegex, ""))) {
            try {
                extractor.getUrl(currentUrl, referer, subtitleCallback, callback)
            } catch (e: Exception) {
                logError(e)
                if (e is CancellationException) throw e
            }
            return true
        }
    }
    for (index in extractorApis.lastIndex downTo 0) {
        val extractor = extractorApis[index]
        val host = runCatching { java.net.URI(currentUrl).host ?: "" }.getOrDefault("")
        val main = runCatching { java.net.URI(extractor.mainUrl).host ?: "" }.getOrDefault("")
        if (host.isNotBlank() && main.isNotBlank() && (host == main || host.endsWith(".$main") || main.endsWith(".$host"))) {
            try {
                extractor.getUrl(currentUrl, referer, subtitleCallback, callback)
            } catch (e: Exception) {
                logError(e)
                if (e is CancellationException) throw e
            }
            return true
        }
    }
    return false
}

val extractorApis: AtomicMutableList<ExtractorApi> = atomicListOf()

fun getExtractorApiFromName(name: String): ExtractorApi {
    for (api in extractorApis) {
        if (api.name == name) return api
    }
    throw ErrorLoadingException("No extractor named $name")
}

fun requireReferer(name: String): Boolean = getExtractorApiFromName(name).requiresReferer

fun httpsify(url: String): String = if (url.startsWith("//")) "https:$url" else url

suspend fun getPostForm(requestUrl: String, html: String): String? {
    val document = org.jsoup.Jsoup.parse(html)
    val inputs = document.select("Form > input")
    if (inputs.size < 4) return null
    var op: String? = null
    var id: String? = null
    var mode: String? = null
    var hash: String? = null
    for (input in inputs) {
        val value = input.attr("value")
        when (input.attr("name")) {
            "op" -> op = value
            "id" -> id = value
            "mode" -> mode = value
            "hash" -> hash = value
            else -> Unit
        }
    }
    if (op == null || id == null || mode == null || hash == null) return null
    kotlinx.coroutines.delay(5000)
    return app.post(
        requestUrl,
        headers = mapOf(
            "content-type" to "application/x-www-form-urlencoded",
            "referer" to requestUrl,
            "user-agent" to USER_AGENT,
            "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        ),
        data = mapOf("op" to op, "id" to id, "mode" to mode, "hash" to hash),
    ).text
}

fun ExtractorApi.fixUrl(url: String): String {
    if (url.startsWith("http") || url.startsWith("{\"")) return url
    if (url.isEmpty()) return ""
    val startsWithNoHttp = url.startsWith("//")
    if (startsWithNoHttp) return "https:$url"
    else {
        if (url.startsWith('/')) return mainUrl + url
        return "$mainUrl/$url"
    }
}

abstract class ExtractorApi {
    abstract val name: String
    abstract val mainUrl: String
    abstract val requiresReferer: Boolean

    var sourcePlugin: String? = null

    @Throws
    open suspend fun getUrl(url: String, referer: String? = null, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        getUrl(url, referer)?.forEach(callback)
    }

    suspend fun getSafeUrl(url: String, referer: String? = null, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            getUrl(url, referer, subtitleCallback, callback)
        } catch (e: Exception) {
            logError(e)
        }
    }

    @Throws
    open suspend fun getUrl(url: String, referer: String? = null): List<ExtractorLink>? {
        return emptyList()
    }

    open fun getExtractorUrl(id: String): String = id
}

object ShortLink {
    private val shortHosts = setOf(
        "adf.ly", "j.gs", "q.gs", "linkup.pro", "linksafe.cc", "uprot.net",
        "tinyium.com", "microify.com", "isecure.link",
    )

    fun isShortLink(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
        return shortHosts.any { host == it || host.endsWith(".$it") }
    }

    suspend fun unshorten(url: String): String = url
}
