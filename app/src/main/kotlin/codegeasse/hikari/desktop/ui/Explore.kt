package codegeasse.hikari.desktop.ui

import codegeasse.hikari.desktop.core.Fx
import codegeasse.hikari.desktop.engine.Engines
import codegeasse.hikari.desktop.model.EngineKind
import codegeasse.hikari.desktop.model.MediaItem
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.CheckBox
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox

/** Search + browse every enabled provider, with per-provider selection. */
object Explore {
    fun build(initial: String): Node {
        val field = TextField(initial).apply {
            promptText = "Search movies, series, anime…"
            styleClass.add("search-big")
        }
        val chips = HBox(8.0).apply { alignment = Pos.CENTER_LEFT }
        val provBox = HBox(8.0).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(2.0, 0.0, 2.0, 0.0)
        }
        val results = VBox(10.0).apply { padding = Insets(4.0, 16.0, 24.0, 16.0) }
        val head = VBox(10.0, field, chips, provBox).apply { padding = Insets(14.0, 16.0, 6.0, 16.0) }
        val scroll = ScrollPane(results).apply {
            styleClass.add("page-scroll")
            isFitToWidth = true
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
        }
        val root = VBox(head, scroll).apply {
            VBox.setVgrow(scroll, javafx.scene.layout.Priority.ALWAYS)
        }

        var kinds: Set<EngineKind>? = null
        val picked = mutableSetOf<String>() // provider ids; empty = all enabled

        fun providers(): List<String> = Engines.current()
            .filter { it.ref.enabled && (kinds == null || AppShell.engineOfProvider(it.ref.id) in kinds!!) }
            .map { it.ref.id }
            .sorted()

        fun renderProvBox() {
            provBox.children.clear()
            val ids = providers()
            if (ids.size < 2) return
            provBox.children.add(Ui.muted("Providers:"))
            for (id in ids) {
                val name = Engines.current().firstOrNull { it.ref.id == id }?.ref?.name ?: id
                val cb = CheckBox(name).apply {
                    isSelected = id in picked || picked.isEmpty()
                    styleClass.add("check-box")
                    setOnAction {
                        if (isSelected) picked.add(id) else picked.remove(id)
                        if (picked.size == ids.size) picked.clear() // all = no filter
                        doSearch(field.text, results, kinds, picked.toSet())
                    }
                }
                provBox.children.add(cb)
            }
        }

        fun renderChips() {
            chips.children.clear()
            val all = listOf(null to "All providers") + EngineKind.entries.map { it to it.label }
            for ((k, label) in all) {
                chips.children.add(Ui.chip(label, kinds == k?.let { setOf(it) }) {
                    kinds = k?.let { setOf(it) }
                    picked.clear()
                    renderChips()
                    renderProvBox()
                    doSearch(field.text, results, kinds, picked.toSet())
                })
            }
        }

        renderChips()
        renderProvBox()
        field.setOnAction { doSearch(field.text, results, kinds, picked.toSet()) }
        if (initial.isNotBlank()) doSearch(initial, results, null, emptySet())
        else {
            results.children.add(
                Ui.muted(
                    "Type a title and press Enter. Searches every enabled provider in parallel. " +
                        "Pick providers above to narrow it down, or manage them in Extensions.",
                ),
            )
            if (Engines.current().isEmpty()) {
                results.children.add(
                    Ui.emptyState(
                        "No providers loaded",
                        "Install an extension or add a Stremio addon first.",
                        "Open Extensions",
                    ) { AppShell.goExtensions() },
                )
            }
        }
        return root
    }

    private fun doSearch(query: String, results: VBox, kinds: Set<EngineKind>?, picked: Set<String>) {
        val q = query.trim()
        if (q.isEmpty()) return
        results.children.setAll(centered(Ui.spinner()))
        Bg.run {
            val found = Engines.searchAll(q)
                .filter { m -> kinds == null || AppShell.engineOfProvider(m.providerId) in kinds }
                .filter { m -> picked.isEmpty() || m.providerId in picked }
            Fx.run { renderResults(results, q, found) }
        }
    }

    private fun centered(n: Node) = HBox(n).apply { alignment = Pos.CENTER }

    private fun renderResults(results: VBox, q: String, items: List<MediaItem>) {
        results.children.clear()
        results.children.add(Ui.title("“$q” — ${items.size} result${if (items.size == 1) "" else "s"}"))
        if (items.isEmpty()) {
            results.children.add(Ui.muted("No results. Try another spelling, or install more extensions."))
            return
        }
        results.children.add(Ui.section(items.take(120), 6) { AppShell.goDetail(it) })
    }
}
