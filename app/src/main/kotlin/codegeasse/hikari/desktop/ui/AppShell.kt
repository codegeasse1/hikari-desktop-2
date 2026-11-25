package codegeasse.hikari.desktop.ui

import codegeasse.hikari.desktop.Build
import codegeasse.hikari.desktop.core.Fx
import codegeasse.hikari.desktop.cs.CsEngine
import codegeasse.hikari.desktop.engine.Engines
import codegeasse.hikari.desktop.hiki.Hiki
import codegeasse.hikari.desktop.install.Installer
import codegeasse.hikari.desktop.model.EngineKind
import codegeasse.hikari.desktop.model.JobState
import codegeasse.hikari.desktop.model.MediaItem
import codegeasse.hikari.desktop.nuvio.NuvioEngine
import codegeasse.hikari.desktop.stremio.StremioEngine
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.stage.Stage

/** App frame: custom title bar, icon rail, top bar, content area. */
object AppShell {
    private lateinit var stage: Stage
    private lateinit var content: StackPane
    private lateinit var crumbs: HBox
    private val backStack = ArrayDeque<Node>()
    var engineFilter: Set<EngineKind>? = null
    private var enginesReady = false

    fun engineOfProvider(providerId: String): EngineKind? = when {
        providerId.startsWith("cs|") -> EngineKind.CS
        providerId.startsWith("hiki|") -> EngineKind.HIKI
        providerId.startsWith("nuvio|") -> EngineKind.NUVIO
        providerId.startsWith("stremio|") -> EngineKind.STREMIO
        else -> null
    }

    fun create(stage: Stage): javafx.scene.Parent {
        this.stage = stage
        content = StackPane().apply { styleClass.add("content") }

        val root = BorderPane()
        root.styleClass.add("root")
        root.top = titleBar()
        root.left = sideBar()
        val center = VBox(0.0, topBar(), content).apply { VBox.setVgrow(content, Priority.ALWAYS) }
        root.center = center
        root.bottom = statusBar()

        ensureEngines()
        return root
    }

    private fun ensureEngines() {
        if (enginesReady) return
        enginesReady = true
        Engines.register(CsEngine)
        Engines.register(Hiki)
        Engines.register(NuvioEngine)
        Engines.register(StremioEngine)
        Bg.run { Engines.reload() }
    }

    // -- chrome -------------------------------------------------------------

    private var dragX = 0.0
    private var dragY = 0.0

    private fun titleBar(): Node {
        val label = Label("HIKARI").apply { styleClass.add("app-title") }
        val bar = HBox(8.0, label, Ui.spacer()).apply {
            styleClass.add("titlebar")
            alignment = Pos.CENTER_LEFT
            padding = Insets(6.0, 8.0, 6.0, 14.0)
        }
        for ((text, fn) in listOf("—" to { stage.isIconified = true }, "✕" to { stage.close() })) {
            val b = Button(text).apply {
                styleClass.add("win-btn")
                setOnAction { fn() }
            }
            bar.children.add(b)
        }
        bar.setOnMousePressed { dragX = it.screenX - stage.x; dragY = it.screenY - stage.y }
        bar.setOnMouseDragged { stage.x = it.screenX - dragX; stage.y = it.screenY - dragY }
        return bar
    }

    private var currentNav = "Home"
    private var jobsPill: Label? = null

