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
        val all = withTimeoutOrNull(60_000L) {
            supervisorScope {
                engines.values.map { eng ->
                    async(Dispatchers.IO) {
                        withTimeoutOrNull(25_000L) {
                            runCatching { eng.loadProviders() }.getOrElse {
                                Log.w("engines", "${eng.kind} load failed: ${it.message}")
                                emptyList()
                            }
                        } ?: run {
                            Log.w("engines", "${eng.kind} load timed out (25s)")
                            emptyList()
                        }
                    }
                }.flatMap { runCatching { it.await() }.getOrDefault(emptyList()) }
            }
        } ?: emptyList()
        cached = all
        return all
    }

    fun current(): List<Provider> = cached

    /**
     * Hot-mount freshly installed/updated providers (the sync-generation trick):
     * replaces same-id entries in place. No full reload — install lands in ~1s.
     */
    fun upsert(providers: List<Provider>) {
        if (providers.isEmpty()) return
        val ids = providers.map { it.ref.id }.toSet()
        cached = cached.filterNot { it.ref.id in ids } + providers
    }

    /** Reload a single engine (uninstall/toggle paths). Bounded, never throws. */
    suspend fun reloadEngine(kind: EngineKind): List<Provider> {
        val eng = engines[kind] ?: return emptyList()
        val fresh = withTimeoutOrNull(30_000L) {
            runCatching { eng.loadProviders() }.getOrElse {
                Log.w("engines", "$kind reload failed: ${it.message}")
                emptyList()
            }
        } ?: emptyList()
        cached = cached.filterNot { it.ref.engine == kind } + fresh
        return fresh
    }

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

    /**
     * Incremental home rows: each provider's rows are delivered via [onRow] the
     * moment they arrive (Stremio board pattern — the page fills in live instead
     * of waiting for the slowest provider). [onError] fires per failed provider
     * so the UI can show a row-level retry instead of a global spinner.
     * Callbacks run on IO threads; hop to FX in the UI layer.
     */
    suspend fun homeRowsEach(
        maxPerProviderRows: Int = 12,
        onRow: (Provider, CatalogRow) -> Unit,
        onError: (Provider, String) -> Unit = { _, _ -> },
    ) = supervisorScope {
        current().filter { it.ref.enabled }.map { p ->
            async(Dispatchers.IO) {
                val rows = withTimeoutOrNull(20_000L) {
                    runCatching { p.homeRows() }.getOrElse {
                        onError(p, it.message?.take(140) ?: "failed")
                        emptyList()
                    }
                } ?: run {
                    onError(p, "timed out after 20s")
                    emptyList()
                }
                for (row in rows.take(maxPerProviderRows)) {
                    val trimmed = if (row.items.size > 14) row.copy(items = row.items.take(14)) else row
                    if (trimmed.items.isNotEmpty()) onRow(p, trimmed)
                }
            }
        }.forEach { runCatching { it.await() } }
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
