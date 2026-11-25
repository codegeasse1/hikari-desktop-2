package codegeasse.hikari.desktop.ui

import codegeasse.hikari.desktop.core.Fx
import codegeasse.hikari.desktop.core.LibraryStore
import codegeasse.hikari.desktop.core.Net
import codegeasse.hikari.desktop.engine.Provider
import codegeasse.hikari.desktop.model.MediaItem
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.ScrollPane
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.shape.Rectangle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

/** Background work shared by all screens. The single rule: never block FX. */
object Bg {
    val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val imgPool = Executors.newFixedThreadPool(6) { r ->
        Thread(r, "img").apply { isDaemon = true }
    }

    fun run(block: suspend () -> Unit) = io.launch { block() }
    fun img(block: () -> Unit) = imgPool.submit(block)
}

/** Bounded in-memory image cache (1x resolution, ~200 posters). */
object Img {
    private const val CAP = 200
    private val cache = object : LinkedHashMap<String, Image>(CAP, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Image>): Boolean =
            size > CAP
    }

    fun get(url: String?): Image? = synchronized(cache) { url?.let { cache[it] } }

    fun load(url: String?, targetW: Double, view: ImageView) {
        if (url.isNullOrBlank()) return
        synchronized(cache) { cache[url] }?.let {
            view.image = it
            return
        }
        Bg.img {
            try {
                val bytes = Net.client.let {
                    val req = okhttp3.Request.Builder().url(url).header("User-Agent", Net.UA).build()
                    it.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) return@img
                        r.body?.bytes() ?: return@img
                    }
                }
                if (bytes.size > 4 * 1024 * 1024) return@img
                val img = Image(ByteArrayInputStream(bytes), targetW, 0.0, true, true)
                if (img.isError) return@img
                synchronized(cache) { cache[url] = img }
                Fx.run { view.image = img }
            } catch (_: Throwable) {
            }
        }
    }
}