    private fun sideBar(): Node {
        val logo = Label("◈ HIKARI").apply { styleClass.add("side-logo") }
        val nav = VBox(4.0).apply {
            padding = Insets(6.0, 10.0, 6.0, 10.0)
        }
        fun rebuild() {
            nav.children.setAll(
                Ui.navItem("⌂", "Home", currentNav == "Home") { goHome() },
                Ui.navItem("◎", "Explore", currentNav == "Explore") { goExplore() },
                Ui.navItem("▤", "Library", currentNav == "Library") { goLibrary() },
                Ui.navItem("◫", "Extensions", currentNav == "Extensions") { goExtensions() },
                Ui.navItem("⚙", "Settings", currentNav == "Settings") { goSettings() },
            )
        }
        rebuild()
        navRef = ::rebuild
        jobsPill = Label("").apply {
            styleClass.add("jobs-pill")
            isVisible = false
            isManaged = false
        }
        val bottom = VBox(6.0, jobsPill, Ui.muted("")).apply {
            padding = Insets(0.0, 14.0, 12.0, 14.0)
        }
        Installer.onChange {
            Fx.run {
                val running = Installer.snapshot().count { it.state == JobState.RUNNING || it.state == JobState.QUEUED }
                jobsPill?.let {
                    it.isVisible = running > 0
                    it.isManaged = running > 0
                    it.text = if (running > 0) "⬇ $running install${if (running == 1) "" else "s"}" else ""
                }
            }
        }
        return VBox(0.0, logo.apply { padding = Insets(14.0, 14.0, 8.0, 14.0) }, nav, grow(), bottom).apply {
            styleClass.add("sidebar")
            prefWidth = 208.0
            minWidth = 208.0
            maxWidth = 208.0
        }
    }

    private var navRef: (() -> Unit)? = null

    private fun grow(): Node = javafx.scene.layout.Region().apply {
        VBox.setVgrow(this, Priority.ALWAYS)
    }

    private fun selectNav(name: String) {
        currentNav = name
        Fx.run { navRef?.invoke() }
    }

    private fun topBar(): Node {
        crumbs = HBox(6.0).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(8.0, 14.0, 8.0, 14.0)
        }
        val search = TextField().apply {
            promptText = "Search titles…"
            styleClass.add("search")
            prefColumnCount = 22
            setOnAction { goSearch(text) }
        }
        val bar = HBox(10.0, crumbs, Ui.spacer(), search).apply {
            styleClass.add("topbar")
            alignment = Pos.CENTER_LEFT
        }
        refreshCrumbs("Home")
        return bar
    }

    private fun statusBar(): Node {
        val v = Label("HIKARI v${Build.VERSION}").apply { styleClass.add("version") }
        return HBox(v).apply {
            styleClass.add("statusbar")
            padding = Insets(2.0, 10.0, 4.0, 10.0)
        }
    }

    fun refreshCrumbs(vararg names: String) {
        if (!this::crumbs.isInitialized) return
        Fx.run {
            crumbs.children.setAll(names.map { Label(it).apply { styleClass.add("crumb") } })
        }
    }

    // -- navigation ----------------------------------------------------------

    private fun show(node: Node, pushCurrent: Boolean = true) {
        Fx.run {
            if (pushCurrent && content.children.isNotEmpty()) {
                backStack.addLast(content.children.first())
                if (backStack.size > 25) backStack.removeFirst()
            }
            content.children.setAll(node)
        }
    }

    fun goBack() {
        Fx.run {
            val prev = backStack.removeLastOrNull() ?: return@run
            content.children.setAll(prev)
        }
    }

    fun goHome() {
        backStack.clear()
        selectNav("Home")
        refreshCrumbs("Home")
        val home = Home.build()
        Fx.run { content.children.setAll(home) }
    }

    fun goDetail(item: MediaItem) {
        refreshCrumbs("Home", item.title.take(40))
        show(Detail.build(item))
    }

    fun goSearch(initial: String = "") {
        backStack.clear()
        selectNav("Explore")
        refreshCrumbs("Search")
        show(Explore.build(initial), pushCurrent = false)
    }

    fun goExplore() {
        backStack.clear()
        selectNav("Explore")
        refreshCrumbs("Explore")
        show(Explore.build(""), pushCurrent = false)
    }

    fun goExtensions() {
        backStack.clear()
        selectNav("Extensions")
        refreshCrumbs("Extensions")
        show(Extensions.build(), pushCurrent = false)
    }

    fun goLibrary() {
        backStack.clear()
        selectNav("Library")
        refreshCrumbs("Library")
        show(Library.build(), pushCurrent = false)
    }

    fun goSettings() {
        backStack.clear()
        selectNav("Settings")
        refreshCrumbs("Settings")
        show(Settings.build(), pushCurrent = false)
    }
}
