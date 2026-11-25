package codegeasse.hikari.desktop.ui

import codegeasse.hikari.desktop.core.Fx
import codegeasse.hikari.desktop.core.LibraryStore
import codegeasse.hikari.desktop.engine.Engines
import codegeasse.hikari.desktop.model.CatalogRow
import codegeasse.hikari.desktop.model.EngineKind
import codegeasse.hikari.desktop.model.MediaItem
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.image.ImageView
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox

/** Home: cinematic hero, continue watching, engine filter chips, rails. */
object Home {
    fun build(): Node {
        val body = VBox(14.0).apply { padding = Insets(0.0, 0.0, 24.0, 0.0) }
        val heroSlot = StackPane(Ui.spinner().apply {
            StackPane.setAlignment(this, Pos.CENTER)
        }).apply {
            prefHeight = 380.0
            styleClass.add("hero-slot")
        }
        body.children.add(heroSlot)
        val chips = HBox(8.0).apply {
            padding = Insets(0.0, 16.0, 0.0, 16.0)
            alignment = Pos.CENTER_LEFT
        }
        body.children.add(chips)
        val rowsBox = VBox(16.0).apply { padding = Insets(0.0, 16.0, 0.0, 16.0) }
        rowsBox.children.add(centered(Ui.spinner()))
        body.children.add(rowsBox)

        val scroll = ScrollPane(body).apply {
            styleClass.add("page-scroll")
            isFitToWidth = true
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
        }

        Bg.run { loadInto(heroSlot, chips, rowsBox) }
        return scroll
    }

    private fun centered(n: Node): Node = HBox(n).apply { alignment = Pos.CENTER }

    private suspend fun loadInto(heroSlot: StackPane, chips: HBox, rowsBox: VBox) {
        val providers = Engines.reload()
        if (providers.isEmpty()) {
            Fx.run {
                rowsBox.children.setAll(
                    centered(Ui.muted("No extensions installed yet — open Extensions (◫) to add some.")),
                )
            }
            return
        }
        val rows = Engines.homeRows()
        val history = LibraryStore.history(14)
        val heroItem = rows.asSequence()
            .flatMap { it.items.asSequence() }
            .firstOrNull { !it.backdropUrl.isNullOrBlank() }
            ?: rows.firstOrNull()?.items?.firstOrNull()
        Fx.run {
            heroItem?.let { heroSlot.children.setAll(hero(it)) } ?: heroSlot.children.clear()
            renderChips(chips, rowsBox, rows, history)
            renderRows(rowsBox, rows, history)
        }
    }

    private fun renderChips(chips: HBox, rowsBox: VBox, rows: List<CatalogRow>, history: List<LibraryStore.Entry>) {
        chips.children.clear()
        val all = listOf(null to "All") + EngineKind.entries.map { it to it.label }
        for ((kind, label) in all) {
            chips.children.add(
                Ui.chip(label, AppShell.engineFilter == (kind?.let { setOf(it) })) {
                    AppShell.engineFilter = kind?.let { setOf(it) }
                    renderChips(chips, rowsBox, rows, history)
                    renderRows(rowsBox, rows, history)
                },
            )
        }
        chips.children.add(Ui.spacer())
        chips.children.add(Ui.ghostBtn("⟳ Reload") {
            rowsBox.children.setAll(centered(Ui.spinner()))
            Bg.run {
                Engines.reload()
                val fresh = Engines.homeRows()
                val h = LibraryStore.history(14)
                Fx.run { renderRows(rowsBox, fresh, h) }
            }
        })
    }

    private fun filtered(rows: List<CatalogRow>): List<CatalogRow> {
        val f = AppShell.engineFilter ?: return rows
        return rows.mapNotNull { row ->
            val items = row.items.filter { AppShell.engineOfProvider(it.providerId) in f }
            if (items.isEmpty()) null else row.copy(items = items)
        }
    }

    private fun renderRows(rowsBox: VBox, rows: List<CatalogRow>, history: List<LibraryStore.Entry>) {
        rowsBox.children.clear()
        if (history.isNotEmpty()) {
            val cards = HBox(10.0)
            for (e in history) {
                cards.children.add(
                    Ui.entryCard(e, progressSub(e)) {
                        Bg.run { openHistoryEntry(e) }
                    },
                )
            }
            val scroller = ScrollPane(cards).apply {
                styleClass.add("rail-scroll")
                hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
                vbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
                isFitToHeight = true
            }
            rowsBox.children.add(
                VBox(6.0, Ui.title("Continue Watching"), scroller),
            )
        }
        val kept = filtered(rows).take(14)
        if (kept.isEmpty()) {
            rowsBox.children.add(centered(Ui.muted("Nothing here yet — providers returned no rows.")))
            return
        }
        for (row in kept) {
            rowsBox.children.add(
                Ui.rail(row.title, row.providerName, row.items, { AppShell.goDetail(it) }),
            )
        }
    }

    private fun progressSub(e: LibraryStore.Entry): String =
        if (e.season > 0 || e.episode > 0) "S${e.season} E${e.episode}" else "Continue"

    private suspend fun openHistoryEntry(e: LibraryStore.Entry) {
        val providers = Engines.current()
        val p = providers.firstOrNull { it.ref.id == e.providerId } ?: return
        try {
            val fake = MediaItem(e.providerId, e.id, e.title, posterUrl = e.posterUrl, backdropUrl = e.backdropUrl)
            val full = p.detail(fake)
            Fx.run { AppShell.goDetail(full) }
        } catch (_: Throwable) {
        }
    }

    private fun hero(item: MediaItem): Node {
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
        val overview = Label(item.overview?.take(240) ?: "").apply {
            styleClass.add("hero-overview")
            isWrapText = true
            maxWidth = 560.0
        }
        val watch = Ui.primaryBtn("▶  Watch Now") { AppShell.goDetail(item) }
        val info = VBox(8.0, title, meta, overview, watch).apply {
            styleClass.add("hero-info")
            alignment = Pos.BOTTOM_LEFT
            padding = Insets(0.0, 0.0, 26.0, 28.0)
            maxWidth = 640.0
        }
        return StackPane(bg, info).apply {
            styleClass.add("hero")
            StackPane.setAlignment(info, Pos.BOTTOM_LEFT)
        }
    }
}
