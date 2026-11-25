package codegeasse.hikari.desktop.ui

import codegeasse.hikari.desktop.Build
import codegeasse.hikari.desktop.core.Log
import codegeasse.hikari.desktop.core.Stores
import codegeasse.hikari.desktop.player.Player
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox

/** Settings: player path, cache, diagnostics. */
object Settings {
    fun build(): Node {
        val box = VBox(14.0).apply { padding = Insets(14.0, 16.0, 24.0, 16.0) }

        val mpvField = TextField(Stores.getSetting("mpvPath")).apply {
            promptText = "Custom mpv path (optional)"
            styleClass.add("search")
            prefColumnCount = 44
        }
        val detected = Player.mpvExe()?.absolutePath ?: "not found"
        box.children.add(
            VBox(
                6.0,
                Ui.title("Player"),
                Ui.muted("External mpv plays every stream. Bundled at install time; override only if needed."),
                HBox(8.0, mpvField, Ui.primaryBtn("Save") {
                    Stores.setSetting("mpvPath", mpvField.text.trim())
                    mpvField.text = Stores.getSetting("mpvPath")
                }).apply { alignment = Pos.CENTER_LEFT },
                Ui.muted("Detected: $detected"),
            ),
        )

        box.children.add(
            VBox(
                6.0,
                Ui.title("Cache"),
                HBox(
                    8.0,
                    Ui.ghostBtn("Clear dex cache") {
                        runCatching { Stores.dexCacheDir().deleteRecursively() }
                    },
                    Ui.ghostBtn("Clear image memory") { },
                ).apply { alignment = Pos.CENTER_LEFT },
                Ui.muted("Dex cache rebuilds automatically on next provider load (~/.hikari2/dex-cache)."),
            ),
        )

        val logView = javafx.scene.control.TextArea(Log.recent(200)).apply {
            isEditable = false
            prefRowCount = 14
            styleClass.add("log")
        }
        box.children.add(
            VBox(
                6.0,
                HBox(8.0, Ui.title("Diagnostics"), Ui.spacer(), Ui.ghostBtn("Refresh") { logView.text = Log.recent(400) }).apply {
                    alignment = Pos.CENTER_LEFT
                },
                logView,
                Ui.muted("HIKARI v${Build.VERSION} · ${Build.DATE} · ${Build.COMMIT}"),
            ),
        )

        return ScrollPane(box).apply {
            styleClass.add("page-scroll")
            isFitToWidth = true
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
        }
    }
}
