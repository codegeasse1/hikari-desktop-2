package codegeasse.hikari.desktop.ui

import codegeasse.hikari.desktop.core.Fx
import codegeasse.hikari.desktop.core.LibraryStore
import codegeasse.hikari.desktop.engine.Engines
import codegeasse.hikari.desktop.model.MediaItem
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.ScrollPane
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox

/** Favorites + watch history. */
object Library {
    fun build(): Node {
        val box = VBox(16.0).apply { padding = Insets(14.0, 16.0, 24.0, 16.0) }
        val scroll = ScrollPane(box).apply {
            styleClass.add("page-scroll")
            isFitToWidth = true
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
        }
        Bg.run {
            val favs = LibraryStore.favorites()
            val hist = LibraryStore.history(60)
            Fx.run { render(box, favs, hist) }
        }
        return scroll
    }

    private fun render(box: VBox, favs: List<LibraryStore.Entry>, hist: List<LibraryStore.Entry>) {
        box.children.clear()
        box.children.add(
            HBox(10.0, Ui.title("Favorites"), Ui.spacer(), Ui.muted("${favs.size}")),
        )
        if (favs.isEmpty()) box.children.add(Ui.muted("No favorites yet — open a title and hit ♡ Favorite."))
        else {
            val grid = VBox(10.0)
            for (e in favs) {
                grid.children.add(favCard(e) { render(box, LibraryStore.favorites(), LibraryStore.history(60)) })
            }
            box.children.add(grid)
        }

        val histRow = HBox(10.0, Ui.title("Continue Watching"), Ui.spacer())
        if (hist.isNotEmpty()) {
            histRow.children.add(Ui.ghostBtn("Clear History") {
                LibraryStore.clearHistory()
                box.children.clear()
                render(box, LibraryStore.favorites(), emptyList())
            })
        }
        box.children.add(histRow)
        if (hist.isEmpty()) box.children.add(Ui.muted("Nothing watched yet."))
        else {
            val cards = HBox(10.0)
            for (e in hist) {
                cards.children.add(
                    Ui.continueCard(
                        e,
                        if (e.season > 0 || e.episode > 0) "S${e.season} E${e.episode}" else "Continue",
                        { open(it.toMedia()) },
                        { LibraryStore.removeHistory(it.providerId, it.id); render(box, LibraryStore.favorites(), LibraryStore.history(60)) },
                    ),
                )
            }
            box.children.add(
                ScrollPane(cards).apply {
                    styleClass.add("rail-scroll")
                    hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
                    vbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
                    isFitToHeight = true
                },
            )
        }
    }

    private fun LibraryStore.Entry.toMedia(): MediaItem =
        MediaItem(providerId, id, title, posterUrl = posterUrl, backdropUrl = backdropUrl)

    private fun favCard(e: LibraryStore.Entry, onChanged: () -> Unit): Node {
        val card = Ui.posterCard(e.toMedia(), onClick = { open(it) })
        val row = HBox(6.0, Ui.muted(e.title.take(24)), Ui.spacer(), Ui.ghostBtn("✕") {
            LibraryStore.toggleFavorite(e)
            onChanged()
        }).apply {
            alignment = Pos.CENTER_LEFT
            maxWidth = 150.0
        }
        return VBox(6.0, card, row).apply { maxWidth = 150.0 }
    }

    private fun open(item: MediaItem) {
        Bg.run {
            val p = Engines.current().firstOrNull { it.ref.id == item.providerId } ?: return@run
            val full = try {
                p.detail(item)
            } catch (_: Throwable) {
                item
            }
            Fx.run { AppShell.goDetail(full) }
        }
    }
}
