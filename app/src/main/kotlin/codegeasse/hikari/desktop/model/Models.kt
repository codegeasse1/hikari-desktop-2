package codegeasse.hikari.desktop.model

/** Which engine a provider/install belongs to. */
enum class EngineKind(val label: String) {
    CS("CloudStream"),
    HIKI("Hikari"),
    NUVIO("Nuvio"),
    STREMIO("Stremio"),
}

enum class MediaKind { MOVIE, SERIES, UNKNOWN }

/** A title anywhere (rows, search, history). */
data class MediaItem(
    val providerId: String,
    val id: String,
    val title: String,
    val kind: MediaKind = MediaKind.UNKNOWN,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val year: Int? = null,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
    val extra: Map<String, String> = emptyMap(),
) {
    val uniqueId: String get() = "$providerId::$id"
}

data class CatalogRow(
    val title: String,
    val providerName: String,
    val items: List<MediaItem>,
)

data class EpisodeItem(
    val id: String,
    val number: Int,
    val season: Int = 1,
    val name: String? = null,
    val stillUrl: String? = null,
    val overview: String? = null,
)

data class SubItem(val lang: String, val url: String)

data class StreamItem(
    val name: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val quality: String? = null,
    val subtitles: List<SubItem> = emptyList(),
)

/** An installed provider (engine plugin instance). */
data class ProviderRef(
    val id: String,
    val engine: EngineKind,
    val name: String,
    val iconUrl: String? = null,
    val enabled: Boolean = true,
    val version: String? = null,
)

/** A plugin repository (URL that lists installable plugins). */
data class RepoRef(
    val url: String,
    val engine: EngineKind,
    val name: String,
    val enabled: Boolean = true,
)

/** Something installable from a repository listing. */
data class ListingItem(
    val engine: EngineKind,
    val repoUrl: String,
    val id: String,
    val name: String,
    val version: String? = null,
    val description: String? = null,
    val iconUrl: String? = null,
    val authors: List<String> = emptyList(),
    /** Where to fetch the payload (one or more mirror URLs). */
    val payloadUrls: List<String> = emptyList(),
    val installed: Boolean = false,
    val installedVersion: String? = null,
    val hasUpdate: Boolean = false,
)

enum class JobState { QUEUED, RUNNING, DONE, FAILED }

data class InstallJob(
    val key: String,
    val label: String,
    var state: JobState = JobState.QUEUED,
    var progress: Double = -1.0,
    var note: String = "",
)
