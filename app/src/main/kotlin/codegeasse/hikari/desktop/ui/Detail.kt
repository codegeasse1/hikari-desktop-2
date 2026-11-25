package codegeasse.hikari.desktop.ui

import codegeasse.hikari.desktop.core.Fx
import codegeasse.hikari.desktop.core.LibraryStore
import codegeasse.hikari.desktop.engine.Engines
import codegeasse.hikari.desktop.model.EpisodeItem
import codegeasse.hikari.desktop.model.MediaItem
import codegeasse.hikari.desktop.model.StreamItem
import codegeasse.hikari.desktop.player.Player
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.image.ImageView
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox

/** Detail page: backdrop, meta, episodes, streams → mpv. */
object Detail {
    fun build(item: MediaItem): Node {
        val body = VBox(12.0).apply { padding = Insets(0.0, 0.0, 30.0, 0.0) }
        val scroll = ScrollPane(body).apply {
            styleClass.add("page-scroll")
            isFitToWidth = true
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
        }
        body.children.add(backHeader(item))
        val status = Ui.muted("Loading…").apply { padding = Insets(0.0, 20.0, 0.0, 20.0) }
        body.children.add(status)
        Bg.run { fill(body, status, item) }
        return scroll
    }

    private fun backHeader(item: MediaItem): Node {
        val bg = ImageView().apply {
            fitWidth = 1280.0
            fitHeight = 300.0
            isPreserveRatio = false
            isSmooth = true
        }
        Img.load(item.backdropUrl ?: item.posterUrl, 1280.0, bg)
        val back = Ui.ghostBtn("← Back") { AppShell.goBack() }
        val title = Label(item.title).apply { styleClass.add("detail-title") }
        val meta = Ui.muted(
            listOfNotNull(
                item.year?.toString(),
                item.kind.name.lowercase().replaceFirstChar { it.uppercase() },
                item.genres.take(4).joinToString(" • ").takeIf { it.isNotBlank() },
            ).joinToString("  •  "),
        )
        val info = VBox(8.0, back, title, meta).apply {
            alignment = Pos.BOTTOM_LEFT
            padding = Insets(16.0, 20.0, 20.0, 20.0)
        }
        return StackPane(bg, info).apply {
            styleClass.add("hero")
            StackPane.setAlignment(info, Pos.BOTTOM_LEFT)
            prefHeight = 300.0
        }
    }

    private suspend fun fill(body: VBox, status: Label, item: MediaItem) {
        val provider = Engines.current().firstOrNull { it.ref.id == item.providerId }
        if (provider == null) {
            Fx.run { status.text = "Provider not available (extension removed?)." }
            return
        }
        val full = try {
            provider.detail(item)
        } catch (t: Throwable) {
            Fx.run { status.text = "Detail failed: ${t.message}" }
            return
        }
        val episodes = try {
            provider.episodes(full)
        } catch (_: Throwable) {
            emptyList()
        }
        Fx.run {
            body.children.remove(status)
            body.children.add(overviewRow(full, provider.ref.name))
            if (episodes.isNotEmpty()) body.children.add(episodeBlock(full, episodes))
            else body.children.add(movieStreamsBlock(full))
        }
    }

    private fun overviewRow(full: MediaItem, providerName: String): Node {
        val overview = Label(full.overview?.take(600) ?: "No overview available.").apply {
            styleClass.add("overview")
            isWrapText = true
        }
        val favState = LibraryStore.isFavorite(full.providerId, full.id)
        val fav = Ui.ghostBtn(if (favState) "♥ Favorited" else "♡ Favorite") {}
        fav.setOnAction {
            val e = LibraryStore.Entry(full.providerId, full.id, full.title, full.posterUrl, full.backdropUrl)
            val now = LibraryStore.toggleFavorite(e)
            fav.text = if (now) "♥ Favorited" else "♡ Favorite"
        }
        val row = HBox(10.0, Ui.muted("via $providerName"), Ui.spacer(), fav).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(0.0, 20.0, 0.0, 20.0)
        }
        return VBox(8.0, row, overview.apply { padding = Insets(0.0, 20.0, 0.0, 20.0) })
    }

    private fun episodeBlock(full: MediaItem, episodes: List<EpisodeItem>): Node {
        val seasons = episodes.map { it.season }.distinct().sorted()
        val combo = ComboBox<Int>().apply {
            items.setAll(seasons)
            selectionModel.selectFirst()
            styleClass.add("combo")
        }
        val grid = FlowPane(8.0, 8.0).apply { padding = Insets(0.0, 20.0, 0.0, 20.0) }
        val streamsBox = VBox(6.0).apply { padding = Insets(0.0, 20.0, 0.0, 20.0) }
        fun render(season: Int) {
            grid.children.clear()
            streamsBox.children.clear()
            val eps = episodes.filter { it.season == season }.sortedBy { it.number }
            for (ep in eps) {
                val b = Ui.ghostBtn("E${ep.number}${ep.name?.let { " · $it" }?.take(28) ?: ""}") {
                    loadStreams(full, ep, streamsBox)
                }
                b.styleClass.add("ep-btn")
                grid.children.add(b)
            }
            eps.firstOrNull()?.let { loadStreams(full, it, streamsBox) }
        }
        combo.setOnAction { combo.selectionModel.selectedItem?.let { render(it) } }
        val head = HBox(10.0, Ui.title("Season"), combo, Ui.spacer()).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(0.0, 20.0, 0.0, 20.0)
        }
        render(seasons.firstOrNull() ?: 1)
        return VBox(10.0, head, grid, streamsBox)
    }

    private fun movieStreamsBlock(full: MediaItem): Node {
        val box = VBox(6.0, Ui.muted("Loading streams…")).apply {
            padding = Insets(0.0, 20.0, 0.0, 20.0)
        }
        loadStreams(full, null, box)
        return box
    }

    private fun loadStreams(full: MediaItem, episode: EpisodeItem?, box: VBox) {
        box.children.setAll(Ui.muted("Loading streams…"))
        Bg.run {
            val provider = Engines.current().firstOrNull { it.ref.id == full.providerId } ?: return@run
            val streams = try {
                provider.streams(full, episode)
            } catch (_: Throwable) {
                emptyList()
            }
            Fx.run { renderStreams(box, full, episode, streams) }
        }
    }

    private fun renderStreams(box: VBox, full: MediaItem, episode: EpisodeItem?, streams: List<StreamItem>) {
        box.children.clear()
        val head = episode?.let { "Streams — S${it.season} E${it.number}" } ?: "Streams"
        box.children.add(Ui.title(head))
        if (streams.isEmpty()) {
            box.children.add(Ui.muted("No streams found for this ${if (episode == null) "title" else "episode"}."))
            return
        }
        for (s in streams.take(40)) {
            val play = Ui.primaryBtn("▶ Play") {
                LibraryStore.touchHistory(
                    LibraryStore.Entry(
                        full.providerId, full.id, full.title, full.posterUrl, full.backdropUrl,
                        season = episode?.season ?: 0, episode = episode?.number ?: 0,
                        episodeId = episode?.id,
                    ),
                )
                val ok = Player.play(
                    "${full.title}${episode?.let { " S${it.season}E${it.number}" } ?: ""}",
                    s,
                )
                if (!ok) AppShell.goSettings()
            }
            val row = HBox(10.0, play, Label(s.name.take(80)).apply { styleClass.add("stream-name") }).apply {
                alignment = Pos.CENTER_LEFT
            }
            box.children.add(row)
        }
    }
}
