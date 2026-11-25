package codegeasse.hikari.desktop.ui

import javafx.scene.Scene

/** App theme: a single stylesheet plus shared color constants. */
object Theme {
    const val BG = "#0b0e17"
    const val PANEL = "#141927"
    const val CARD = "#1a2133"
    const val TEXT = "#eef1f8"
    const val MUTED = "#9aa3b8"
    const val ACCENT = "#7c5cff"
    const val ACCENT2 = "#b14bff"

    fun style(scene: Scene): Scene {
        val css = Theme::class.java.getResource("/theme.css")
        if (css != null) scene.stylesheets.add(css.toExternalForm())
        scene.fill = javafx.scene.paint.Color.web(BG)
        return scene
    }
}
