package android.content

import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/** Minimal SharedPreferences shape for legacy plugins (file-backed .properties). */
interface SharedPreferences {
    fun getString(key: String, defValue: String?): String?
    fun getInt(key: String, defValue: Int): Int
    fun getLong(key: String, defValue: Long): Long
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun contains(key: String): Boolean
    fun edit(): Editor

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun apply()
        fun commit(): Boolean
    }
}

/** Minimal Context shape for legacy plugins. */
abstract class Context {
    abstract fun getSharedPreferences(name: String, mode: Int): SharedPreferences
    abstract fun getFilesDir(): File
    abstract fun getCacheDir(): File

    companion object {
        const val MODE_PRIVATE = 0
    }
}

private class FilePrefs(private val file: File) : SharedPreferences {
    private val lock = Any()
    private var props: Properties = load()

    private fun load(): Properties {
        val p = Properties()
        try {
            if (file.exists()) file.inputStream().use { p.load(it) }
        } catch (_: Throwable) {
        }
        return p
    }

    private fun persist() {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { props.store(it, null) }
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        } catch (_: Throwable) {
        }
    }

    override fun getString(key: String, defValue: String?): String? = synchronized(lock) {
        props.getProperty(key, defValue)
    }

    override fun getInt(key: String, defValue: Int): Int = synchronized(lock) {
        props.getProperty(key)?.toIntOrNull() ?: defValue
    }

    override fun getLong(key: String, defValue: Long): Long = synchronized(lock) {
        props.getProperty(key)?.toLongOrNull() ?: defValue
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean = synchronized(lock) {
        props.getProperty(key)?.toBooleanStrictOrNull() ?: defValue
    }

    override fun contains(key: String): Boolean = synchronized(lock) { props.containsKey(key) }

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    private inner class EditorImpl : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, String?>()
        private var clearAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            pending[key] = value.toString()
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            pending[key] = value.toString()
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            pending[key] = value.toString()
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            pending[key] = null
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun apply() {
            commit()
        }

        override fun commit(): Boolean = synchronized(lock) {
            try {
                if (clearAll) props.clear()
                for ((k, v) in pending) {
                    if (v == null) props.remove(k) else props.setProperty(k, v)
                }
                persist()
                true
            } catch (_: Throwable) {
                false
            }
        }
    }
}

/** Desktop Context singleton handed to legacy plugins. */
class DesktopContext(private val baseDir: File) : Context() {
    private val prefs = ConcurrentHashMap<String, FilePrefs>()

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        prefs.computeIfAbsent(name) { FilePrefs(File(baseDir, "prefs/$name.properties")) }

    override fun getFilesDir(): File = File(baseDir, "files").apply { mkdirs() }
    override fun getCacheDir(): File = File(baseDir, "cache").apply { mkdirs() }

    companion object {
        @Volatile private var instance: DesktopContext? = null

        fun get(): DesktopContext {
            var c = instance
            if (c == null) {
                c = DesktopContext(File(System.getProperty("user.home"), ".hikari2"))
                instance = c
            }
            return c
        }
    }
}
