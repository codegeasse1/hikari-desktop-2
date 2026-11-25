package codegeasse.hikari.desktop.core

import java.io.File
import java.time.Instant

/** Tiny timestamped logger: console + bounded in-memory ring for the UI. */
object Log {
    data class Entry(val at: Long, val tag: String, val level: String, val text: String)

    private val ring = java.util.concurrent.ConcurrentLinkedDeque<Entry>()
    private const val CAP = 600

    private fun push(level: String, tag: String, text: String) {
        if (text.isBlank()) return
        ring.addLast(Entry(System.currentTimeMillis(), tag, level, text))
        while (ring.size > CAP) ring.pollFirst()
    }

    fun i(tag: String, msg: String) {
        println("[${Instant.now()}][$tag] $msg")
        push("INFO", tag, msg)
    }

    fun w(tag: String, msg: String) {
        System.err.println("[${Instant.now()}][$tag][WARN] $msg")
        push("WARN", tag, msg)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        System.err.println("[${Instant.now()}][$tag][ERROR] $msg")
        t?.printStackTrace()
        push("ERROR", tag, if (t == null) msg else "$msg\n${t.javaClass.simpleName}: ${t.message}")
    }

    fun recent(limit: Int = 400): String =
        ring.toList().takeLast(limit).joinToString("\n") {
            "[${Instant.ofEpochMilli(it.at).toString().substring(11, 19)}][${it.tag}][${it.level}] ${it.text}"
        }

    fun dataDir(): File = File(System.getProperty("user.home"), ".hikari2").apply { mkdirs() }
}
