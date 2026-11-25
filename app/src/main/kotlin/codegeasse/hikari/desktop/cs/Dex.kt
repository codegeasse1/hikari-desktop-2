package codegeasse.hikari.desktop.cs

import codegeasse.hikari.desktop.core.Log
import codegeasse.hikari.desktop.core.Stores
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Loads extension payloads (.cs3 dex zips, JVM .jars) into child-first
 * class loaders. Converted jars are cached on disk keyed by payload hash.
 */
object Dex {
    data class Loaded(val loader: ClassLoader, val fromJar: Boolean)

    /** Packages always delegated to the app (parent) loader. */
    private val parentFirst = listOf(
        "java.", "jdk.", "javax.", "javafx.", "kotlin.", "kotlinx.",
        "com.lagradost.", "android.", "org.mozilla.", "okhttp3.", "okio.",
        "org.jsoup.", "com.fasterxml.", "com.fasterxml.jackson.",
        "org.objectweb.", "org.json.", "com.googlecode.", "de.femtopedia.",
        "com.hikari.", "codegeasse.",
    )

    private class ChildFirst(urls: Array<URL>, parent: ClassLoader) : URLClassLoader(urls, parent) {
        @Throws(ClassNotFoundException::class)
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            synchronized(getClassLoadingLock(name)) {
                var c = findLoadedClass(name)
                if (c == null) {
                    val shared = parentFirst.any { name.startsWith(it) }
                    if (!shared) {
                        try {
                            c = findClass(name)
                        } catch (_: ClassNotFoundException) {
                        }
                    }
                    if (c == null) c = super.loadClass(name, false)
                }
                if (resolve) resolveClass(c)
                return c
            }
        }
    }

    fun shaKey(vararg parts: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p.toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { it.toUByte().toString(16).padStart(2, '0') }.take(24)
    }

    private fun hasJvmClasses(payload: ByteArray): Boolean {
        if (payload.size < 4) return false
        if (!(payload[0] == 0x50.toByte() && payload[1] == 0x4B.toByte())) return false
        return try {
            val tmp = File.createTempFile("probe", ".zip")
            try {
                tmp.writeBytes(payload)
                ZipFile(tmp).use { z ->
                    z.entries().asSequence().any { it.name.endsWith(".class") }
                }
            } finally {
                tmp.delete()
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun manifestJson(jar: File): String? = try {        ZipFile(jar).use { z ->
            z.entries().asSequence().firstOrNull { it.name == "manifest.json" }?.let {
                z.getInputStream(it).use { ins -> ins.readBytes().toString(Charsets.UTF_8) }
            }
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * Load a payload, converting dex→jar when needed. Throws on failure.
     * [cacheKey] must be stable per payload version (e.g. engine+id+version).
     */
    fun load(payload: ByteArray, cacheKey: String): Loaded {
        require(payload.isNotEmpty()) { "empty payload" }
        val dir = Stores.dexCacheDir()
        val jar = File(dir, "$cacheKey.jar")
        val direct = hasJvmClasses(payload)
        if (!jar.exists() || jar.length() == 0L) {
            if (direct) {
                val tmp = File(dir, "$cacheKey.jar.part")
                tmp.writeBytes(payload)
                if (jar.exists()) jar.delete()
                tmp.renameTo(jar)
            } else {
                val tmp = File(dir, "$cacheKey.jar.part")
                val res = DexConverter.convert(payload, tmp)
                Log.i("dex", "converted $cacheKey: classes=${res.classCount} " +
                    "failed=${res.failedMethods.size} errors=${res.errors.size} ${res.millis}ms")
                if (!tmp.exists() || tmp.length() == 0L) {
                    throw IllegalStateException("dex conversion produced nothing: ${res.errors.take(2)}")
                }
                if (jar.exists()) jar.delete()
                tmp.renameTo(jar)
            }
        }
        val loader = ChildFirst(arrayOf(jar.toURI().toURL()), Dex::class.java.classLoader)
        return Loaded(loader, direct)
    }

    fun jarFile(cacheKey: String): File = File(Stores.dexCacheDir(), "$cacheKey.jar")

    /** True when [payload] is a zip/jar already holding JVM classes. */
    fun looksLikeJar(payload: ByteArray): Boolean = hasJvmClasses(payload)

    /**
     * Find concrete classes in [jar] extending/implementing [targetInternal]
     * (slash-separated, e.g. `com/foo/Base`). Reads class headers with ASM —
     * nothing is loaded. Fallback for payloads without manifest.json.
     */
    fun scanSubtypes(jar: File, targetInternal: String): List<String> {
        if (!jar.exists()) return emptyList()
        data class Info(val access: Int, val superName: String?, val ifaces: List<String>)
        val infos = LinkedHashMap<String, Info>()
        try {
            ZipFile(jar).use { z ->
                var n = 0
                val en = z.entries()
                while (en.hasMoreElements() && n < 20000) {
                    val e = en.nextElement()
                    if (!e.name.endsWith(".class") || e.name.contains("$")) continue
                    n++
                    try {
                        val bytes = z.getInputStream(e).use { it.readBytes() }
                        if (bytes.size > 2_000_000) continue
                        val cr = org.objectweb.asm.ClassReader(bytes)
                        infos[cr.className] = Info(cr.access, cr.superName, cr.interfaces.toList())
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (_: Throwable) {
            return emptyList()
        }
        fun isSub(name: String, seen: MutableSet<String>): Boolean {
            if (name == targetInternal) return true
            if (!seen.add(name)) return false
            val i = infos[name] ?: return false
            for (iface in i.ifaces) if (isSub(iface, seen)) return true
            val s = i.superName ?: return false
            return isSub(s, seen)
        }
        val accAbstract = org.objectweb.asm.Opcodes.ACC_ABSTRACT
        val accInterface = org.objectweb.asm.Opcodes.ACC_INTERFACE
        return infos.entries.filter { (name, info) ->
            (info.access and accAbstract) == 0 &&
                (info.access and accInterface) == 0 &&
                name != targetInternal &&
                runCatching { isSub(name, mutableSetOf()) }.getOrDefault(false)
        }.map { it.key.replace('/', '.') }
    }
}
