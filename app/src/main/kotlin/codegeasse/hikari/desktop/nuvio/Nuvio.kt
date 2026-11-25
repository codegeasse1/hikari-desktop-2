package codegeasse.hikari.desktop.nuvio

import codegeasse.hikari.desktop.core.Log
import codegeasse.hikari.desktop.core.Net
import codegeasse.hikari.desktop.core.Stores
import codegeasse.hikari.desktop.engine.Engine
import codegeasse.hikari.desktop.engine.Engines
import codegeasse.hikari.desktop.engine.Provider
import codegeasse.hikari.desktop.model.CatalogRow
import codegeasse.hikari.desktop.model.EngineKind
import codegeasse.hikari.desktop.model.EpisodeItem
import codegeasse.hikari.desktop.model.ListingItem
import codegeasse.hikari.desktop.model.MediaItem
import codegeasse.hikari.desktop.model.ProviderRef
import codegeasse.hikari.desktop.model.RepoRef
import codegeasse.hikari.desktop.model.StreamItem
import codegeasse.hikari.desktop.model.SubItem
import codegeasse.hikari.desktop.stremio.Cinemeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

@Serializable
data class NuvioScraperDto(
    val id: String,
    val name: String,
    val description: String? = null,
    val version: String,
    val filename: String,
    val supportedTypes: List<String> = listOf("movie", "tv"),
    val enabled: Boolean = true,
    val hasSettings: Boolean = false,
    val logo: String? = null,
)

@Serializable
data class NuvioManifestDto(
    val name: String,
    val version: String,
    val description: String? = null,
    val author: String? = null,
    val scrapers: List<NuvioScraperDto> = emptyList(),
)

data class NuvioScraper(
    val repoUrl: String,
    val dto: NuvioScraperDto,
    val enabled: Boolean,
    val code: String,
)

data class NuvioResult(
    val title: String,
    val url: String,
    val quality: String? = null,
    val size: String? = null,
    val language: String? = null,
    val provider: String? = null,
    val headers: Map<String, String>? = null,
    val subtitles: List<SubItem> = emptyList(),
)

/** Nuvio engine: repo manifests + scraper JS files, executed by [JsRun]. */
object NuvioEngine : Engine {
    override val kind = EngineKind.NUVIO

    val defaultRepos = listOf(
        "https://raw.githubusercontent.com/tapframe/nuvio-providers/main/manifest.json",
        "https://raw.githubusercontent.com/Gowaru/gowaru-nuvio-providers/main/manifest.json",
        "https://raw.githubusercontent.com/phisher98/phisher-nuvio-providers/main/manifest.json",
        "https://plugin.eclipsia.dpdns.org/manifest.json",
        "https://raw.githubusercontent.com/D3adlyRocket/Anime-Nuvio/refs/heads/main/manifest.json",
        "https://raw.githubusercontent.com/D3adlyRocket/Hindi-Nuvio/refs/heads/main/manifest.json",
    )

