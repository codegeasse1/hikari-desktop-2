package codegeasse.hikari.desktop.ui

import codegeasse.hikari.desktop.core.Fx
import codegeasse.hikari.desktop.cs.CsEngine
import codegeasse.hikari.desktop.engine.Engines
import codegeasse.hikari.desktop.hiki.Hiki
import codegeasse.hikari.desktop.install.Installer
import codegeasse.hikari.desktop.model.EngineKind
import codegeasse.hikari.desktop.model.InstallJob
import codegeasse.hikari.desktop.model.JobState
import codegeasse.hikari.desktop.model.ListingItem
import codegeasse.hikari.desktop.nuvio.NuvioEngine
import codegeasse.hikari.desktop.stremio.StremioEngine
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Extension management, modeled on Nuvio's Addons screen + Stremio's Addons
 * route + CloudStream's plugin screens:
 * - overview stats (installed / active / updates)
 * - repositories with add/remove
 * - installed section first (version, enable toggle, update badge, uninstall)
 * - available section with search + install/update-all
 * - every async load has an error state with retry — never a stuck spinner
 */
object Extensions {
    fun build(): Node {
        val tabs = TabPane().apply {
            styleClass.add("tabs")
            tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
        }
        tabs.tabs.addAll(
            Tab("CloudStream", storePane(EngineKind.CS)),
            Tab("Hikari", storePane(EngineKind.HIKI)),
            Tab("Nuvio", nuvioPane()),
            Tab("Stremio", stremioPane()),
        )
        Installer.onChange { Fx.run { refreshJobs() } }
        return VBox(0.0, jobsBox(), tabs).apply {
            VBox.setVgrow(tabs, Priority.ALWAYS)
            padding = Insets(10.0, 14.0, 14.0, 14.0)
        }
    }

    // -- install jobs ---------------------------------------------------------

    private var jobsRef: VBox? = null

    private fun jobsBox(): Node {
        val box = VBox(4.0).apply { padding = Insets(0.0, 0.0, 8.0, 0.0) }
        jobsRef = box
        refreshJobs()
        return box
    }

    private fun refreshJobs() {
        val box = jobsRef ?: return
        box.children.clear()
        for (j in Installer.snapshot()) {
            val st = when (j.state) {
                JobState.QUEUED -> "queued"
                JobState.RUNNING -> if (j.progress >= 0) "${(j.progress * 100).toInt()}%" else "working…"
                JobState.DONE -> "done"
                JobState.FAILED -> "failed: ${j.note}"
            }
            if (j.state == JobState.RUNNING && j.progress >= 0) {
                box.children.add(Ui.progressRow("⬇ ${j.label}", j.progress))
            } else {
                box.children.add(
                    HBox(8.0, Label("⬇ ${j.label}").apply { styleClass.add("job-label") }, Ui.muted(st)).apply {
                        alignment = Pos.CENTER_LEFT
                    },
                )
            }
        }
    }

    // -- generic repo row -----------------------------------------------------

    private fun repoEditor(urls: List<String>, onSave: (List<String>) -> Unit, onChanged: () -> Unit): Node {
        val box = VBox(4.0)
        fun render() {
            box.children.clear()
            for (u in urls.toMutableList()) {
                val del = Ui.ghostBtn("Remove") {
                    onSave(urls - u)
                    onChanged()
                }
                box.children.add(
                    HBox(8.0, Label(u).apply { styleClass.add("mono") }, Ui.spacer(), del).apply {
                        alignment = Pos.CENTER_LEFT
                    },
                )
            }
            val field = TextField().apply {
                promptText = "https://…/repo.json"
                styleClass.add("search")
                prefColumnCount = 40
            }
            val add = Ui.primaryBtn("Add repo") {
                val u = field.text.trim()
                if (u.isNotBlank()) {
                    onSave(urls + u)
                    onChanged()
                }
            }
            box.children.add(HBox(8.0, field, add).apply { alignment = Pos.CENTER_LEFT })
        }
        render()
        return box
    }

