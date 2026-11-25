package codegeasse.hikari.desktop.player

import codegeasse.hikari.desktop.core.Log
import codegeasse.hikari.desktop.core.Stores
import codegeasse.hikari.desktop.model.StreamItem
import java.io.File

/** External mpv playback. The player ships inside the install (app/mpv/mpv.exe). */
object Player {
    @Volatile private var current: Process? = null

    fun mpvExe(): File? {
        val configured = Stores.getSetting("mpvPath").trim()
        if (configured.isNotEmpty()) {
            val f = File(configured)
            if (f.exists()) return f
        }
        // jpackage layout: <image>/app/mpv/mpv.exe ; java.home = <image>/runtime
        val bundled = runCatching {
            val javaHome = File(System.getProperty("java.home"))
            val candidates = listOf(
                File(javaHome.parentFile, "app/mpv/mpv.exe"),
                File(javaHome, "mpv/mpv.exe"),
                File("jpackage-input/mpv/mpv.exe"),
                File("mpv/mpv.exe"),
            )
            candidates.firstOrNull { it.exists() }
        }.getOrNull()
        if (bundled != null) return bundled
        // PATH fallback (dev machines).
        for (name in listOf("mpv.exe", "mpv")) {
            val found = runCatching {
                ProcessBuilder(if (File.separator == "\\") "where" else "which", name)
                    .redirectErrorStream(true).start().apply { waitFor() }
                    .inputStream.bufferedReader().readText().lines()
                    .firstOrNull { it.isNotBlank() }?.trim()?.let { File(it) }
            }.getOrNull()
            if (found != null && found.exists()) return found
        }
        return null
    }

    /** Launch mpv for [stream]. Returns false when mpv is missing or fails to start. */
    fun play(title: String, stream: StreamItem): Boolean {
        stop()
        val exe = mpvExe()
        if (exe == null) {
            Log.w("player", "mpv not found (Settings → Player)")
            return false
        }
        return try {
            val args = mutableListOf(exe.absolutePath, stream.url, "--title=$title", "--force-window=yes")
            if (stream.headers.isNotEmpty()) {
                val fields = stream.headers.entries
                    .filter { it.key.isNotBlank() && it.value.isNotBlank() }
                    .joinToString(",") { "\"${it.key}: ${it.value.replace("\"", "")}\"" }
                if (fields.isNotBlank()) args += "--http-header-fields=$fields"
            }
            stream.subtitles.firstOrNull()?.let { args += "--sub-file=${it.url}" }
            Log.i("player", "mpv ${stream.url.take(90)}")
            current = ProcessBuilder(args).redirectErrorStream(true).start()
            true
        } catch (t: Throwable) {
            Log.w("player", "launch failed: ${t.message}")
            false
        }
    }

    fun stop() {
        runCatching { current?.destroy() }
        current = null
    }

    fun shutdown() = stop()
}
