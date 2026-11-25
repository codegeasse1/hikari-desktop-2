package codegeasse.hikari.desktop.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Continue-watching entries + favorites, persisted as JSON arrays. */
object LibraryStore {
    data class Entry(
        val providerId: String,
        val id: String,
        val title: String,
        val posterUrl: String? = null,
        val backdropUrl: String? = null,
        val season: Int = 0,
        val episode: Int = 0,
        val episodeId: String? = null,
        val at: Long = System.currentTimeMillis(),
    )

    private fun JsonObject.toEntry(): Entry? {
        val pid = this["providerId"]?.jsonPrimitive?.contentOrNull ?: return null
        val id = this["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = this["title"]?.jsonPrimitive?.contentOrNull ?: return null
        return Entry(
            providerId = pid,
            id = id,
            title = title,
            posterUrl = this["posterUrl"]?.jsonPrimitive?.contentOrNull,
            backdropUrl = this["backdropUrl"]?.jsonPrimitive?.contentOrNull,
            season = this["season"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            episode = this["episode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            episodeId = this["episodeId"]?.jsonPrimitive?.contentOrNull,
            at = this["at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
        )
    }

    private fun Entry.toJson(): JsonObject = buildJsonObject {
        put("providerId", providerId)
        put("id", id)
        put("title", title)
        posterUrl?.let { put("posterUrl", it) }
        backdropUrl?.let { put("backdropUrl", it) }
        put("season", season.toString())
        put("episode", episode.toString())
        episodeId?.let { put("episodeId", it) }
        put("at", at.toString())
    }

    private fun read(key: String, limit: Int): List<Entry> =
        Stores.history.readArray(key).mapNotNull {
            (it as? JsonObject)?.toEntry()
        }.sortedByDescending { it.at }.take(limit)

    private fun write(key: String, entries: List<Entry>) {
        Stores.history.put(key, JsonArray(entries.map { it.toJson() }))
    }

    fun history(limit: Int = 60): List<Entry> = read("history", limit)

    fun touchHistory(e: Entry) {
        val cur = read("history", 200).filterNot {
            it.providerId == e.providerId && it.id == e.id
        }
        write("history", (listOf(e) + cur).take(200))
    }

    fun clearHistory() = Stores.history.put("history", JsonArray(emptyList()))

    fun favorites(limit: Int = 500): List<Entry> = read("favorites", limit)

    fun isFavorite(providerId: String, id: String): Boolean =
        read("favorites", 2000).any { it.providerId == providerId && it.id == id }

    fun toggleFavorite(e: Entry): Boolean {
        val cur = read("favorites", 2000).toMutableList()
        val ix = cur.indexOfFirst { it.providerId == e.providerId && it.id == e.id }
        return if (ix >= 0) {
            cur.removeAt(ix)
            write("favorites", cur)
            false
        } else {
            write("favorites", (listOf(e) + cur).take(500))
            true
        }
    }
}