    private fun listingRow(
        item: ListingItem,
        enabled: Boolean,
        onToggle: (Boolean) -> Unit,
        onInstall: () -> Unit,
        onUninstall: () -> Unit,
    ): Node {
        val name = Label(item.name).apply { styleClass.add("ext-name") }
        val ver = Ui.muted(listOfNotNull(item.version?.let { "v$it" }, item.authors.take(3).joinToString(", ").takeIf { it.isNotBlank() }).joinToString(" · "))
        val desc = Label(item.description?.take(160) ?: "").apply {
            styleClass.add("muted")
            isWrapText = true
        }
        val badges = HBox(6.0).apply { alignment = Pos.CENTER_LEFT }
        if (item.hasUpdate) badges.children.add(Ui.badge("update available"))
        if (item.installed) badges.children.add(Ui.badge("v${item.installedVersion ?: "?"} installed"))
        val left = VBox(2.0, HBox(8.0, name, ver, badges), desc)
        HBox.setHgrow(left, Priority.ALWAYS)
        val row = HBox(8.0, left)
        row.alignment = Pos.CENTER_LEFT
        if (item.installed) {
            val toggle = CheckBox().apply {
                isSelected = enabled
                setOnAction { onToggle(isSelected) }
            }
            val btnLabel = if (item.hasUpdate) "Update" else "Reinstall"
            row.children.addAll(
                toggle,
                (if (item.hasUpdate) Ui.primaryBtn(btnLabel) { onInstall() } else Ui.ghostBtn(btnLabel) { onInstall() }),
                Ui.ghostBtn("Uninstall") { onUninstall() },
            )
        } else {
            row.children.add(Ui.primaryBtn("Install") { onInstall() })
        }
        return row
    }

    private fun section(title: String, body: Node): Node =
        VBox(8.0, Ui.title(title), body).apply { padding = Insets(6.0, 4.0, 6.0, 4.0) }

    private fun scrollOf(box: VBox): Node = ScrollPane(box).apply {
        styleClass.add("page-scroll")
        isFitToWidth = true
        hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
    }

    // -- CloudStream / Hikari store panes (same shape) --------------------------

    private fun storePane(kind: EngineKind): Node {
        val box = VBox(10.0).apply { padding = Insets(10.0, 6.0, 20.0, 6.0) }
        val isCs = kind == EngineKind.CS
        val repoUrls: () -> List<String> = if (isCs) CsEngine::repoUrls else Hiki::repoUrls
        val saveRepos: (List<String>) -> Unit = if (isCs) CsEngine::saveRepos else Hiki::saveRepos
        val fetch: suspend () -> List<ListingItem> = if (isCs) ({ CsEngine.listings() }) else ({ Hiki.listings() })
        val engineTag = if (isCs) "cs" else "hiki"

        fun rebuild() {
            box.children.clear()
            box.children.add(section("Repositories", repoEditor(repoUrls(), saveRepos) { rebuild() }))
            val stats = Ui.muted("Syncing…")
            val head = HBox(10.0, Ui.title("Extensions"), Ui.spacer(), Ui.ghostBtn("↻ Sync") { rebuild() }, stats).apply {
                alignment = Pos.CENTER_LEFT
            }
            box.children.add(head)
            val body = VBox(8.0, Ui.skeletonRow("Loading…", 4))
            box.children.add(body)
            Bg.run {
                val items = withTimeoutOrNull(60_000L) { runCatching { fetch() }.getOrNull() }
                Fx.run {
                    if (items == null) {
                        body.children.setAll(
                            Ui.errorCard("Sync", "repository unreachable or timed out") { rebuild() },
                        )
                        stats.text = "sync failed"
                    } else {
                        renderStoreList(kind, engineTag, body, items, stats) { rebuild() }
                    }
                }
            }
        }
        rebuild()
        return scrollOf(box)
    }

