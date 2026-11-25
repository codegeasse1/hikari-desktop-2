package codegeasse.hikari.desktop.hiki

import codegeasse.hikari.desktop.core.Log
import codegeasse.hikari.desktop.core.Net
import codegeasse.hikari.desktop.core.Stores
import codegeasse.hikari.desktop.cs.Dex
import codegeasse.hikari.desktop.engine.Engine
import codegeasse.hikari.desktop.engine.Provider
import codegeasse.hikari.desktop.install.Installer
import codegeasse.hikari.desktop.model.CatalogRow
import codegeasse.hikari.desktop.model.EngineKind
import codegeasse.hikari.desktop.model.EpisodeItem
import codegeasse.hikari.desktop.model.ListingItem
import codegeasse.hikari.desktop.model.MediaItem
import codegeasse.hikari.desktop.model.MediaKind
import codegeasse.hikari.desktop.model.ProviderRef
import codegeasse.hikari.desktop.model.StreamItem
import codegeasse.hikari.desktop.model.SubItem
import com.hikari.ext.HikariEpisode
import com.hikari.ext.HikariMedia
import com.hikari.ext.HikariMediaType
import com.hikari.ext.HikariProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

/** Hikari engine: .hiki dex zips (converted) or JVM .jars (loaded directly). */
object Hiki : Engine {
    override val kind = EngineKind.HIKI

    val defaultRepos = listOf(
        "https://raw.githubusercontent.com/codegeasse1/hikari-extensions/main/repo.json",
        "https://raw.githubusercontent.com/codegeasse1/hikari-extensions/main/repo-desktop.json",
    )

    fun repoUrls(): List<String> {
        val arr = Stores.repos.readArray("hiki")
        val saved = arr.mapNotNull { (it as? JsonPrimitive)?.content }
        if (saved.isEmpty()) {
            saveRepos(defaultRepos)
            return defaultRepos
        }
        return saved
    }

    fun saveRepos(urls: List<String>) {
        Stores.repos.put("hiki", JsonArray(urls.distinct().map { JsonPrimitive(it) }))
    }

    suspend fun listings(): List<ListingItem> = withContext(Dispatchers.IO) {
        val out = mutableListOf<ListingItem>()
        for (repo in repoUrls()) {
            val body = Net.getString(repo) ?: continue
            val plugins = runCatching {
                (json.parseToJsonElement(body).jsonObject["plugins"] as? JsonArray) ?: JsonArray(emptyList())
            }.getOrNull() ?: continue
            for (e in plugins) {
                val o = e as? JsonObject ?: continue
                fun str(k: String) = o[k]?.jsonPrimitive?.contentOrNull
                val name = str("name") ?: continue
                val url = str("url") ?: continue
                val dir = File(Stores.pluginDir("hiki"), Installer.sanitize(name))
                val installed = payloadFile(dir) != null
                out += ListingItem(
                    engine = EngineKind.HIKI,
                    repoUrl = repo,
                    id = "hiki|$name",
                    name = name,
                    version = str("version"),
                    description = str("description"),
                    iconUrl = null,
                    payloadUrls = listOf(url),
                    installed = installed,
                    installedVersion = if (installed) readMetaVersion(dir) else null,
                )
            }
        }
        out.distinctBy { it.id }
    }

    private fun payloadFile(dir: File): File? {
        if (!dir.isDirectory) return null
        return dir.listFiles()
            ?.firstOrNull { it.isFile && (it.name.endsWith(".jar") || it.name.endsWith(".hiki")) }
    }

    private fun readMetaVersion(dir: File): String? = try {
        File(dir, "meta.json").takeIf { it.exists() }?.readText()?.let {
            (json.parseToJsonElement(it) as? JsonObject)?.get("version")?.jsonPrimitive?.contentOrNull
        }
    } catch (_: Throwable) {
        null
    }

