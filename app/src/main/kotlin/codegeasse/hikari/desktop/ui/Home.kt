package codegeasse.hikari.desktop.ui

import codegeasse.hikari.desktop.core.Fx
import codegeasse.hikari.desktop.core.LibraryStore
import codegeasse.hikari.desktop.core.Log
import codegeasse.hikari.desktop.engine.Engines
import codegeasse.hikari.desktop.engine.Provider
import codegeasse.hikari.desktop.install.Installer
import codegeasse.hikari.desktop.model.CatalogRow
import codegeasse.hikari.desktop.model.EngineKind
import codegeasse.hikari.desktop.model.MediaItem
import codegeasse.hikari.desktop.nuvio.NuvioEngine
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.image.ImageView
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.stage.Stage
import javafx.util.Duration

/**
 * Home: cinematic hero carousel, continue watching, engine filter chips,
 * provider management, and per-provider rails that fill in live as each
 * provider answers. A dead provider shows a row-level retry — the page as a
 * whole never gets stuck on a spinner.
 */
object Home {
    fun build(): Node {
        val body = VBox(14.0).apply { padding = Insets(0.0, 0.0, 24.0, 0.0) }
        val heroSlot = StackPane().apply {
            prefHeight = 380.0
            minHeight = 380.0
            maxHeight = 380.0
            styleClass.add("hero-slot")
        }
        body.children.add(heroSlot)
        val toolbar = HBox(8.0).apply {
            padding = Insets(0.0, 16.0, 0.0, 16.0)
            alignment = Pos.CENTER_LEFT
        }
        body.children.add(toolbar)
        val rowsBox = VBox(16.0).apply { padding = Insets(0.0, 16.0, 0.0, 16.0) }
        rowsBox.children.add(Ui.skeletonRow("Loading…"))
        body.children.add(rowsBox)

        val scroll = ScrollPane(body).apply {
            styleClass.add("page-scroll")
            isFitToWidth = true
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
        }
        Bg.run { boot(heroSlot, toolbar, rowsBox) }
        return scroll
    }

    private fun centered(n: Node): Node = HBox(n).apply { alignment = Pos.CENTER }

    // -- boot: providers first, rows stream in ---------------------------------

    private suspend fun boot(heroSlot: StackPane, toolbar: HBox, rowsBox: VBox) {
        val t0 = System.currentTimeMillis()
        val providers = Engines.reload()
        Log.i("home", "reload took ${System.currentTimeMillis() - t0}ms, ${providers.size} providers")
        if (providers.isEmpty()) {
            Fx.run {
                heroSlot.children.clear()
                heroSlot.prefHeight = 0.0
                heroSlot.minHeight = 0.0
                heroSlot.maxHeight = 0.0
                toolbar.children.clear()
                rowsBox.children.setAll(
                    Ui.emptyState(
                        "No extensions installed",
                        "HIKARI plays movies and series through extensions. Install a plugin " +
                            "or add a Stremio addon to fill this page.",
                        "Open Extensions",
                    ) { AppShell.goExtensions() },
                )
            }
            return
        }
        val history = LibraryStore.history(14)
        val heroPool = java.util.concurrent.ConcurrentLinkedQueue<MediaItem>()
        val rowNodes = java.util.concurrent.ConcurrentHashMap<String, Node>()
        val failed = java.util.concurrent.ConcurrentHashMap<String, String>()
        var toolbarDone = false

        Engines.homeRowsEach(
            onRow = { p, row ->
                val items = filteredItems(p, row.items)
                if (items.isNotEmpty()) {
                    heroPool.addAll(items.filter { !it.backdropUrl.isNullOrBlank() }.take(3))
                    Fx.run {
                        if (!toolbarDone) {
                            toolbarDone = true
                            toolbar.children.setAll(buildToolbar(rowsBox))
                            renderHistory(rowsBox, history)
                        }
                        val key = "${p.ref.id}::${row.title}"
                        val node = Ui.rail(row.title, p.ref.name, items, { AppShell.goDetail(it) })
                        rowNodes[key] = node
                        insertRowSorted(rowsBox, key, node, providers)
                        refreshHero(heroSlot, heroPool)
                    }
                }
            },
            onError = { p, msg ->
                val filtered = AppShell.engineFilter != null && AppShell.engineOfProvider(p.ref.id) !in AppShell.engineFilter!!
                if (!filtered) {
                    failed[p.ref.id] = msg
                    Fx.run {
                        if (!toolbarDone) {
                            toolbarDone = true
                            toolbar.children.setAll(buildToolbar(rowsBox))
                            renderHistory(rowsBox, history)
                        }
                        insertRowSorted(
                            rowsBox,
                            "err::${p.ref.id}",
                            Ui.errorCard(p.ref.name, msg) {
                                Bg.run { retryProvider(p, rowsBox, providers, heroPool, heroSlot, toolbar) }
                            },
                            providers,
                        )
                    }
                }
            },
        )
        Fx.run {
            if (!toolbarDone) {
                toolbar.children.setAll(buildToolbar(rowsBox))
                renderHistory(rowsBox, history)
            }
            if (rowNodes.isEmpty() && failed.isEmpty()) {
                rowsBox.children.setAll(
                    Ui.emptyState(
                        "Nothing here yet",
                        "Installed providers returned no rows. They may be offline — or disabled. " +
                            "Check Providers, or install more extensions.",
                        "Manage Providers",
                    ) { showProvidersDialog(rowsBox) },
                )
                heroSlot.children.clear()
            } else if (rowNodes.isEmpty()) {
                rowsBox.children.add(
                    0,
                    centered(Ui.muted("All providers failed — fix them above, or install more extensions.")),
                )
            }
        }
    }