/** Shared widgets: cards, rails, buttons, section headers. */
object Ui {
    fun spacer(): Region = Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }

    fun title(text: String): Label = Label(text).apply { styleClass.add("rail-title") }

    fun muted(text: String): Label = Label(text).apply { styleClass.add("muted") }

    fun primaryBtn(text: String, onClick: () -> Unit): Button =
        Button(text).apply {
            styleClass.add("btn-primary")
            setOnAction { onClick() }
        }

    fun ghostBtn(text: String, onClick: () -> Unit): Button =
        Button(text).apply {
            styleClass.add("btn-ghost")
            setOnAction { onClick() }
        }

    fun chip(text: String, selected: Boolean, onClick: () -> Unit): Button =
        Button(text).apply {
            styleClass.add(if (selected) "chip-on" else "chip")
            setOnAction { onClick() }
        }

    fun spinner(): ProgressIndicator = ProgressIndicator().apply {
        styleClass.add("spinner")
        prefWidth = 36.0
        prefHeight = 36.0
    }

    /** Poster card with a static (non-shimmer) skeleton behind the image. */
    fun posterCard(
        item: MediaItem,
        w: Double = 150.0,
        h: Double = 214.0,
        onClick: (MediaItem) -> Unit,
    ): Node {
        val view = ImageView().apply {
            fitWidth = w
            fitHeight = h
            isPreserveRatio = false
            isSmooth = true
        }
        val skel = Rectangle(w, h).apply { styleClass.add("skel") }
        val stack = StackPane(skel, view).apply {
            styleClass.add("card")
            maxWidth = w
        }
        val title = Label(item.title).apply {
            styleClass.add("card-title")
            maxWidth = w
            isWrapText = true
        }
        val box = VBox(6.0, stack, title).apply {
            maxWidth = w
            styleClass.add("card-box")
        }
        box.setOnMouseClicked { onClick(item) }
        Img.get(item.posterUrl)?.let { view.image = it } ?: Img.load(item.posterUrl, w, view)
        return box
    }

    /** Horizontal rail with arrow buttons; [rowId] keeps scroll state stable. */
    fun rail(
        title: String,
        provider: String,
        items: List<MediaItem>,
        onItem: (MediaItem) -> Unit,
        onViewAll: (() -> Unit)? = null,
    ): Node {
        val cards = HBox(10.0).apply {
            padding = Insets(2.0, 4.0, 8.0, 4.0)
        }
        for (m in items) cards.children.add(posterCard(m, onClick = onItem))
        val scroller = ScrollPane(cards).apply {
            styleClass.add("rail-scroll")
            isFitToHeight = true
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            vbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            prefViewportHeight = 250.0
        }
        val head = HBox(10.0).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(0.0, 4.0, 0.0, 4.0)
        }
        head.children.add(title(title))
        head.children.add(muted(provider))
        head.children.add(spacer())
        if (onViewAll != null) {
            head.children.add(ghostBtn("View All", onViewAll))
        }
        val left = ghostBtn("‹") { scroller.hvalue = (scroller.hvalue - 0.25).coerceAtLeast(0.0) }
        val right = ghostBtn("›") { scroller.hvalue = (scroller.hvalue + 0.25).coerceAtMost(1.0) }
        left.styleClass.add("arrow")
        right.styleClass.add("arrow")
        return VBox(6.0, head, HBox(8.0, left, scroller, right).apply {
            HBox.setHgrow(scroller, Priority.ALWAYS)
        })
    }

    fun section(items: List<MediaItem>, cols: Int, onItem: (MediaItem) -> Unit): Node {
        val flow = javafx.scene.layout.TilePane(12.0, 14.0).apply {
            prefColumns = cols
            styleClass.add("grid")
        }
        for (m in items) flow.children.add(posterCard(m, onClick = onItem))
        return flow
    }

    fun entryCard(e: LibraryStore.Entry, sub: String, onClick: (LibraryStore.Entry) -> Unit): Node {
        val fake = MediaItem(e.providerId, e.id, e.title, posterUrl = e.posterUrl)
        val card = posterCard(fake, onClick = {
            onClick(e)
        })
        return VBox(6.0, card, muted(sub).apply { maxWidth = 150.0 }).apply { maxWidth = 150.0 }
    }

    fun providerRef(provider: Provider): String = provider.ref.name

    // -- sidebar + state widgets (reference-grade chrome) ----------------------

    /** Sidebar navigation row: icon + label, highlighted when selected. */
    fun navItem(icon: String, label: String, selected: Boolean, onClick: () -> Unit): Button =
        Button("$icon   $label").apply {
            styleClass.add(if (selected) "nav-item-on" else "nav-item")
            maxWidth = Double.MAX_VALUE
            alignment = Pos.CENTER_LEFT
            setOnAction { onClick() }
        }

    /** Loading placeholder rail: skeleton cards under a title. */
    fun skeletonRow(title: String, cards: Int = 6): Node {
        val row = HBox(10.0).apply { padding = Insets(2.0, 4.0, 8.0, 4.0) }
        repeat(cards) {
            row.children.add(
                VBox(6.0).apply {
                    children.add(Rectangle(150.0, 214.0).apply { styleClass.add("skel") })
                    children.add(Rectangle(110.0, 12.0).apply { styleClass.add("skel") })
                },
            )
        }
        val scroller = ScrollPane(row).apply {
            styleClass.add("rail-scroll")
            isFitToHeight = true
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            vbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            prefViewportHeight = 250.0
        }
        return VBox(6.0, title(title), scroller)
    }

    /** Row-level failure card with retry (a dead provider never blocks the page). */
    fun errorCard(who: String, message: String, onRetry: () -> Unit): Node =
        HBox(10.0, muted("$who: ${message.take(160)}"), spacer(), ghostBtn("↻ Retry") { onRetry() }).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(10.0, 14.0, 10.0, 14.0)
            styleClass.add("error-card")
        }

    /** Empty-page hero with a call to action (never a bare spinner). */
    fun emptyState(title: String, body: String, actionLabel: String, onAction: () -> Unit): Node =
        VBox(12.0).apply {
            alignment = Pos.CENTER
            padding = Insets(60.0, 20.0, 60.0, 20.0)
            children.add(Label(title).apply { styleClass.add("empty-title") })
            children.add(muted(body).apply { isWrapText = true })
            children.add(primaryBtn(actionLabel) { onAction() })
        }

    /** Small status pill (update available, counts, quality). */
    fun badge(text: String): Label = Label(text).apply { styleClass.add("badge") }

    /** Inline install/update progress row. */
    fun progressRow(label: String, frac: Double): Node {
        val bar = javafx.scene.control.ProgressBar().apply {
            progress = frac.coerceIn(0.0, 1.0)
            prefWidth = 220.0
            styleClass.add("job-bar")
        }
        return HBox(10.0, Label(label).apply { styleClass.add("job-label") }, bar).apply {
            alignment = Pos.CENTER_LEFT
        }
    }

    /** Section header with an optional trailing action. */
    fun sectionHead(title: String, sub: String = "", action: Node? = null): Node {
        val head = HBox(10.0, title(title)).apply { alignment = Pos.CENTER_LEFT }
        if (sub.isNotBlank()) head.children.add(muted(sub))
        head.children.add(spacer())
        if (action != null) head.children.add(action)
        return head
    }

    /** Continue-watching card with a watched-progress bar and a remove button. */
    fun continueCard(
        e: LibraryStore.Entry,
        sub: String,
        onOpen: (LibraryStore.Entry) -> Unit,
        onRemove: (LibraryStore.Entry) -> Unit,
    ): Node {
        val fake = MediaItem(e.providerId, e.id, e.title, posterUrl = e.posterUrl, backdropUrl = e.backdropUrl)
        val card = posterCard(fake, onClick = { onOpen(e) })
        val under = mutableListOf<Node>()
        if (e.progress >= 0.0) {
            under.add(
                javafx.scene.control.ProgressBar().apply {
                    progress = e.progress.coerceIn(0.0, 1.0)
                    prefWidth = 150.0
                    maxWidth = 150.0
                    styleClass.add("watch-bar")
                },
            )
        }
        val row = HBox(6.0, muted(sub).apply { maxWidth = 120.0 }, spacer(), ghostBtn("✕") { onRemove(e) }).apply {
            alignment = Pos.CENTER_LEFT
            maxWidth = 150.0
        }
        return VBox(6.0).apply {
            maxWidth = 150.0
            children.add(card)
            children.addAll(under)
            children.add(row)
        }
    }

    /** Small modal dialog owned by [owner]. Returns the stage so callers can close it. */
    fun dialog(owner: javafx.stage.Stage, title: String, body: Node, width: Double = 460.0): javafx.stage.Stage {
        val stage = javafx.stage.Stage().apply {
            initOwner(owner)
            initModality(javafx.stage.Modality.APPLICATION_MODAL)
            this.title = title
        }
        val root = VBox(12.0, Label(title).apply { styleClass.add("dialog-title") }, body).apply {
            padding = Insets(16.0, 18.0, 18.0, 18.0)
            styleClass.add("dialog")
        }
        stage.scene = javafx.scene.Scene(ScrollPane(root).apply {
            styleClass.add("page-scroll")
            isFitToWidth = true
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            prefViewportWidth = width
        })
        return stage
    }
}