    suspend fun install(item: ListingItem, onProgress: (Double) -> Unit): Boolean {
        val name = item.id.removePrefix("hiki|")
        val bytes = Installer.fetchPayload(item.payloadUrls) { onProgress(it * 0.7) } ?: return false
        if (bytes.isEmpty() || bytes.size > 60 * 1024 * 1024) return false
        val manifest = readManifestBytes(bytes)
        val mainClass = manifest?.get("mainClass")
        if (mainClass.isNullOrBlank() && !Dex.looksLikeJar(bytes)) return false
        val dir = Installer.pluginDir("hiki", name)
        val ext = if (looksLikeJar(bytes)) ".jar" else ".hiki"
        val tmp = File(dir, "payload$ext.part")
        tmp.writeBytes(bytes)
        dir.listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".jar") || it.name.endsWith(".hiki")) }
            ?.forEach { runCatching { it.delete() } }
        val dest = File(dir, "payload$ext")
        if (dest.exists()) dest.delete()
        tmp.renameTo(dest)
        File(dir, "meta.json").writeText(
            """{"version":"${(item.version ?: "").replace("\"", "")}","name":"${item.name.replace("\"", "")}"}""",
        )
        onProgress(1.0)
        Log.i("hiki", "installed ${item.name} v${item.version}")
        return true
    }

    private fun looksLikeJar(payload: ByteArray): Boolean {
        if (payload.size < 4) return false
        if (!(payload[0] == 0x50.toByte() && payload[1] == 0x4B.toByte())) return false
        return try {
            val tmp = File.createTempFile("probe", ".zip")
            try {
                tmp.writeBytes(payload)
                ZipFile(tmp).use { z ->
                    z.entries().asSequence().any { it.name.endsWith(".class") }
                }
            } finally {
                tmp.delete()
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun readManifestBytes(payload: ByteArray): Map<String, String?>? {
        return try {
            val tmp = File.createTempFile("manifest", ".zip")
            try {
                tmp.writeBytes(payload)
                ZipFile(tmp).use { z ->
                    val e = z.entries().asSequence().firstOrNull { it.name == "manifest.json" }
                        ?: return null
                    val text = z.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
                    val o = json.parseToJsonElement(text).jsonObject
                    val mainClass = when (val m = o["mainClass"]) {
                        is JsonPrimitive -> m.contentOrNull
                        is JsonArray -> m.firstOrNull()?.jsonPrimitive?.contentOrNull
                        else -> null
                    }
                    mapOf(
                        "mainClass" to mainClass,
                        "name" to o["name"]?.jsonPrimitive?.contentOrNull,
                        "version" to o["version"]?.jsonPrimitive?.contentOrNull,
                    )
                }
            } finally {
                tmp.delete()
            }
        } catch (_: Throwable) {
            null
        }
    }

    override suspend fun loadProviders(): List<Provider> = withContext(Dispatchers.IO) {
        val root = Stores.pluginDir("hiki")
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return@withContext emptyList()
        val out = mutableListOf<Provider>()
        for (dir in dirs) {
            val payload = payloadFile(dir) ?: continue
            try {
                val bytes = payload.readBytes()
                val manifest = readManifestBytes(bytes)
                val version = manifest?.get("version") ?: payload.length().toString()
                val key = "hiki-${Installer.sanitize(dir.name)}-$version"
                val loaded = Dex.load(bytes, key)
                val candidates = listOfNotNull(manifest?.get("mainClass")).ifEmpty {
                    Dex.scanSubtypes(Dex.jarFile(key), "com/hikari/ext/HikariProvider")
                }
                if (candidates.isEmpty()) continue
                for (mainClass in candidates) {
                    val inst = runCatching {
                        val cls = loaded.loader.loadClass(mainClass)
                        cls.getDeclaredConstructor().newInstance() as? HikariProvider
                    }.getOrNull() ?: continue
                    out += HikiProvider(inst)
                }
            } catch (t: Throwable) {
                Log.w("hiki", "plugin ${dir.name} failed: ${t.message}")
            }
        }
        out
    }
}

private fun hikKind(t: HikariMediaType): MediaKind = when (t) {
    HikariMediaType.MOVIE -> MediaKind.MOVIE
    HikariMediaType.SERIES -> MediaKind.SERIES
    else -> MediaKind.UNKNOWN
}

private fun hikKindBack(k: MediaKind): HikariMediaType = when (k) {
    MediaKind.MOVIE -> HikariMediaType.MOVIE
    MediaKind.SERIES -> HikariMediaType.SERIES
    else -> HikariMediaType.UNKNOWN
}

class HikiProvider(val ext: HikariProvider) : Provider {
    override val ref = ProviderRef(
        id = "hiki|" + ext.id,
        engine = EngineKind.HIKI,
        name = ext.name,
        iconUrl = ext.iconUrl,
        version = ext.version.toString(),
        enabled = Installer.isEnabled("hiki", "hiki|" + ext.id),
    )

    private val mediaCache = ConcurrentHashMap<String, HikariMedia>()
    private val epCache = ConcurrentHashMap<String, List<HikariEpisode>>()

    private fun HikariMedia.toMedia(): MediaItem {
        mediaCache[ref.id + "::" + id] = this
        return MediaItem(
            providerId = ref.id,
            id = id,
            title = title,
            kind = hikKind(type),
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            year = year,
            overview = overview,
            genres = genres,
            extra = mapOf("mid" to id),
        )
    }

    private fun mediaOf(item: MediaItem): HikariMedia =
        mediaCache[item.uniqueId] ?: HikariMedia(
            id = item.extra["mid"] ?: item.id,
            title = item.title,
            type = hikKindBack(item.kind),
            posterUrl = item.posterUrl,
            year = item.year,
            overview = item.overview,
            genres = item.genres,
            backdropUrl = item.backdropUrl,
        )

    override suspend fun homeRows(): List<CatalogRow> = withContext(Dispatchers.IO) {
        val catalogs = try {
            ext.catalogs()
        } catch (t: Throwable) {
            Log.w("hiki", "${ext.name} catalogs failed: ${t.message}")
            return@withContext emptyList()
        }
        val out = mutableListOf<CatalogRow>()
        for (c in catalogs.take(6)) {
            try {
                val items = ext.getCatalog(c, 1).mapNotNull {
                    runCatching { it.toMedia() }.getOrNull()
                }
                if (items.isNotEmpty()) out += CatalogRow(c.name, ext.name, items)
            } catch (t: Throwable) {
                Log.w("hiki", "${ext.name} catalog ${c.id} failed: ${t.message}")
            }
        }
        out
    }

    override suspend fun search(query: String): List<MediaItem> = withContext(Dispatchers.IO) {
        try {
            ext.search(query, 1).mapNotNull { runCatching { it.toMedia() }.getOrNull() }
        } catch (t: Throwable) {
            Log.w("hiki", "${ext.name} search failed: ${t.message}")
            emptyList()
        }
    }

    override suspend fun detail(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        try {
            ext.getMeta(mediaOf(item)).toMedia()
        } catch (t: Throwable) {
            Log.w("hiki", "${ext.name} meta failed: ${t.message}")
            item
        }
    }

    override suspend fun episodes(item: MediaItem): List<EpisodeItem> = withContext(Dispatchers.IO) {
        try {
            val eps = ext.getEpisodes(mediaOf(item)) ?: return@withContext emptyList()
            epCache[item.uniqueId] = eps
            eps.map { e ->
                EpisodeItem(
                    id = e.id,
                    number = e.number,
                    season = e.season,
                    name = e.name,
                    stillUrl = e.image,
                )
            }
        } catch (t: Throwable) {
            Log.w("hiki", "${ext.name} episodes failed: ${t.message}")
            emptyList()
        }
    }

    override suspend fun streams(item: MediaItem, episode: EpisodeItem?): List<StreamItem> =
        withContext(Dispatchers.IO) {
            val ep = episode?.let { id ->
                epCache[item.uniqueId]?.firstOrNull { it.id == id.id }
            }
            try {
                ext.getStreams(mediaOf(item), ep).mapNotNull { s ->
                    val url = streamUrl(s) ?: return@mapNotNull null
                    StreamItem(
                        name = s.name,
                        url = url,
                        headers = s.headers,
                        subtitles = s.subtitles.map { SubItem(it.lang, it.url) },
                    )
                }
            } catch (t: Throwable) {
                Log.w("hiki", "${ext.name} streams failed: ${t.message}")
                emptyList()
            }
        }

    private fun streamUrl(s: com.hikari.ext.HikariStream): String? {
        if (s.url.isNotBlank()) return s.url
        if (!s.ytId.isNullOrBlank()) return "https://www.youtube.com/watch?v=${s.ytId}"
        if (!s.infoHash.isNullOrBlank()) {
            val tr = s.trackers.joinToString("") { "&tr=${URLEncoder.encode(it, "UTF-8")}" }
            return "magnet:?xt=urn:btih:${s.infoHash}$tr"
        }
        return null
    }
}
