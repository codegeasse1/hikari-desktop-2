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

/** Extension management: repos, install/update, enable toggles, jobs. */
object Extensions {
    fun build(): Node {
        val tabs = TabPane().apply {
            styleClass.add("tabs")
            tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
        }
        val csTab = Tab("CloudStream", csPane())
        val hikiTab = Tab("Hikari", hikiPane())
        val nuvioTab = Tab("Nuvio", nuvioPane())
        val stremioTab = Tab("Stremio", stremioPane())
        tabs.tabs.addAll(csTab, hikiTab, nuvioTab, stremioTab)
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
        val jobs = Installer.snapshot()
        box.children.clear()
        for (j in jobs) {
            val st = when (j.state) {
                JobState.QUEUED -> "queued"
                JobState.RUNNING -> if (j.progress >= 0) "${(j.progress * 100).toInt()}%" else "working…"
                JobState.DONE -> "done"
                JobState.FAILED -> "failed: ${j.note}"
            }
            box.children.add(
                HBox(8.0, Label("⬇ ${j.label}").apply { styleClass.add("job-label") }, Ui.muted(st)).apply {
                    alignment = Pos.CENTER_LEFT
                },
            )
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
        val left = VBox(2.0, HBox(8.0, name, ver), desc)
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
                Ui.muted(if (item.hasUpdate) "update available" else "installed"),
                Ui.ghostBtn(btnLabel) { onInstall() },
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

    // -- CloudStream -----------------------------------------------------------

    private fun csPane(): Node {
        val box = VBox(10.0).apply { padding = Insets(10.0, 6.0, 20.0, 6.0) }
        fun rebuild() {
            box.children.clear()
            box.children.add(section("Repositories", repoEditor(CsEngine.repoUrls(), CsEngine::saveRepos) { rebuild() }))
            val list = VBox(8.0, Ui.muted("Loading plugin list…"))
            box.children.add(section("Available plugins", list))
            Bg.run {
                val items = CsEngine.listings()
                Fx.run { renderCsList(list, items) { rebuild() } }
            }
            box.children.add(
                HBox(8.0, Ui.ghostBtn("⟳ Reload providers") { Bg.run { Engines.reload() } }).apply {
                    alignment = Pos.CENTER_LEFT
                },
            )
        }
        rebuild()
        return scrollOf(box)
    }

    private fun renderCsList(list: VBox, items: List<ListingItem>, onChanged: () -> Unit) {
        list.children.clear()
        if (items.isEmpty()) {
            list.children.add(Ui.muted("No plugins found — check the repo URLs above."))
            return
        }
        for (it in items.sortedBy { it.name.lowercase() }) {
            list.children.add(
                listingRow(
                    it,
                    enabled = Installer.isEnabled("cs", it.id),
                    onToggle = { en -> Installer.setEnabled("cs", it.id, en) },
                    onInstall = { installCs(it, onChanged) },
                    onUninstall = {
                        Installer.uninstall("cs", it.id.removePrefix("cs|"))
                        onChanged()
                    },
                ),
            )
        }
    }

    private fun installCs(item: ListingItem, onChanged: () -> Unit) {
        val job = InstallJob("cs:${item.id}", item.name)
        job.state = JobState.RUNNING
        Installer.enqueue(job) {
            Installer.progress(job.key, 0.2, "downloading")
            val ok = CsEngine.install(item) { Installer.progress(job.key, 0.2 + it * 0.7, "downloading") }
            if (!ok) throw IllegalStateException("download or validation failed")
            Installer.progress(job.key, 0.95, "reloading")
            Engines.reload()
            Fx.run { onChanged() }
        }
    }

    // -- Hikari ----------------------------------------------------------------

    private fun hikiPane(): Node {
        val box = VBox(10.0).apply { padding = Insets(10.0, 6.0, 20.0, 6.0) }
        fun rebuild() {
            box.children.clear()
            box.children.add(section("Repositories", repoEditor(Hiki.repoUrls(), Hiki::saveRepos) { rebuild() }))
            val list = VBox(8.0, Ui.muted("Loading plugin list…"))
            box.children.add(section("Available plugins", list))
            Bg.run {
                val items = Hiki.listings()
                Fx.run { renderHikiList(list, items) { rebuild() } }
            }
            box.children.add(
                HBox(8.0, Ui.ghostBtn("⟳ Reload providers") { Bg.run { Engines.reload() } }).apply {
                    alignment = Pos.CENTER_LEFT
                },
            )
        }
        rebuild()
        return scrollOf(box)
    }

    private fun renderHikiList(list: VBox, items: List<ListingItem>, onChanged: () -> Unit) {
        list.children.clear()
        if (items.isEmpty()) {
            list.children.add(Ui.muted("No plugins found — check the repo URLs above."))
            return
        }
        for (it in items.sortedBy { it.name.lowercase() }) {
            list.children.add(
                listingRow(
                    it,
                    enabled = Installer.isEnabled("hiki", it.id),
                    onToggle = { en -> Installer.setEnabled("hiki", it.id, en) },
                    onInstall = { installHiki(it, onChanged) },
                    onUninstall = {
                        Installer.uninstall("hiki", it.id.removePrefix("hiki|"))
                        onChanged()
                    },
                ),
            )
        }
    }

    private fun installHiki(item: ListingItem, onChanged: () -> Unit) {
        val job = InstallJob("hiki:${item.id}", item.name)
        job.state = JobState.RUNNING
        Installer.enqueue(job) {
            Installer.progress(job.key, 0.2, "downloading")
            val ok = Hiki.install(item) { Installer.progress(job.key, 0.2 + it * 0.7, "downloading") }
            if (!ok) throw IllegalStateException("download or validation failed")
            Installer.progress(job.key, 0.95, "reloading")
            Engines.reload()
            Fx.run { onChanged() }
        }
    }

    // -- Nuvio -----------------------------------------------------------------

    private fun nuvioPane(): Node {
        val box = VBox(10.0).apply { padding = Insets(10.0, 6.0, 20.0, 6.0) }
        fun rebuild() {
            box.children.clear()
            box.children.add(section("Scraper manifests", repoEditor(NuvioEngine.repoUrls(), NuvioEngine::saveRepos) { rebuild() }))
            val list = VBox(8.0, Ui.muted("Loading scrapers…"))
            box.children.add(section("Scrapers", list))
            Bg.run {
                val items = NuvioEngine.scrapers()
                Fx.run {
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
        rebuild()
        return scrollOf(box)
    }

    // -- Stremio ----------------------------------------------------------------

    private fun stremioPane(): Node {
        val box = VBox(10.0).apply { padding = Insets(10.0, 6.0, 20.0, 6.0) }
        fun rebuild() {
            box.children.clear()
            val urls = StremioEngine.savedManifests()
            val list = VBox(4.0)
            for (u in urls) {
                val del = Ui.ghostBtn("Remove") {
                    if (u != StremioEngine.CINEMETA) {
                        StremioEngine.saveManifests(urls - u)
                        Bg.run { Engines.reload() }
                        rebuild()
                    }
                }
                list.children.add(
                    HBox(8.0, Label(u).apply { styleClass.add("mono") }, Ui.spacer(), del).apply {
                        alignment = Pos.CENTER_LEFT
                    },
                )
            }
            val field = TextField().apply {
                promptText = "https://…/manifest.json"
                styleClass.add("search")
                prefColumnCount = 40
            }
            val add = Ui.primaryBtn("Add addon") {
                val u = field.text.trim()
                if (u.isNotBlank()) {
                    val job = InstallJob("stremio:$u", "addon")
                    job.state = JobState.RUNNING
                    Installer.enqueue(job) {
                        val m = StremioEngine.fetchManifest(u)
                            ?: throw IllegalStateException("not a Stremio manifest")
                        val clean = u.trim().removeSuffix("/manifest.json").trimEnd('/') + "/manifest.json"
                        StremioEngine.saveManifests(StremioEngine.savedManifests() + clean)
                        Engines.reload()
                        Fx.run { rebuild() }
                    }
                }
            }
            box.children.add(section("Installed addons (Cinemeta ships built-in)", VBox(6.0, list, HBox(8.0, field, add).apply { alignment = Pos.CENTER_LEFT })))
            box.children.add(Ui.muted("Addons are validated by fetching their manifest — installs take about a second."))
        }
        rebuild()
        return scrollOf(box)
    }
}
