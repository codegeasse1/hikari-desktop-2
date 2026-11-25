package codegeasse.hikari.desktop.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * Tiny JSON file store. Each collection is one file, written atomically
 * (tmp + rename) and guarded by a lock — a crash mid-write never corrupts it.
 */
class JsonStore(private val file: File) {
    private val lock = Any()
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun readObject(): JsonObject = synchronized(lock) {
        try {
            if (!file.exists()) return JsonObject(emptyMap())
            json.parseToJsonElement(file.readText()).jsonObject
        } catch (t: Throwable) {
            Log.w("store", "corrupt ${file.name}, starting empty: ${t.message}")
            runCatching {
                file.copyTo(File(file.parentFile, file.name + ".corrupt-${System.currentTimeMillis()}"), true)
            }
            JsonObject(emptyMap())
        }
    }

    fun writeObject(obj: JsonObject) = synchronized(lock) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(obj.toString())
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        } catch (t: Throwable) {
            Log.e("store", "write failed ${file.name}", t)
        }
    }

    fun readArray(key: String): JsonArray = synchronized(lock) {
        (readObject()[key] as? JsonArray) ?: JsonArray(emptyList())
    }

    fun put(key: String, value: JsonElement) = synchronized(lock) {
        val cur = readObject().toMutableMap()
        cur[key] = value
        writeObject(JsonObject(cur))
    }
}

/** App-wide stores, all under ~/.hikari2. */
object Stores {
    private val dir: File get() = Log.dataDir()

    fun file(name: String): File = File(dir, name)

    val repos = JsonStore(File(dir, "repos.json"))
    val providers = JsonStore(File(dir, "providers.json"))
    val history = JsonStore(File(dir, "history.json"))
    val favorites = JsonStore(File(dir, "favorites.json"))
    val settings = JsonStore(File(dir, "settings.json"))

    fun getSetting(key: String, default: String = ""): String =
        (settings.readObject()[key] as? JsonPrimitive)?.content ?: default

    fun setSetting(key: String, value: String) =
        settings.put(key, JsonPrimitive(value))

    fun pluginDir(engine: String): File = File(dir, "plugins/$engine").apply { mkdirs() }
    fun dexCacheDir(): File = File(dir, "dex-cache").apply { mkdirs() }
}