    private fun retryProvider(
        p: Provider,
        rowsBox: VBox,
        providers: List<Provider>,
        heroPool: java.util.concurrent.ConcurrentLinkedQueue<MediaItem>,
        heroSlot: StackPane,
        toolbar: HBox,
    ) {
        Bg.run {
            val rows = runCatching { p.homeRows() }.getOrDefault(emptyList()).take(12)
            Fx.run {
                rowsBox.children.removeIf { (it.properties["hk"] as? String)?.startsWith("err::${p.ref.id}") == true }
                for (row in rows) {
                    val items = filteredItems(p, row.items)
                    if (items.isEmpty()) continue
                    heroPool.addAll(items.filter { !it.backdropUrl.isNullOrBlank() }.take(3))
                    val key = "${p.ref.id}::retry::${row.title}"
                    val node = Ui.rail(row.title, p.ref.name, items, { AppShell.goDetail(it) })
                    node.properties["hk"] = key
                    insertRowSorted(rowsBox, key, node, providers)
                    refreshHero(heroSlot, heroPool)
                }
            }
        }
    }

    private fun insertRowSorted(rowsBox: VBox, key: String, node: Node, providers: List<Provider>) {
        node.properties["hk"] = key
        val order = providers.map { it.ref.id }
        fun rank(k: String): Int {
            val pid = k.removePrefix("err::").substringBefore("::")
            val ix = order.indexOf(pid)
            return if (ix < 0) Int.MAX_VALUE else ix
        }
        val kids = rowsBox.children.filter { it.properties["hk"] != null }.toMutableList()
        kids.removeIf { it.properties["hk"] == key }
        kids.add(node)
        kids.sortBy { rank(it.properties["hk"] as String) }
        // Keep the history block (no hk) and skeleton/empty states first.
        val fixed = rowsBox.children.filter { it.properties["hk"] == null }
        rowsBox.children.setAll(fixed + kids)
        // Drop the initial skeleton once real rows arrive.
        rowsBox.children.removeIf { it.properties["skeleton"] == true }
    }

    // -- toolbar: engine chips + providers + reload ------------------------------

