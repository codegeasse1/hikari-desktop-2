package codegeasse.hikari.desktop.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * HTTP layer. Bounded timeouts everywhere; parallel first-byte-wins racing
 * for mirror lists so one dead host never sets the pace.
 */
object Net {
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

    val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private val ioLimited = kotlinx.coroutines.sync.Semaphore(16)

    suspend fun getString(url: String, headers: Map<String, String> = emptyMap()): String? =
        withContext(Dispatchers.IO) {
            ioLimited.acquire()
            try {
                val req = Request.Builder().url(url).header("User-Agent", UA).apply {
                    headers.forEach { (k, v) -> header(k, v) }
                }.build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@withContext null
                    r.body?.string()
                }
            } catch (t: Throwable) {
                Log.w("net", "GET failed $url: ${t.message}")
                null
            } finally {
                ioLimited.release()
            }
        }

    suspend fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray? =
        withContext(Dispatchers.IO) {
            ioLimited.acquire()
            try {
                val req = Request.Builder().url(url).header("User-Agent", UA).apply {
                    headers.forEach { (k, v) -> header(k, v) }
                }.build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@withContext null
                    r.body?.bytes()
                }
            } catch (t: Throwable) {
                Log.w("net", "GET bytes failed $url: ${t.message}")
                null
            } finally {
                ioLimited.release()
            }
        }

    suspend fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): String? =
        withContext(Dispatchers.IO) {
            ioLimited.acquire()
            try {
                val req = Request.Builder().url(url)
                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .header("User-Agent", UA).apply {
                        headers.forEach { (k, v) -> header(k, v) }
                    }.build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@withContext null
                    r.body?.string()
                }
            } catch (t: Throwable) {
                Log.w("net", "POST failed $url: ${t.message}")
                null
            } finally {
                ioLimited.release()
            }
        }

    /**
     * First-byte-wins race across mirror URLs: all start at once, the first
     * success wins, the rest are abandoned. A dead mirror costs nothing.
     */
    suspend fun raceString(urls: List<String>, headers: Map<String, String> = emptyMap()): Pair<String, String?>? =
        supervisorScope {
            val jobs = urls.map { u -> async { u to getString(u, headers) } }
            var win: Pair<String, String?>? = null
            for (j in jobs) {
                val (u, body) = runCatching { j.await() }.getOrNull() ?: continue
                if (!body.isNullOrBlank()) {
                    win = u to body
                    break
                }
            }
            jobs.forEach { it.cancel() }
            win
        }

    suspend fun raceBytes(urls: List<String>, headers: Map<String, String> = emptyMap()): Pair<String, ByteArray?>? =
        supervisorScope {
            val jobs = urls.map { u -> async { u to getBytes(u, headers) } }
            var win: Pair<String, ByteArray?>? = null
            for (j in jobs) {
                val (u, body) = runCatching { j.await() }.getOrNull() ?: continue
                if (body != null && body.isNotEmpty()) {
                    win = u to body
                    break
                }
            }
            jobs.forEach { it.cancel() }
            win
        }

    /** Download with progress (0.0..1.0, -1 when size unknown). Runs on IO. */
    suspend fun download(
        url: String,
        dest: File,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Long = Long.MAX_VALUE,
        onProgress: (Double) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).header("User-Agent", UA).apply {
                headers.forEach { (k, v) -> header(k, v) }
            }.build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    Log.w("net", "download HTTP ${r.code} $url")
                    return@withContext false
                }
                val body = r.body ?: return@withContext false
                val total = body.contentLength()
                dest.parentFile?.mkdirs()
                val tmp = File(dest.parentFile, dest.name + ".part")
                body.byteStream().use { input ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var last = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            done += n
                            if (done > maxBytes) {
                                Log.w("net", "download exceeded cap $url")
                                return@withContext false
                            }
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0 && done - last > 256 * 1024) {
                                last = done
                                onProgress(done.toDouble() / total)
                            }
                        }
                    }
                }
                onProgress(1.0)
                if (dest.exists()) dest.delete()
                tmp.renameTo(dest)
                true
            }
        } catch (t: Throwable) {
            Log.w("net", "download failed $url: ${t.message}")
            false
        }
    }
}