    private fun renderStoreList(
        kind: EngineKind,
        engineTag: String,
        body: VBox,
        items: List<ListingItem>,
        stats: Label,
        onChanged: () -> Unit,
    ) {
        body.children.clear()
        val installed = items.filter { it.installed }
        val updates = installed.filter { it.hasUpdate }
        val active = installed.count { Installer.isEnabled(engineTag, it.id) }
        stats.text = "${installed.size} installed · $active active · ${updates.size} update${if (updates.size == 1) "" else "s"}"
        // Filter + update-all bar.
        val filter = TextField().apply {
            promptText = "Filter extensions…"
            styleClass.add("search")
            prefColumnCount = 30
        }
        val bar = HBox(8.0, filter).apply { alignment = Pos.CENTER_LEFT }
        if (updates.isNotEmpty()) {
            bar.children.add(
                Ui.primaryBtn("Update all (${updates.size})") {
                    for (u in updates) installListing(kind, engineTag, u, onChanged)
                },
            )
        }
        body.children.add(bar)
        val list = VBox(8.0)
        body.children.add(list)
        fun render() {
            val q = filter.text.trim().lowercase()
            fun match(it: ListingItem) = q.isEmpty() || it.name.lowercase().contains(q) ||
                (it.description?.lowercase()?.contains(q) == true)
            list.children.clear()
            val inst = installed.filter(::match).sortedBy { it.name.lowercase() }
            val avail = items.filter { !it.installed && match(it) }.sortedBy { it.name.lowercase() }
            if (inst.isNotEmpty()) {
                list.children.add(Ui.sectionHead("Installed", "${inst.size}"))
                for (it in inst) {
                    list.children.add(
                        listingRow(
                            it,
                            enabled = Installer.isEnabled(engineTag, it.id),
                            onToggle = { en ->
                                Installer.setEnabled(engineTag, it.id, en)
                                Bg.run {
                                    Engines.reloadEngine(kind)
                                    Fx.run { onChanged() }
                                }
                            },
                            onInstall = { installListing(kind, engineTag, it, onChanged) },
                            onUninstall = {
                                Installer.uninstall(engineTag, it.id.removePrefix("$engineTag|"))
                                Bg.run {
                                    Engines.reloadEngine(kind)
                                    Fx.run { onChanged() }
                                }
                            },
                        ),
                    )
                }
            }
            if (avail.isNotEmpty()) {
                list.children.add(Ui.sectionHead("Available", "${avail.size}"))
                for (it in avail.take(300)) {
                    list.children.add(
                        listingRow(
                            it,
                            enabled = true,
                            onToggle = {},
                            onInstall = { installListing(kind, engineTag, it, onChanged) },
                            onUninstall = {},
                        ),
                    )
                }
            }
            if (inst.isEmpty() && avail.isEmpty()) {
                list.children.add(
                    if (q.isEmpty()) Ui.muted("No plugins found — check the repo URLs above.")
                    else Ui.muted("No extensions match \"$q\"."),
                )
            }
        }
        filter.textProperty().addListener { _, _, _ -> render() }
        render()
    }

    private fun installListing(kind: EngineKind, engineTag: String, item: ListingItem, onChanged: () -> Unit) {
        val job = InstallJob("$engineTag:${item.id}", "${kind.label} · ${item.name}")
        job.state = JobState.RUNNING
        Installer.enqueue(job) {
            // Stream to disk with live progress, then hot-mount just this plugin.
            // No full reload — this is the install-in-a-second path.
            val dir = if (kind == EngineKind.CS) {
                CsEngine.install(item) { Installer.progress(job.key, it * 0.9, "downloading") }
            } else {
                Hiki.install(item) { Installer.progress(job.key, it * 0.9, "downloading") }
            } ?: throw IllegalStateException("download or validation failed")
            Installer.progress(job.key, 0.95, "mounting")
            val fresh = if (kind == EngineKind.CS) CsEngine.mountDir(dir) else Hiki.mountDir(dir)
            if (fresh.isEmpty()) throw IllegalStateException("downloaded, but the plugin would not load")
            Engines.upsert(fresh)
            Installer.progress(job.key, 1.0, "ready")
            Fx.run { onChanged() }
        }
    }

    // -- Nuvio -----------------------------------------------------------------