    fun repoUrls(): List<String> {
        val arr = Stores.repos.readArray("nuvio")
        val saved = arr.mapNotNull { (it as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull }
        if (saved.isEmpty()) {
            saveRepos(defaultRepos)
            return defaultRepos
        }
        return saved
    }

    fun saveRepos(urls: List<String>) {
        Stores.repos.put("nuvio", JsonArray(urls.distinct().map { JsonPrimitive(it) }))
    }

    fun codeDir(): java.io.File = Stores.pluginDir("nuvio")

    private fun codeFile(scraperId: String): java.io.File =
        java.io.File(codeDir(), scraperId.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".js")

    suspend fun fetchManifest(url: String): NuvioManifestDto? = withContext(Dispatchers.IO) {
        val body = Net.getString(url) ?: return@withContext null
        runCatching { json.decodeFromString<NuvioManifestDto>(body) }.getOrElse {
            Log.w("nuvio", "manifest parse failed $url: ${it.message}")
            null
        }
    }

    /** Download (or refresh) one scraper's code. Bounded size, atomic write. */
    suspend fun fetchCode(manifestUrl: String, filename: String, scraperId: String): String? =
        withContext(Dispatchers.IO) {
            val base = manifestUrl.substringBefore('?').substringBeforeLast('/')
            for (candidate in listOf("$base/$filename", manifestUrl.substringBeforeLast('/') + "/" + filename).distinct()) {
                val bytes = Net.getBytes(candidate) ?: continue
                if (bytes.size > 5 * 1024 * 1024) continue
                val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: continue
                if (text.isBlank()) continue
                val dest = codeFile(scraperId)
                val tmp = java.io.File(dest.parentFile, dest.name + ".part")
                tmp.writeText(text)
                if (dest.exists()) dest.delete()
                tmp.renameTo(dest)
                return@withContext text
            }
            // Fall back to the on-disk copy (offline-friendly installs).
            codeFile(scraperId).takeIf { it.exists() }?.readText()
        }

    fun disabledScrapers(): Set<String> {
        val arr = Stores.providers.readArray("nuvio-disabled")
        return arr.mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
    }

    fun setScraperEnabled(id: String, enabled: Boolean) {
        val cur = disabledScrapers().toMutableSet()
        if (enabled) cur.remove(id) else cur.add(id)
        Stores.providers.put("nuvio-disabled", JsonArray(cur.map { JsonPrimitive(it) }))
    }

    suspend fun scrapers(): List<NuvioScraper> = withContext(Dispatchers.IO) {
        val disabled = disabledScrapers()
        val out = mutableListOf<NuvioScraper>()
        for (repo in repoUrls()) {
            val manifest = fetchManifest(repo) ?: continue
            for (dto in manifest.scrapers) {
                if (!dto.enabled) continue
                val code = fetchCode(repo, dto.filename, dto.id) ?: continue
                out += NuvioScraper(repo, dto, dto.id !in disabled, code)
            }
        }
        out
    }

    suspend fun listings(): List<ListingItem> {
        val out = mutableListOf<ListingItem>()
        for (repo in repoUrls()) {
            val manifest = fetchManifest(repo) ?: continue
            for (dto in manifest.scrapers) {
                out += ListingItem(
                    engine = EngineKind.NUVIO,
                    repoUrl = repo,
                    id = "nuvio|" + dto.id,
                    name = dto.name,
                    version = dto.version,
                    description = dto.description,
                    iconUrl = dto.logo,
                    payloadUrls = listOf(repo.substringBefore('?').substringBeforeLast('/') + "/" + dto.filename),
                    installed = codeFile(dto.id).exists(),
                    installedVersion = if (codeFile(dto.id).exists()) dto.version else null,
                )
            }
        }
        return out
    }

    override suspend fun loadProviders(): List<Provider> =
        scrapers().filter { it.enabled }.map { NuvioProvider(it) }
}

class NuvioProvider(val scraper: NuvioScraper) : Provider {
    override val ref = ProviderRef(
        id = "nuvio|" + scraper.dto.id,
        engine = EngineKind.NUVIO,
        name = scraper.dto.name,
        iconUrl = scraper.dto.logo,
        version = scraper.dto.version,
    )

    private fun tmdbOf(item: MediaItem): String? =
        item.extra["tmdbId"]?.ifBlank { null }

    private suspend fun resolveTmdb(item: MediaItem): Triple<String, String, String?>? {
        tmdbOf(item)?.let { return Triple(it, if (item.kind.name == "SERIES") "tv" else "movie", item.id) }
        val meta = Cinemeta.meta(item.id, if (item.kind.name == "SERIES") "series" else "movie") ?: return null
        val tmdb = Cinemeta.tmdbId(meta) ?: return null
        val type = if (item.kind.name == "SERIES") "tv" else "movie"
        return Triple(tmdb, type, item.id)
    }

    override suspend fun homeRows(): List<CatalogRow> = emptyList()

    override suspend fun search(query: String): List<MediaItem> =
        Cinemeta.search(query).map { it.copy(providerId = ref.id, extra = it.extra) }

    override suspend fun detail(item: MediaItem): MediaItem {
        val meta = Cinemeta.meta(item.id, if (item.kind.name == "SERIES") "series" else "movie")
            ?: return item
        val tmdb = Cinemeta.tmdbId(meta)
        return item.copy(
            overview = meta["description"]?.jsonPrimitive?.contentOrNull ?: item.overview,
            backdropUrl = meta["background"]?.jsonPrimitive?.contentOrNull ?: item.backdropUrl,
            posterUrl = meta["poster"]?.jsonPrimitive?.contentOrNull ?: item.posterUrl,
            extra = item.extra + mapOf("tmdbId" to (tmdb ?: "")),
        )
    }

    override suspend fun episodes(item: MediaItem): List<EpisodeItem> {
        val meta = Cinemeta.meta(item.id, if (item.kind.name == "SERIES") "series" else "movie")
            ?: return emptyList()
        return Cinemeta.episodes(meta)
    }

    override suspend fun streams(item: MediaItem, episode: EpisodeItem?): List<StreamItem> {
        val (tmdbId, mediaType, _) = resolveTmdb(item) ?: return emptyList()
        val season = episode?.season
        val ep = episode?.number
        val results = JsRun.execScraper(scraper.code, scraper.dto.id, tmdbId, mediaType, season, ep)
        return results.map {
            StreamItem(
                name = (it.provider ?: scraper.dto.name) + (it.quality?.let { q -> " · $q" } ?: ""),
                url = it.url,
                headers = it.headers ?: emptyMap(),
                quality = it.quality,
                subtitles = it.subtitles,
            )
        }
    }
}