    private fun buildToolbar(rowsBox: VBox): List<Node> {
        val out = mutableListOf<Node>()
        val all = listOf(null to "All") + EngineKind.entries.map { it to it.label }
        for ((kind, label) in all) {
            out.add(
                Ui.chip(label, AppShell.engineFilter == (kind?.let { setOf(it) })) {
                    AppShell.engineFilter = kind?.let { setOf(it) }
                    AppShell.goHome()
                },
            )
        }
        out.add(Ui.spacer())
        val active = Engines.current().count { it.ref.enabled }
        val total = Engines.current().size
        out.add(Ui.muted("$active/$total providers").apply { padding = Insets(0.0, 4.0, 0.0, 4.0) })
        out.add(Ui.ghostBtn("Providers") { showProvidersDialog(rowsBox) })
        out.add(Ui.ghostBtn("⟳ Reload") { AppShell.goHome() })
        return out
    }

    private fun showProvidersDialog(rowsBox: VBox) {
        val owner = rowsBox.scene?.window as? Stage ?: return
        val list = VBox(8.0)
        for (p in Engines.current().sortedBy { it.ref.name.lowercase() }) {
            val box = CheckBox("${p.ref.name}  ·  ${p.ref.engine.label}").apply {
                isSelected = p.ref.enabled
                styleClass.add("check-box")
            }
            val row = HBox(10.0, box, Ui.spacer(), Ui.muted(p.ref.version?.take(24) ?: "")).apply {
                alignment = Pos.CENTER_LEFT
            }
            row.properties["pid"] = p.ref.id
            list.children.add(row)
        }
        val dlgBody = VBox(
            10.0,
            Ui.muted("Disabled providers are skipped everywhere: home, search, detail."),
            list,
            HBox(
                8.0,
                Ui.spacer(),
                Ui.ghostBtn("Cancel") { (list.scene?.window as? Stage)?.close() },
                Ui.primaryBtn("Apply") {
                    for (row in list.children) {
                        val pid = row.properties["pid"] as? String ?: continue
                        val cb = (row as HBox).children.filterIsInstance<CheckBox>().firstOrNull() ?: continue
                        val eng = Engines.current().firstOrNull { it.ref.id == pid }?.ref?.engine
                        when (eng) {
                            EngineKind.NUVIO -> NuvioEngine.setScraperEnabled(pid.removePrefix("nuvio|"), cb.isSelected)
                            EngineKind.CS, EngineKind.HIKI, EngineKind.STREMIO ->
                                Installer.setEnabled(eng.name.lowercase(), pid, cb.isSelected)
                            null -> {}
                        }
                    }
                    (list.scene?.window as? Stage)?.close()
                    Bg.run {
                        Engines.reload()
                        Fx.run { AppShell.goHome() }
                    }
                },
            ),
        )
        Ui.dialog(owner, "Providers", dlgBody).show()
    }

    // -- history -----------------------------------------------------------------

    private fun renderHistory(rowsBox: VBox, history: List<LibraryStore.Entry>) {
        rowsBox.children.clear()
        val skel = Ui.skeletonRow("Loading…")
        skel.properties["skeleton"] = true
        rowsBox.children.add(skel)
        if (history.isNotEmpty()) {
            val cards = HBox(10.0)
            for (e in history) {
                cards.children.add(
                    Ui.continueCard(e, progressSub(e), { openHistoryEntry(it) }, {
                        LibraryStore.removeHistory(it.providerId, it.id)
                        AppShell.goHome()
                    }),
                )
            }
            val scroller = ScrollPane(cards).apply {
                styleClass.add("rail-scroll")
                hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
                vbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
                isFitToHeight = true
            }
            val block = VBox(6.0, Ui.title("Continue Watching"), scroller)
            rowsBox.children.add(0, block)
        }
    }

    private fun progressSub(e: LibraryStore.Entry): String =
        if (e.season > 0 || e.episode > 0) "S${e.season} E${e.episode}" else "Continue"

    private fun openHistoryEntry(e: LibraryStore.Entry) {
        Bg.run { openHistoryEntrySync(e) }
    }

