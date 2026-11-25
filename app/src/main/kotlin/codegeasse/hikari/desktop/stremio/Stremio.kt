package codegeasse.hikari.desktop.stremio

import codegeasse.hikari.desktop.core.Log
import codegeasse.hikari.desktop.core.Net
import codegeasse.hikari.desktop.core.Stores
import codegeasse.hikari.desktop.engine.Engine
import codegeasse.hikari.desktop.engine.Engines
import codegeasse.hikari.desktop.engine.Provider
import codegeasse.hikari.desktop.install.Installer
import codegeasse.hikari.desktop.model.CatalogRow
import codegeasse.hikari.desktop.model.EngineKind
import codegeasse.hikari.desktop.model.EpisodeItem
import codegeasse.hikari.desktop.model.ListingItem
import codegeasse.hikari.desktop.model.MediaItem
import codegeasse.hikari.desktop.model.MediaKind
import codegeasse.hikari.desktop.model.ProviderRef
import codegeasse.hikari.desktop.model.RepoRef
import codegeasse.hikari.desktop.model.StreamItem
import codegeasse.hikari.desktop.model.SubItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

/** Parsed Stremio addon manifest (https://github.com/Stremio/stremio-addon-sdk). */
data class StManifest(
    val id: String,
    val name: String,
    val version: String,
    val logo: String? = null,
    val description: String? = null,
    val resources: List<StResource> = emptyList(),
    val types: List<String> = emptyList(),
    val catalogs: List<StCatalog> = emptyList(),
    val transportUrl: String,
)

data class StResource(val name: String, val types: List<String>, val idPrefixes: List<String>)
data class StCatalog(val type: String, val id: String, val name: String, val searchable: Boolean)

