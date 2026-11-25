package codegeasse.hikari.desktop

import codegeasse.hikari.desktop.ui.AppShell
import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.image.Image
import javafx.stage.Stage
import javafx.stage.StageStyle

fun main() {
    Application.launch(HikariApp::class.java)
}

class HikariApp : Application() {
    override fun start(stage: Stage) {
        codegeasse.hikari.desktop.core.Fx.onStart()
        stage.title = "HIKARI"
        stage.initStyle(StageStyle.UNDECORATED)
        runCatching {
            javaClass.getResourceAsStream("/hikari.png")?.use { stage.icons.add(Image(it)) }
        }
        val root = AppShell.create(stage)
        val bounds = javafx.stage.Screen.getPrimary().visualBounds
        val w = minOf(1280.0, bounds.width - 60.0)
        val h = minOf(820.0, bounds.height - 60.0)
        stage.scene = codegeasse.hikari.desktop.ui.Theme.style(Scene(root, w, h))
        stage.minWidth = minOf(900.0, w)
        stage.minHeight = minOf(560.0, h)
        stage.x = bounds.minX + (bounds.width - w) / 2
        stage.y = bounds.minY + (bounds.height - h) / 2
        stage.setOnCloseRequest { runCatching { codegeasse.hikari.desktop.player.Player.shutdown() } }
        stage.show()
        AppShell.goHome()
    }

    override fun stop() {
        runCatching { codegeasse.hikari.desktop.player.Player.shutdown() }
    }
}
