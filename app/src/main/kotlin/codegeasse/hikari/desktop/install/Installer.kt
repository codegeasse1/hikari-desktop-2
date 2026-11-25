package codegeasse.hikari.desktop.install

import codegeasse.hikari.desktop.core.Fx
import codegeasse.hikari.desktop.core.Log
import codegeasse.hikari.desktop.core.Net
import codegeasse.hikari.desktop.core.Stores
import codegeasse.hikari.desktop.model.InstallJob
import codegeasse.hikari.desktop.model.JobState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Background extension installs. Every install ACKs instantly (a job row
 * appears at once) and the download/convert happens off the FX thread.
 */
object Installer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, InstallJob>()
    private val listeners = mutableListOf<() -> Unit>()

    fun snapshot(): List<InstallJob> = jobs.values.toList()

    fun onChange(listener: () -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    private fun ping() {
        val copy: List<() -> Unit>
        synchronized(listeners) { copy = listeners.toList() }
        Fx.run { copy.forEach { runCatching { it() } } }
    }

    fun pluginDir(engine: String, name: String): File =
        File(Stores.pluginDir(engine), sanitize(name)).apply { mkdirs() }

    fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80).ifBlank { "plugin" }

    /** First-byte-wins download across mirrors. Null when every mirror fails. */
    suspend fun fetchPayload(urls: List<String>, onProgress: (Double) -> Unit = {}): ByteArray? {
        if (urls.isEmpty()) return null
        // Fast path: race full bodies for small payloads via Net.
        val win = Net.raceBytes(urls)
        if (win?.second != null) {
            onProgress(1.0)
            return win.second
        }
        // Slow path: stream the first URL with progress.
        val first = urls.first()
        val tmp = File.createTempFile("hiki-dl", ".part")
        try {
            val ok = Net.download(first, tmp) { onProgress(it) }
            if (!ok) return null
            return tmp.readBytes()
        } finally {
            runCatching { tmp.delete() }
        }
    }

    fun enqueue(job: InstallJob, work: suspend () -> Unit) {
        jobs[job.key] = job
        ping()
        scope.launch {
            try {
                work()
                if (job.state == JobState.RUNNING) job.state = JobState.DONE
            } catch (t: Throwable) {
                Log.w("install", "${job.label} failed: ${t.message}")
                job.state = JobState.FAILED
                job.note = t.message?.take(120) ?: "failed"
            }
            ping()
            // Prune finished jobs after a while so the list stays short.
            scope.launch {
                kotlinx.coroutines.delay(60_000)
                if (jobs[job.key]?.state != JobState.RUNNING) {
                    jobs.remove(job.key)
                    ping()
                }
            }
        }
    }

    fun progress(key: String, frac: Double, note: String = "") {
        jobs[key]?.let {
            it.progress = frac
            if (note.isNotEmpty()) it.note = note
            ping()
        }
    }

    fun uninstall(engine: String, name: String): Boolean {
        return try {
            val dir = File(Stores.pluginDir(engine), sanitize(name))
            val gone = !dir.exists() || dir.deleteRecursively()
            // Forget cached dex jars for this payload.
            runCatching {
                Stores.dexCacheDir().listFiles()
                    ?.filter { it.name.startsWith("$engine-${sanitize(name)}-") }
                    ?.forEach { it.delete() }
            }
            Log.i("install", "uninstalled $engine/$name")
            gone
        } catch (t: Throwable) {
            Log.w("install", "uninstall failed: ${t.message}")
            false
        }
    }

    fun disabledKey(engine: String): String = "$engine-disabled"

    fun disabledIds(engine: String): Set<String> {
        val arr = Stores.providers.readArray(disabledKey(engine))
        return arr.mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
    }

    /**
     * True when [remote] is a newer version than [installed]. Compares
     * dot-separated numeric parts first ("10" > "9"); falls back to plain
     * inequality for non-numeric schemes (commit hashes, dates).
     */
    fun isNewer(remote: String?, installed: String?): Boolean {
        if (remote.isNullOrBlank()) return false
        if (installed.isNullOrBlank()) return true
        val r = remote.trim()
        val i = installed.trim()
        if (r == i) return false
        val rn = r.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull() }
        val in_ = i.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull() }
        if (rn.isNotEmpty() && in_.isNotEmpty()) {
            val n = maxOf(rn.size, in_.size)
            for (k in 0 until n) {
                val d = rn.getOrElse(k) { 0 } - in_.getOrElse(k) { 0 }
                if (d != 0) return d > 0
            }
            return false
        }
        return true
    }

    fun isEnabled(engine: String, id: String): Boolean = id !in disabledIds(engine)

    fun setEnabled(engine: String, id: String, enabled: Boolean) {
        val cur = disabledIds(engine).toMutableSet()
        if (enabled) cur.remove(id) else cur.add(id)
        Stores.providers.put(disabledKey(engine), JsonArray(cur.map { JsonPrimitive(it) }))
    }
}
