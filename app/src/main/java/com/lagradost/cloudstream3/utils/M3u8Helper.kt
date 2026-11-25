package com.lagradost.cloudstream3.utils

import com.lagradost.api.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.app

class M3u8Helper {
    companion object {
        suspend fun generateM3u8(
            source: String,
            streamUrl: String,
            referer: String,
            quality: Int? = null,
            headers: Map<String, String> = mapOf(),
            name: String = source,
        ): List<ExtractorLink> {
            return M3u8Helper2.generateM3u8(source, streamUrl, referer, quality, headers, name)
        }
    }

    data class M3u8Stream(val streamUrl: String, val quality: Int? = null, val headers: Map<String, String> = mapOf())

    suspend fun m3u8Generation(m3u8: M3u8Stream, returnThis: Boolean? = true): List<M3u8Stream> {
        return M3u8Helper2.m3u8Generation(m3u8, returnThis ?: true)
    }
}

object M3u8Helper2 {
    private const val TAG = "M3u8Helper2"

    suspend fun generateM3u8(
        source: String,
        streamUrl: String,
        referer: String,
        quality: Int? = null,
        headers: Map<String, String> = mapOf(),
        name: String = source,
    ): List<ExtractorLink> {
        return m3u8Generation(M3u8Helper.M3u8Stream(streamUrl = streamUrl, quality = quality, headers = headers), true)
            .map { stream ->
                newExtractorLink(source, name = name, stream.streamUrl, type = ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.quality = stream.quality ?: quality ?: Qualities.Unknown.value
                    this.headers = stream.headers + headers
                }
            }
    }

    suspend fun m3u8Generation(m3u8: M3u8Helper.M3u8Stream, returnThis: Boolean = true): List<M3u8Helper.M3u8Stream> {
        return try {
            val res = app.get(m3u8.streamUrl, headers = m3u8.headers)
            val body = res.text
            if (!body.contains("#EXTM3U")) {
                return listOf(m3u8)
            }
            val base = m3u8.streamUrl.substringBeforeLast('/') + "/"
            val out = mutableListOf<M3u8Helper.M3u8Stream>()
            val lines = body.lines()
            var pendingQuality: Int? = null
            var pendingHeaders = m3u8.headers
            for (i in lines.indices) {
                val line = lines[i].trim()
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    val res2 = Regex("RESOLUTION=\\d+x(\\d+)").find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val bw = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    pendingQuality = res2 ?: bw?.let { getQualityFromName("${it / 1000}p") }?.takeIf { it != Qualities.Unknown.value }
                } else if (line.isNotBlank() && !line.startsWith("#")) {
                    val absolute = when {
                        line.startsWith("http") -> line
                        line.startsWith("/") -> runCatching {
                            val u = java.net.URI(m3u8.streamUrl)
                            "${u.scheme}://${u.host}$line"
                        }.getOrDefault(line)
                        else -> base + line
                    }
                    out += M3u8Helper.M3u8Stream(absolute, pendingQuality ?: m3u8.quality, pendingHeaders)
                    pendingQuality = null
                    pendingHeaders = m3u8.headers
                }
            }
            if (out.isEmpty() || !returnThis) out.ifEmpty { listOf(m3u8) } else out
        } catch (e: Exception) {
            Log.i(TAG, "m3u8Generation failed: ${e.message}")
            listOf(m3u8)
        }
    }
}