fun parseManifest(transportUrl: String, payload: String): StManifest? {
    return try {
        val root = json.parseToJsonElement(payload).jsonObject
        fun objStr(o: JsonObject, k: String): String = o[k]?.jsonPrimitive?.contentOrNull.orEmpty()
        val defTypes = (root["types"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val defPrefixes = (root["idPrefixes"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val resources = (root["resources"] as? JsonArray)?.mapNotNull { r ->
            when (r) {
                is JsonPrimitive -> StResource(r.contentOrNull.orEmpty(), defTypes, defPrefixes)
                is JsonObject -> StResource(
                    objStr(r, "name"),
                    ((r["types"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()).ifEmpty { defTypes },
                    ((r["idPrefixes"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()).ifEmpty { defPrefixes },
                )
                else -> null
            }
        }?.filter { it.name.isNotBlank() } ?: emptyList()
        val catalogs = (root["catalogs"] as? JsonArray)?.mapNotNull { c ->
            if (c !is JsonObject) return@mapNotNull null
            val extra = (c["extra"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let { o -> objStr(o, "name") } } ?: emptyList()
            StCatalog(objStr(c, "type"), objStr(c, "id"), objStr(c, "name").ifBlank { objStr(c, "id") }, "search" in extra)
        } ?: emptyList()
        StManifest(
            id = objStr(root, "id"),
            name = objStr(root, "name").ifBlank { transportUrl },
            version = objStr(root, "version"),
            logo = root["logo"]?.jsonPrimitive?.contentOrNull,
            description = root["description"]?.jsonPrimitive?.contentOrNull,
            resources = resources,
            types = defTypes,
            catalogs = catalogs,
            transportUrl = transportUrl,
        ).takeIf { it.id.isNotBlank() }
    } catch (t: Throwable) {
        Log.w("stremio", "manifest parse failed $transportUrl: ${t.message}")
        null
    }
}

private fun kindOf(t: String): MediaKind = when (t.lowercase()) {
    "movie" -> MediaKind.MOVIE
    "series", "tv" -> MediaKind.SERIES
    else -> MediaKind.UNKNOWN
}

private fun metaToItem(providerId: String, m: JsonObject): MediaItem? {
    val id = m["id"]?.jsonPrimitive?.contentOrNull ?: return null
    val type = m["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
    return MediaItem(
        providerId = providerId,
        id = id,
        title = m["name"]?.jsonPrimitive?.contentOrNull ?: id,
        kind = kindOf(type),
        posterUrl = m["poster"]?.jsonPrimitive?.contentOrNull,
        backdropUrl = m["background"]?.jsonPrimitive?.contentOrNull,
        year = m["releaseInfo"]?.jsonPrimitive?.contentOrNull?.take(4)?.toIntOrNull(),
        overview = m["description"]?.jsonPrimitive?.contentOrNull,
        genres = (m["genres"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
        rating = m["imdbRating"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
        extra = mapOf("stype" to type),
    )
}

class StremioProvider(val manifest: StManifest) : Provider {
    override val ref = ProviderRef(
        id = "stremio|" + manifest.id,
        engine = EngineKind.STREMIO,
        name = manifest.name,
        iconUrl = manifest.logo,
        enabled = Installer.isEnabled("stremio", "stremio|" + manifest.id),
    )

    private fun base(): String = manifest.transportUrl.trimEnd('/')

    private fun supports(resource: String, type: String, id: String): Boolean =
        manifest.resources.any { r ->
            r.name == resource && (r.types.isEmpty() || type in r.types) &&
                (r.idPrefixes.isEmpty() || r.idPrefixes.any { id.startsWith(it) })
        }

    override suspend fun homeRows(): List<CatalogRow> = supervisorScope {
        if (!supports("catalog", "", "")) {
            val anyCatalog = manifest.resources.any { it.name == "catalog" }
            if (!anyCatalog) return@supervisorScope emptyList()
        }
        manifest.catalogs.map { cat ->
            async(Dispatchers.IO) {
                val url = "${base()}/catalog/${cat.type}/${cat.id}.json"
                val body = Net.getString(url) ?: return@async null
                val metas = runCatching {
                    (json.parseToJsonElement(body).jsonObject["metas"] as? JsonArray)?.mapNotNull {
                        (it as? JsonObject)?.let { o -> metaToItem(ref.id, o) }
                    } ?: emptyList()
                }.getOrDefault(emptyList())
                if (metas.isEmpty()) null else CatalogRow(cat.name, manifest.name, metas)
            }
        }.mapNotNull { runCatching { it.await() }.getOrNull() }
    }

    override suspend fun search(query: String): List<MediaItem> = supervisorScope {
        manifest.catalogs.filter { it.searchable }.map { cat ->
            async(Dispatchers.IO) {
                val q = java.net.URLEncoder.encode(query, "UTF-8")
                val url = "${base()}/catalog/${cat.type}/${cat.id}/search=$q.json"
                val body = Net.getString(url) ?: return@async emptyList<MediaItem>()
                runCatching {
                    (json.parseToJsonElement(body).jsonObject["metas"] as? JsonArray)?.mapNotNull {
                        (it as? JsonObject)?.let { o -> metaToItem(ref.id, o) }
                    } ?: emptyList()
                }.getOrDefault(emptyList())
            }
        }.flatMap { runCatching { it.await() }.getOrDefault(emptyList()) }.distinctBy { it.uniqueId }
    }

    private suspend fun fetchMeta(item: MediaItem): JsonObject? {
        val type = item.extra["stype"]?.ifBlank { null } ?: "movie"
        val body = Net.getString("${base()}/meta/$type/${item.id}.json") ?: return null
        return runCatching { json.parseToJsonElement(body).jsonObject["meta"] as? JsonObject }.getOrNull()
    }

    override suspend fun detail(item: MediaItem): MediaItem {
        val m = fetchMeta(item) ?: return item
        return item.copy(
            overview = m["description"]?.jsonPrimitive?.contentOrNull ?: item.overview,
            backdropUrl = m["background"]?.jsonPrimitive?.contentOrNull ?: item.backdropUrl,
            posterUrl = m["poster"]?.jsonPrimitive?.contentOrNull ?: item.posterUrl,
            rating = m["imdbRating"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: item.rating,
        )
    }

    override suspend fun episodes(item: MediaItem): List<EpisodeItem> {
        val m = fetchMeta(item) ?: return emptyList()
        val videos = (m["videos"] as? JsonArray) ?: return emptyList()
        return videos.mapNotNull { (it as? JsonObject) }.mapNotNull { v ->
            val vid = v["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            EpisodeItem(
                id = vid,
                number = v["episode"]?.jsonPrimitive?.intOrNull ?: 0,
                season = v["season"]?.jsonPrimitive?.intOrNull ?: 1,
                name = v["title"]?.jsonPrimitive?.contentOrNull ?: v["name"]?.jsonPrimitive?.contentOrNull,
                stillUrl = v["thumbnail"]?.jsonPrimitive?.contentOrNull,
                overview = v["overview"]?.jsonPrimitive?.contentOrNull,
            )
        }.filter { it.number > 0 }.sortedWith(compareBy({ it.season }, { it.number }))
    }

    override suspend fun streams(item: MediaItem, episode: EpisodeItem?): List<StreamItem> {
        val type = item.extra["stype"]?.ifBlank { null } ?: "movie"
        val sid = episode?.id ?: item.id
        val body = Net.getString("${base()}/stream/$type/$sid.json") ?: return emptyList()
        val arr = runCatching { json.parseToJsonElement(body).jsonObject["streams"] as? JsonArray }.getOrNull()
            ?: return emptyList()
        return arr.mapNotNull { (it as? JsonObject) }.mapNotNull { s ->
            val url = s["url"]?.jsonPrimitive?.contentOrNull
                ?: s["ytId"]?.jsonPrimitive?.contentOrNull?.let { "https://www.youtube.com/watch?v=$it" }
                ?: return@mapNotNull null
            val subs = ((s["subtitles"] as? JsonArray)?.mapNotNull { (it as? JsonObject) })?.mapNotNull {
                val u = it["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                SubItem(it["lang"]?.jsonPrimitive?.contentOrNull ?: "und", u)
            } ?: emptyList()
            val title = s["title"]?.jsonPrimitive?.contentOrNull
            StreamItem(
                name = (s["name"]?.jsonPrimitive?.contentOrNull ?: manifest.name) +
                    (title?.let { " — $it" } ?: ""),
                url = url,
                quality = title,
                subtitles = subs,
            )
        }
    }
}

/** Stremio engine: addons are installed by manifest URL (one GET + parse, <1s). */
object StremioEngine : Engine {
    override val kind = EngineKind.STREMIO

    const val CINEMETA = "https://v3-cinemeta.strem.io/manifest.json"

    fun savedManifests(): List<String> {
        // Written as plain strings by saveManifests; tolerate legacy {url:} objects.
        val arr = Stores.providers.readArray("stremio")
        return arr.mapNotNull {
            when (it) {
                is JsonPrimitive -> it.contentOrNull?.takeIf { s -> s.isNotBlank() }
                is JsonObject -> it["url"]?.jsonPrimitive?.contentOrNull?.takeIf { s -> s.isNotBlank() }
                else -> null
            }
        }
    }

    fun saveManifests(urls: List<String>) {
        Stores.providers.put("stremio", JsonArray(urls.distinct().map { JsonPrimitive(it) }))
    }

    suspend fun fetchManifest(url: String): StManifest? {
        val clean = url.trim().removeSuffix("/manifest.json").trimEnd('/') + "/manifest.json"
        val body = Net.getString(clean) ?: return null
        return parseManifest(clean, body)
    }

    suspend fun listings(): List<ListingItem> = savedManifests().map { u ->
        ListingItem(EngineKind.STREMIO, "", "stremio|" + u.hashCode(), u, installed = true)
    }

    override suspend fun loadProviders(): List<Provider> = withContext(Dispatchers.IO) {
        val urls = savedManifests().ifEmpty {
            saveManifests(listOf(CINEMETA))
            listOf(CINEMETA)
        }
        urls.mapNotNull { u ->
            val clean = u.trim().removeSuffix("/manifest.json").trimEnd('/') + "/manifest.json"
            val body = Net.getString(clean)
            val m = body?.let { parseManifest(clean, it) }
            when {
                m == null -> {
                    Log.w("stremio", "manifest unreachable: $u")
                    null
                }
                !Installer.isEnabled("stremio", "stremio|" + m.id) -> null
                else -> StremioProvider(m)
            }
        }
    }
}
