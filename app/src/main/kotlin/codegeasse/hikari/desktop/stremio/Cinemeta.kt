package codegeasse.hikari.desktop.stremio

import codegeasse.hikari.desktop.core.Net
import codegeasse.hikari.desktop.model.EpisodeItem
import codegeasse.hikari.desktop.model.MediaItem
import codegeasse.hikari.desktop.model.MediaKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Cinemeta (free, no key) as the metadata backbone: search, posters,
 * backdrops, overviews, episode lists and IMDb→TMDB id mapping that engines
 * like Nuvio need (their scrapers take a tmdbId).
 */
object Cinemeta {
    private const val BASE = "https://v3-cinemeta.strem.io"

    suspend fun search(query: String): List<MediaItem> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val out = mutableListOf<MediaItem>()
        for (type in listOf("movie", "series")) {
            val body = Net.getString("$BASE/catalog/$type/top/search=$q.json") ?: continue
            val metas = runCatching {
                (json.parseToJsonElement(body).jsonObject["metas"] as? JsonArray) ?: JsonArray(emptyList())
            }.getOrDefault(JsonArray(emptyList()))
            metas.mapNotNull { it as? JsonObject }.mapNotNullTo(out) { m ->
                cinemetaToItem(m)
            }
        }
        return out.distinctBy { it.id }.take(60)
    }

    suspend fun meta(imdbId: String, type: String): JsonObject? {
        val t = if (type == "series" || type == "tv") "series" else "movie"
        val body = Net.getString("$BASE/meta/$t/$imdbId.json") ?: return null
        return runCatching { json.parseToJsonElement(body).jsonObject["meta"] as? JsonObject }.getOrNull()
    }

    fun tmdbId(meta: JsonObject): String? =
        meta["moviedb_id"]?.jsonPrimitive?.intOrNull?.toString()
            ?: meta["tmdb_id"]?.jsonPrimitive?.contentOrNull

    fun episodes(meta: JsonObject): List<EpisodeItem> {
        val videos = (meta["videos"] as? JsonArray) ?: return emptyList()
        return videos.mapNotNull { (it as? JsonObject) }.mapNotNull { v ->
            val vid = v["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            EpisodeItem(
                id = vid,
                number = v["episode"]?.jsonPrimitive?.intOrNull ?: 0,
                season = v["season"]?.jsonPrimitive?.intOrNull ?: 1,
                name = v["title"]?.jsonPrimitive?.contentOrNull,
                stillUrl = v["thumbnail"]?.jsonPrimitive?.contentOrNull,
                overview = v["overview"]?.jsonPrimitive?.contentOrNull,
            )
        }.filter { it.number > 0 }.sortedWith(compareBy({ it.season }, { it.number }))
    }

    fun toItem(meta: JsonObject, providerId: String): MediaItem? = cinemetaToItem(meta, providerId)

    private fun cinemetaToItem(m: JsonObject, providerId: String = "cinemeta"): MediaItem? {
        val id = m["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val type = m["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return MediaItem(
            providerId = providerId,
            id = id,
            title = m["name"]?.jsonPrimitive?.contentOrNull ?: id,
            kind = if (type == "series") MediaKind.SERIES else MediaKind.MOVIE,
            posterUrl = m["poster"]?.jsonPrimitive?.contentOrNull,
            backdropUrl = m["background"]?.jsonPrimitive?.contentOrNull,
            year = m["releaseInfo"]?.jsonPrimitive?.contentOrNull?.take(4)?.toIntOrNull(),
            overview = m["description"]?.jsonPrimitive?.contentOrNull,
            genres = (m["genres"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
            rating = m["imdbRating"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
            extra = mapOf(
                "stype" to type,
                "tmdbId" to (tmdbId(m) ?: ""),
            ),
        )
    }
}
