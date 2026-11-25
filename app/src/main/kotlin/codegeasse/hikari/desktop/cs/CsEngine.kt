package codegeasse.hikari.desktop.cs

import android.content.DesktopContext
import codegeasse.hikari.desktop.core.Log
import codegeasse.hikari.desktop.core.Net
import codegeasse.hikari.desktop.core.Stores
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
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TorrentLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

/** CloudStream engine: .cs3 dex zips (converted) or JVM .jars (loaded directly). */
object CsEngine : Engine {
    override val kind = EngineKind.CS

    val defaultRepos = listOf(
        "https://raw.githubusercontent.com/recloudstream/extensions/master/repo.json",
    )

    fun repoUrls(): List<String> {
        val arr = Stores.repos.readArray("cs")
        val saved = arr.mapNotNull { (it as? JsonPrimitive)?.content }
        if (saved.isEmpty()) {
            saveRepos(defaultRepos)
            return defaultRepos
        }
        return saved
    }

    fun saveRepos(urls: List<String>) {
        Stores.repos.put("cs", JsonArray(urls.distinct().map { JsonPrimitive(it) }))
    }

    private fun iconSized(url: String?): String? =
        url?.replace("%size%", "128")?.takeIf { it.isNotBlank() }

    suspend fun listings(): List<ListingItem> = withContext(Dispatchers.IO) {
        val out = mutableListOf<ListingItem>()
        for (repo in repoUrls()) {
            val repoBody = Net.getString(repo) ?: continue
            val lists = runCatching {
                val root = json.parseToJsonElement(repoBody).jsonObject
                (root["pluginLists"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
            }.getOrNull() ?: continue
            for (listUrl in lists) {
                val resolved = resolveUrl(repo, listUrl)
                val body = Net.getString(resolved) ?: continue
                val entries = runCatching {
                    json.parseToJsonElement(body).jsonArray
                }.getOrNull() ?: continue
                for (e in entries) {
                    val o = e as? JsonObject ?: continue
                    fun str(k: String) = o[k]?.jsonPrimitive?.contentOrNull
                    val status = o["status"]?.jsonPrimitive?.intOrNull
                    if (status != null && status != 1) continue
                    val internalName = str("internalName") ?: continue
                    val url = str("url") ?: continue
                    val jarUrl = str("jarUrl")
                    val version = str("version") ?: o["version"]?.toString()
                    val dir = File(Stores.pluginDir("cs"), Installer.sanitize(internalName))
                    val installedPayload = payloadFile(dir) != null
                    val installedVersion = if (installedPayload) readMetaVersion(dir) else null
                    out += ListingItem(
                        engine = EngineKind.CS,
                        repoUrl = repo,
                        id = "cs|$internalName",
                        name = str("name") ?: internalName,
                        version = version,
                        description = str("description"),
                        iconUrl = iconSized(str("iconUrl")),
                        authors = (o["authors"] as? JsonArray)?.mapNotNull {
                            it.jsonPrimitive.contentOrNull
                        } ?: emptyList(),
                        payloadUrls = listOfNotNull(jarUrl, url),
                        installed = installedPayload,
                        installedVersion = installedVersion,
                        hasUpdate = installedPayload && Installer.isNewer(version, installedVersion),
                    )
                }
            }
        }
        out.distinctBy { it.id }
    }

    private fun resolveUrl(base: String, rel: String): String {
        if (rel.startsWith("http")) return rel
        return try {
            java.net.URI(base).resolve(rel).toString()
        } catch (_: Throwable) {
            rel
        }
    }

    private fun payloadFile(dir: File): File? {
        if (!dir.isDirectory) return null
        return dir.listFiles()
            ?.firstOrNull { it.isFile && (it.name.endsWith(".jar") || it.name.endsWith(".cs3")) }
    }

    private fun readMetaVersion(dir: File): String? = try {
        File(dir, "meta.json").takeIf { it.exists() }?.readText()?.let {
            (json.parseToJsonElement(it) as? JsonObject)?.get("version")?.jsonPrimitive?.contentOrNull
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * Download + store a plugin payload. Streams straight to disk (one write),
     * validates the file in place, then atomically renames — the fast path:
     * JVM jars mount in ~1s, no dex conversion involved.
     * Returns the plugin dir on success, null on failure.
     */
    suspend fun install(item: ListingItem, onProgress: (Double) -> Unit): File? {
        val internalName = item.id.removePrefix("cs|")
        val dir = Installer.pluginDir("cs", internalName)
        // JVM jars first: they mount instantly (their -jvm bypass); .cs3 needs conversion.
        val ordered = item.payloadUrls.sortedBy {
            if (it.substringBefore('?').endsWith(".jar", ignoreCase = true)) 0 else 1
        }
        for (u in ordered) {
            val ext = if (u.substringBefore('?').endsWith(".jar", ignoreCase = true)) ".jar" else ".cs3"
            val dest = File(dir, "payload$ext")
            val won = Installer.downloadToFile(listOf(u), dest, 64L * 1024 * 1024) {
                onProgress(0.05 + it * 0.8)
            } ?: continue
            onProgress(0.9)
            if (!validatePayloadFile(dest, internalName)) {
                Log.w("cs", "payload failed validation: $won")
                runCatching { dest.delete() }
                continue
            }
            // Drop superseded sibling payloads (e.g. old .cs3 replaced by canonical .jar).
            dir.listFiles()
                ?.filter { it.isFile && it != dest && (it.name.endsWith(".jar") || it.name.endsWith(".cs3")) }
                ?.forEach { runCatching { it.delete() } }
            File(dir, "meta.json").writeText(
                """{"version":"${(item.version ?: "").replace("\"", "")}","name":"${item.name.replace("\"", "")}"}""",
            )
            onProgress(1.0)
            Log.i("cs", "installed ${item.name} v${item.version} from $won")
            return dir
        }
        return null
    }

    /** Archive integrity check on the downloaded file (mirrors upstream). */
    private fun validatePayloadFile(file: File, internalName: String): Boolean {
        if (!file.exists() || file.length() < 100L) return false
        return try {
            ZipFile(file).use { z ->
                val manifestEntry = z.getEntry("manifest.json")
                if (manifestEntry != null) {
                    val text = z.getInputStream(manifestEntry).use { it.readBytes().toString(Charsets.UTF_8) }
                    val o = json.parseToJsonElement(text).jsonObject
                    val listed = o["internalName"]?.jsonPrimitive?.contentOrNull
                    val listedName = o["name"]?.jsonPrimitive?.contentOrNull
                    if (internalName.isNotBlank() &&
                        !listed.equals(internalName, ignoreCase = true) &&
                        !listedName.equals(internalName, ignoreCase = true)
                    ) return false
                }
                val hasDex = z.getEntry("classes.dex") != null
                val hasClasses = z.entries().asSequence().any { it.name.endsWith(".class") }
                // .jar payloads must carry JVM classes; .cs3 must carry dex or classes.
                if (file.name.endsWith(".jar")) hasClasses else (hasDex || hasClasses || manifestEntry != null)
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
                    mapOf(
                        "pluginClassName" to o["pluginClassName"]?.jsonPrimitive?.contentOrNull,
                        "internalName" to o["internalName"]?.jsonPrimitive?.contentOrNull,
                        "name" to o["name"]?.jsonPrimitive?.contentOrNull,
                        "version" to (o["version"]?.jsonPrimitive?.contentOrNull ?: o["version"]?.toString()),
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
        val root = Stores.pluginDir("cs")
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return@withContext emptyList()
        val out = mutableListOf<Provider>()
        for (dir in dirs) {
            try {
                out += mountDir(dir)
            } catch (t: Throwable) {
                Log.w("cs", "plugin ${dir.name} failed: ${t.message}")
            }
        }
        out
    }

    /**
     * Mount ONE plugin dir (hot-mount a fresh install without rescanning
     * everything — the install-in-a-second path). Runs on IO, never throws
     * for a bad payload (returns empty).
     */
    fun mountDir(dir: File): List<Provider> {
        val payload = payloadFile(dir) ?: return emptyList()
        return try {
            val bytes = payload.readBytes()
            val manifest = readManifestBytes(bytes)
            val version = manifest?.get("version") ?: payload.length().toString()
            val key = "cs-${Installer.sanitize(dir.name)}-$version"
            val loaded = Dex.load(bytes, key)
            val candidates = listOfNotNull(manifest?.get("pluginClassName")).ifEmpty {
                Dex.scanSubtypes(Dex.jarFile(key), "com/lagradost/cloudstream3/plugins/BasePlugin")
            }
            if (candidates.isEmpty()) return emptyList()
            val before = APIHolder.allProviders.snapshot()
            var loadedAny = false
            for (className in candidates) {
                val inst = runCatching {
                    val cls = loaded.loader.loadClass(className)
                    cls.getDeclaredConstructor().newInstance() as? BasePlugin
                }.getOrNull() ?: continue
                inst.filename = dir.name
                if (inst is Plugin) {
                    inst.activity = DesktopContext.get()
                    runCatching { inst.load(inst.activity) }.exceptionOrNull()?.let {
                        runCatching { inst.load() }
                    }
                } else {
                    runCatching { inst.load() }.getOrThrow()
                }
                loadedAny = true
            }
            if (!loadedAny) return emptyList()
            val after = APIHolder.allProviders.snapshot()
            val fresh = after.filter { a -> before.none { it === a } }
            for (api in fresh) {
                runCatching { api.init() }
            }
            fresh.map { CsProvider(it) }
        } catch (t: Throwable) {
            Log.w("cs", "plugin ${dir.name} failed: ${t.message}")
            emptyList()
        }
    }
}

private fun tvKind(t: TvType?): MediaKind = when (t) {
    TvType.Movie, TvType.AnimeMovie, TvType.Torrent -> MediaKind.MOVIE
    TvType.TvSeries, TvType.Anime, TvType.Cartoon, TvType.AsianDrama, TvType.OVA, TvType.Live -> MediaKind.SERIES
    else -> MediaKind.UNKNOWN
}

class CsProvider(val api: MainAPI) : Provider {
    override val ref = ProviderRef(
        id = "cs|" + api.name,
        engine = EngineKind.CS,
        name = api.name,
        version = api.sourcePlugin,
        enabled = Installer.isEnabled("cs", "cs|" + api.name),
    )

    private val detailCache = ConcurrentHashMap<String, LoadResponse>()
    private val epCache = ConcurrentHashMap<String, List<Episode>>()

    private fun SearchResponse.toMedia(): MediaItem = MediaItem(
        providerId = ref.id,
        id = url,
        title = name,
        kind = tvKind(type),
        posterUrl = posterUrl,
        extra = mapOf("url" to url),
    )

    override suspend fun homeRows(): List<CatalogRow> = withContext(Dispatchers.IO) {
        if (!api.hasMainPage) return@withContext emptyList()
        val out = mutableListOf<CatalogRow>()
        for (page in api.mainPage.take(4)) {
            try {
                val req = MainPageRequest(page.name, page.data, page.horizontalImages)
                val resp = api.getMainPage(1, req) ?: continue
                for (list in resp.items) {
                    val items = list.list.mapNotNull { runCatching { it.toMedia() }.getOrNull() }
                    if (items.isNotEmpty()) out += CatalogRow(list.name, api.name, items)
                }
            } catch (t: Throwable) {
                Log.w("cs", "${api.name} mainpage failed: ${t.message}")
            }
        }
        out
    }

    override suspend fun search(query: String): List<MediaItem> = withContext(Dispatchers.IO) {
        try {
            val list = api.search(query)
                ?: api.search(query, 1)?.items
                ?: return@withContext emptyList()
            list.mapNotNull { runCatching { it.toMedia() }.getOrNull() }
        } catch (t: Throwable) {
            Log.w("cs", "${api.name} search failed: ${t.message}")
            emptyList()
        }
    }

    private suspend fun loadDetail(item: MediaItem): LoadResponse? {
        detailCache[item.uniqueId]?.let { return it }
        val url = item.extra["url"] ?: return null
        return try {
            val r = api.load(url) ?: return null
            detailCache[item.uniqueId] = r
            r
        } catch (t: Throwable) {
            Log.w("cs", "${api.name} load failed: ${t.message}")
            null
        }
    }

    override suspend fun detail(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        val r = loadDetail(item) ?: return@withContext item
        item.copy(
            overview = r.plot ?: item.overview,
            backdropUrl = r.backgroundPosterUrl ?: item.backdropUrl,
            posterUrl = r.posterUrl ?: item.posterUrl,
            year = r.year ?: item.year,
            genres = r.tags ?: item.genres,
        )
    }

    override suspend fun episodes(item: MediaItem): List<EpisodeItem> = withContext(Dispatchers.IO) {
        val r = loadDetail(item) ?: return@withContext emptyList()
        val eps: List<Episode> = when (r) {
            is TvSeriesLoadResponse -> r.episodes
            is AnimeLoadResponse -> r.episodes.values.flatten()
            else -> return@withContext emptyList()
        }
        epCache[item.uniqueId] = eps
        eps.mapIndexed { i, e ->
            EpisodeItem(
                id = i.toString(),
                number = e.episode ?: (i + 1),
                season = e.season ?: 1,
                name = e.name,
                stillUrl = e.posterUrl,
                overview = e.description,
            )
        }
    }

    override suspend fun streams(item: MediaItem, episode: EpisodeItem?): List<StreamItem> =
        withContext(Dispatchers.IO) {
            val data: String? = if (episode != null) {
                epCache[item.uniqueId]?.getOrNull(episode.id.toIntOrNull() ?: -1)?.data
            } else {
                when (val r = loadDetail(item)) {
                    is MovieLoadResponse -> r.dataUrl
                    is LiveStreamLoadResponse -> r.dataUrl
                    is TorrentLoadResponse -> r.magnet ?: r.torrent
                    is TvSeriesLoadResponse -> r.episodes.firstOrNull()?.data
                    is AnimeLoadResponse -> r.episodes.values.firstOrNull()?.firstOrNull()?.data
                    else -> null
                }
            }
            if (data.isNullOrBlank()) return@withContext emptyList()
            val links = java.util.concurrent.ConcurrentLinkedQueue<ExtractorLink>()
            val subs = java.util.concurrent.ConcurrentLinkedQueue<SubItem>()
            try {
                api.loadLinks(
                    data,
                    false,
                    { s -> subs.add(SubItem(s.lang, s.url)) },
                    { l -> links.add(l) },
                )
            } catch (t: Throwable) {
                Log.w("cs", "${api.name} loadLinks failed: ${t.message}")
            }
            if (links.isEmpty()) return@withContext emptyList()
            val subList = subs.toList()
            links.map { l ->
                val q = Qualities.getStringByInt(l.quality).takeIf { it.isNotBlank() }
                StreamItem(
                    name = l.name + (q?.let { " · $it" } ?: ""),
                    url = l.url,
                    headers = l.getAllHeaders(),
                    quality = q,
                    subtitles = subList,
                )
            }
        }
}
