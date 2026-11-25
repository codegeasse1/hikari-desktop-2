package codegeasse.hikari.desktop.engine

import codegeasse.hikari.desktop.core.Log
import codegeasse.hikari.desktop.model.CatalogRow
import codegeasse.hikari.desktop.model.EngineKind
import codegeasse.hikari.desktop.model.EpisodeItem
import codegeasse.hikari.desktop.model.MediaItem
import codegeasse.hikari.desktop.model.ProviderRef
import codegeasse.hikari.desktop.model.StreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

/** One installed source: catalogs + search + detail + streams. */
interface Provider {
    val ref: ProviderRef
    suspend fun homeRows(): List<CatalogRow>
    suspend fun search(query: String): List<MediaItem>
    suspend fun detail(item: MediaItem): MediaItem
    suspend fun episodes(item: MediaItem): List<EpisodeItem>
    suspend fun streams(item: MediaItem, episode: EpisodeItem?): List<StreamItem>
}

/** One extension engine (CloudStream / Hikari / Nuvio / Stremio). */
interface Engine {
    val kind: EngineKind

    /** All installed+enabled providers, freshly loaded. Never throws. */
    suspend fun loadProviders(): List<Provider>

    /** Optional: refresh repository metadata. */
    suspend fun refreshRepos() {}
}

/** Registry + parallel fan-out with per-provider budgets. */
object Engines {
    private val engines = linkedMapOf<EngineKind, Engine>()
    @Volatile private var cached: List<Provider> = emptyList()

    fun register(engine: Engine) {
        engines[engine.kind] = engine
    }

    fun kinds(): List<EngineKind> = engines.keys.toList()

    fun engine(kind: EngineKind): Engine? = engines[kind]

    suspend fun reload(): List<Provider> {
        val all = supervisorScope {
            engines.values.map { eng ->
                async(Dispatchers.IO) {
                    runCatching { eng.loadProviders() }.getOrElse {
                        Log.w("engines", "${eng.kind} load failed: ${it.message}")
                        emptyList()
                    }
                }
            }.flatMap { runCatching { it.await() }.getOrDefault(emptyList()) }
        }
        cached = all
        return all
    }

    fun current(): List<Provider> = cached

    /** Home rows from every enabled provider, bounded: slow providers are skipped, never fatal. */
    suspend fun homeRows(maxPerProviderRows: Int = 12): List<CatalogRow> = supervisorScope {
        current().filter { it.ref.enabled }.map { p ->
            async(Dispatchers.IO) {
                withTimeoutOrNull(20_000L) {
                    runCatching { p.homeRows() }.getOrDefault(emptyList())
                } ?: emptyList<CatalogRow>()
            }
        }.flatMap { runCatching { it.await() }.getOrDefault(emptyList()) }
            .map { row -> if (row.items.size > 14) row.copy(items = row.items.take(14)) else row }
    }

    suspend fun searchAll(query: String): List<MediaItem> = supervisorScope {
        current().filter { it.ref.enabled }.map { p ->
            async(Dispatchers.IO) {
                withTimeoutOrNull(15_000L) {
                    runCatching { p.search(query) }.getOrDefault(emptyList())
                } ?: emptyList()
            }
        }.flatMap { runCatching { it.await() }.getOrDefault(emptyList()) }.take(300)
    }
}