    private fun nuvioPane(): Node {
        val box = VBox(10.0).apply { padding = Insets(10.0, 6.0, 20.0, 6.0) }
        fun rebuild() {
            box.children.clear()
            box.children.add(section("Scraper manifests", repoEditor(NuvioEngine.repoUrls(), NuvioEngine::saveRepos) { rebuild() }))
            val stats = Ui.muted("Loading scrapers…")
            box.children.add(HBox(10.0, Ui.title("Scrapers"), Ui.spacer(), Ui.ghostBtn("↻ Sync") { rebuild() }, stats).apply { alignment = Pos.CENTER_LEFT })
            val list = VBox(8.0, Ui.skeletonRow("Loading…", 4))
            box.children.add(list)
            Bg.run {
                val items = withTimeoutOrNull(60_000L) { runCatching { NuvioEngine.scrapers() }.getOrNull() }
                Fx.run {
                    if (items == null) {
                        list.children.setAll(Ui.errorCard("Scrapers", "manifests unreachable or timed out") { rebuild() })
                        stats.text = "load failed"
                    } else {
                        stats.text = "${items.size} scrapers · ${items.count { it.enabled }} enabled"
                        list.children.clear()
                        if (items.isEmpty()) list.children.add(Ui.muted("No scrapers found."))
                        for (s in items.sortedBy { it.dto.name.lowercase() }) {
                            val toggle = CheckBox().apply {
                                isSelected = s.enabled
                                setOnAction { NuvioEngine.setScraperEnabled(s.dto.id, isSelected) }
                            }
                            list.children.add(
                                HBox(
                                    8.0,
                                    toggle,
                                    VBox(
                                        2.0,
                                        Label(s.dto.name).apply { styleClass.add("ext-name") },
                                        Ui.muted(listOfNotNull(s.dto.version.let { "v$it" }, s.dto.description).joinToString(" · ").take(160)),
                                    ),
                                ).apply { alignment = Pos.CENTER_LEFT },
                            )
                        }
                    }
                }
            }
        }
        rebuild()
        return scrollOf(box)
    }

    // -- Stremio ----------------------------------------------------------------

    private fun stremioPane(): Node {
        val box = VBox(10.0).apply { padding = Insets(10.0, 6.0, 20.0, 6.0) }
        fun rebuild() {
            box.children.clear()
            val urls = StremioEngine.savedManifests()
            val stats = Ui.muted("${urls.size} addon${if (urls.size == 1) "" else "s"} · Cinemeta ships built-in")
            box.children.add(HBox(10.0, Ui.title("Installed addons"), Ui.spacer(), stats).apply { alignment = Pos.CENTER_LEFT })
            val list = VBox(6.0, Ui.muted("Resolving addons…"))
            box.children.add(list)
            Bg.run {
                data class Card(val url: String, val name: String, val id: String?, val version: String?)
                val cards = urls.map { u ->
                    val m = runCatching { StremioEngine.fetchManifest(u) }.getOrNull()
                    Card(u, m?.name ?: u, m?.id, m?.version)
                }
                Fx.run {
                    list.children.clear()
                    for (c in cards) {
                        val key = c.id?.let { "stremio|$it" }
                        val toggle = CheckBox().apply {
                            isSelected = key?.let { Installer.isEnabled("stremio", it) } ?: true
                            setOnAction {
                                if (key != null) {
                                    Installer.setEnabled("stremio", key, isSelected)
                                    Bg.run { Engines.reloadEngine(EngineKind.STREMIO) }
                                }
                            }
                        }
                        val del = Ui.ghostBtn("Remove") {
                            StremioEngine.saveManifests(urls - c.url)
                            Bg.run {
                                Engines.reloadEngine(EngineKind.STREMIO)
                                Fx.run { rebuild() }
                            }
                        }
                        list.children.add(
                            HBox(
                                8.0,
                                toggle,
                                VBox(
                                    2.0,
                                    Label(c.name).apply { styleClass.add("ext-name") },
                                    Ui.muted(listOfNotNull(c.version?.let { "v$it" }, c.url).joinToString(" · ")),
                                ),
                                Ui.spacer(),
                                del,
                            ).apply { alignment = Pos.CENTER_LEFT },
                        )
                    }
                }
            }
            val field = TextField().apply {
                promptText = "https://…/manifest.json"
                styleClass.add("search")
                prefColumnCount = 40
            }
            val add = Ui.primaryBtn("Add addon") {
                val u = field.text.trim()
                if (u.isNotBlank()) {
                    val job = InstallJob("stremio:$u", "Stremio addon")
                    job.state = JobState.RUNNING
                    Installer.enqueue(job) {
                        StremioEngine.fetchManifest(u)
                            ?: throw IllegalStateException("not a Stremio manifest")
                        val clean = u.trim().removeSuffix("/manifest.json").trimEnd('/') + "/manifest.json"
                        StremioEngine.saveManifests(StremioEngine.savedManifests() + clean)
                        Engines.reloadEngine(EngineKind.STREMIO)
                        Fx.run { rebuild() }
                    }
                }
            }
            box.children.add(section("Add addon", HBox(8.0, field, add).apply { alignment = Pos.CENTER_LEFT }))
            box.children.add(Ui.muted("Addons are validated by fetching their manifest — installs take about a second."))
        }
        rebuild()
        return scrollOf(box)
    }
}