    private suspend fun openHistoryEntrySync(e: LibraryStore.Entry) {
        val providers = Engines.current()
        val p = providers.firstOrNull { it.ref.id == e.providerId } ?: return
        try {
            val fake = MediaItem(e.providerId, e.id, e.title, posterUrl = e.posterUrl, backdropUrl = e.backdropUrl)
            val full = p.detail(fake)
            Fx.run { AppShell.goDetail(full) }
        } catch (_: Throwable) {
        }
    }

    // -- filtering ---------------------------------------------------------------

    private fun filteredItems(p: Provider, items: List<MediaItem>): List<MediaItem> {
        val f = AppShell.engineFilter ?: return items
        return if (AppShell.engineOfProvider(p.ref.id) in f) items else emptyList()
    }

    // -- hero carousel -----------------------------------------------------------

    private var carousel: Timeline? = null
    private var heroItems: List<MediaItem> = emptyList()
    private var heroIx = 0

    private fun refreshHero(heroSlot: StackPane, pool: java.util.concurrent.ConcurrentLinkedQueue<MediaItem>) {
        val fresh = pool.distinctBy { it.uniqueId }.take(10)
        if (fresh.isEmpty()) return
        if (fresh.map { it.uniqueId } == heroItems.map { it.uniqueId }) return
        heroItems = fresh
        heroIx = 0
        carousel?.stop()
        showHero(heroSlot, heroItems[0], fresh.size)
        if (fresh.size < 2) return
        carousel = Timeline(KeyFrame(Duration.seconds(8.0), { _ ->
            if (heroSlot.scene == null) {
                carousel?.stop()
                return@KeyFrame
            }
            heroIx = (heroIx + 1) % heroItems.size
            showHero(heroSlot, heroItems[heroIx], heroItems.size)
        })).apply { cycleCount = Timeline.INDEFINITE; play() }
    }

    private fun showHero(heroSlot: StackPane, item: MediaItem, total: Int) {
        heroSlot.children.setAll(hero(item, total))
    }

    private fun hero(item: MediaItem, total: Int): Node {
        val bg = ImageView().apply {
            fitWidth = 1280.0
            fitHeight = 380.0
            isPreserveRatio = false
            isSmooth = true
            styleClass.add("hero-bg")
        }
        Img.load(item.backdropUrl ?: item.posterUrl, 1280.0, bg)
        val title = Label(item.title).apply { styleClass.add("hero-title") }
        val meta = Ui.muted(
            listOfNotNull(
                item.year?.toString(),
                item.kind.name.lowercase().replaceFirstChar { it.uppercase() },
                item.genres.take(3).joinToString(" • ").takeIf { it.isNotBlank() },
            ).joinToString("  •  ").ifBlank { item.providerId },
        )
        val dots = if (total > 1) Ui.muted("${heroIx + 1}/$total") else Ui.muted("")
        val overview = Label(item.overview?.take(240) ?: "").apply {
            styleClass.add("hero-overview")
            isWrapText = true
            maxWidth = 560.0
        }
        val row = HBox(
            10.0,
            Ui.primaryBtn("▶  Watch Now") { AppShell.goDetail(item) },
            Ui.ghostBtn("More Info") { AppShell.goDetail(item) },
        ).apply { alignment = Pos.CENTER_LEFT }
        val info = VBox(8.0, title, meta, overview, row).apply {
            styleClass.add("hero-info")
            alignment = Pos.BOTTOM_LEFT
            padding = Insets(0.0, 0.0, 26.0, 28.0)
            maxWidth = 640.0
        }
        if (total > 1) {
            val dotsRow = HBox(4.0, dots).apply { padding = Insets(0.0, 28.0, 12.0, 0.0) }
            val stack = StackPane(bg, info, dotsRow).apply {
                styleClass.add("hero")
                StackPane.setAlignment(info, Pos.BOTTOM_LEFT)
                StackPane.setAlignment(dotsRow, Pos.BOTTOM_RIGHT)
            }
            return stack
        }
        return StackPane(bg, info).apply {
            styleClass.add("hero")
            StackPane.setAlignment(info, Pos.BOTTOM_LEFT)
        }
    }
}
