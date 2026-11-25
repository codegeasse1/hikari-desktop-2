package codegeasse.hikari.desktop.nuvio

import codegeasse.hikari.desktop.core.Log
import codegeasse.hikari.desktop.core.Net
import codegeasse.hikari.desktop.model.SubItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.mozilla.javascript.Context
import org.mozilla.javascript.FunctionObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Nuvio-compatible scraper runtime on Rhino (pure JVM, no natives).
 *
 * Contract mirror: module.exports.getStreams(tmdbId, mediaType, season, episode),
 * __native_fetch / __cheerio_* / __crypto_* / __parse_url / timers / console.
 * Each scraper runs on its own worker thread with a hard timeout; the bridge
 * is fully synchronous (blocking network + inline timers), which is safe
 * because scrapers are linear async flows awaiting plain values.
 */
object JsRun {
    private const val TIMEOUT_MS = 60_000L
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "nuvio-js").apply { isDaemon = true } }

    fun execScraper(
        code: String,
        scraperId: String,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        timeoutMs: Long = TIMEOUT_MS,
    ): List<NuvioResult> {
        val future = pool.submit<List<NuvioResult>> {
            runRhino(code, scraperId, tmdbId, mediaType, season, episode)
        }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            future.cancel(true)
            Log.w("nuvio-js", "scraper $scraperId timed out/failed: ${t.message}")
            emptyList()
        }
    }

    fun settingsLayout(code: String, scraperId: String): String? {
        val future = pool.submit<String?> {
            runRhinoSettings(code, scraperId)
        }
        return try {
            future.get(30_000L, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            future.cancel(true)
            null
        }
    }

    // -- Rhino plumbing ------------------------------------------------------

    private class Host(val scraperId: String) {
        val latch = CountDownLatch(1)
        val captured = AtomicReference<String>("[]")
        val dom = JsDom()
    }

    // NOTE: FunctionObject needs instance methods on a per-scope object; we use
    // a per-execution ScriptableObject holding the Host instead.
    private class ScopeFns(val host: Host) : ScriptableObject() {
        override fun getClassName(): String = "HostFns"

        @JvmName("jsCapture")
        fun jsCapture(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any? {
            host.captured.set(args.getOrNull(0)?.let { Context.toString(it) } ?: "[]")
            host.latch.countDown()
            return null
        }

        @JvmName("jsSleep")
        fun jsSleep(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any? {
            val ms = (args.getOrNull(0) as? Number)?.toLong()?.coerceIn(0L, 60_000L) ?: 0L
            try {
                Thread.sleep(ms)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return null
        }

        @JvmName("jsFetch")
        fun jsFetch(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any? {
            return nativeFetch(
                args.getOrNull(0)?.let { Context.toString(it) }.orEmpty(),
                args.getOrNull(1)?.let { Context.toString(it) } ?: "GET",
                args.getOrNull(2)?.let { Context.toString(it) } ?: "{}",
                args.getOrNull(3)?.let { Context.toString(it) } ?: "none",
                args.getOrNull(4)?.let { Context.toString(it) }.orEmpty(),
                (args.getOrNull(5) as? Boolean) ?: true,
            )
        }

        @JvmName("jsParseUrl")
        fun jsParseUrl(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any? {
            return parseUrl(args.getOrNull(0)?.let { Context.toString(it) }.orEmpty())
        }

        @JvmName("jsLog")
        fun jsLog(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any? {
            Log.i("scraper:" + host.scraperId, args.joinToString(" ") { Context.toString(it) })
            return null
        }

        @JvmName("jsCrypto")
        fun jsCrypto(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any? {
            return JsCrypto.call(args.map { Context.toString(it) })
        }

        @JvmName("jsDom")
        fun jsDom(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any? {
            val op = Context.toString(args.getOrNull(0))
            val rest = args.drop(1).map { Context.toString(it) }
            return host.dom.call(op, rest)
        }

        @JvmName("jsB64dec")
        fun jsB64dec(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any? {
            return runCatching {
                String(java.util.Base64.getDecoder().decode(Context.toString(args.getOrNull(0))), Charsets.UTF_8)
            }.getOrDefault("")
        }

        @JvmName("jsB64enc")
        fun jsB64enc(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any? {
            return java.util.Base64.getEncoder().encodeToString(Context.toString(args.getOrNull(0)).toByteArray(Charsets.UTF_8))
        }
    }

    private fun injectFns(cx: Context, scope: Scriptable, host: Host) {
        val fns = ScopeFns(host)
        fun add(jsName: String, javaName: String) {
            val m = ScopeFns::class.java.getMethod(
                javaName, Context::class.java, Scriptable::class.java, Scriptable::class.java,
                Array<Any?>::class.java,
            )
            val fn = FunctionObject(jsName, m, fns)
            ScriptableObject.putProperty(scope, jsName, fn)
        }
        add("__capture_result", "jsCapture")
        add("__plugin_sleep", "jsSleep")
        add("__native_fetch", "jsFetch")
        add("__parse_url", "jsParseUrl")
        add("__cheerio", "jsDom")
        add("__crypto", "jsCrypto")
        add("__b64dec", "jsB64dec")
        add("__b64enc", "jsB64enc")
        add("console_log", "jsLog")
    }

    private fun prelude(scraperId: String): String = """
        globalThis.SCRAPER_ID = __SCRAPER_ID__;
        globalThis.SCRAPER_SETTINGS = JSON.parse(__SCRAPER_SETTINGS__);
        if (typeof TMDB_API_KEY === 'undefined') { globalThis.TMDB_API_KEY = ""; }
        globalThis.console = {
          log: function(){ console_log(Array.prototype.join.call(arguments, " ")); },
          error: function(){ console_log("[err] " + Array.prototype.join.call(arguments, " ")); },
          warn: function(){ console_log("[warn] " + Array.prototype.join.call(arguments, " ")); },
          info: function(){ console_log(Array.prototype.join.call(arguments, " ")); },
          debug: function(){ console_log(Array.prototype.join.call(arguments, " ")); }
        };
        globalThis.setTimeout = function(cb, ms){ __plugin_sleep(ms); try { cb(); } catch(e){} return 0; };
        globalThis.clearTimeout = function(){};
        function __hdrs(obj){ var h = {}; if (obj) { for (var k in obj) { h[k] = String(obj[k]); } } return h; }
        globalThis.fetch = async function(url, opts){
          opts = opts || {};
          var method = (opts.method || "GET").toUpperCase();
          var headers = __hdrs(opts.headers);
          var body = opts.body || "";
          var kind = "none"; var value = "";
          if (typeof body === "string" && body.length > 0) { kind = "text"; value = body; }
          var raw = __native_fetch(String(url), method, JSON.stringify(headers), kind, value, opts.redirect !== "manual");
          var p = JSON.parse(raw);
          function resp(){
            return {
              ok: p.ok, status: p.status, statusText: p.statusText, url: p.url,
              headers: { get: function(n){ return p.headers[String(n).toLowerCase()] || null; } },
              text: async function(){ return p.body; },
              json: async function(){ return JSON.parse(p.body); }
            };
          }
          return resp();
        };
        globalThis.TextEncoder = function(){ this.encode = function(s){ var out = []; var str = unescape(encodeURIComponent(String(s))); for (var i = 0; i < str.length; i++) out.push(str.charCodeAt(i)); return out; }; };
        globalThis.TextDecoder = function(){ this.decode = function(bytes){ if (typeof bytes === "string") return bytes; var s = ""; for (var i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]); try { return decodeURIComponent(escape(s)); } catch(e){ return s; } }; };
        globalThis.URL = function(u){ var p = JSON.parse(__parse_url(String(u))); this.protocol = p.protocol; this.host = p.host; this.hostname = p.hostname; this.port = p.port; this.pathname = p.pathname; this.search = p.search; this.hash = p.hash; this.href = String(u); };
        function __cryptoCall(){ return __crypto.apply(null, arguments); }
        globalThis.crypto = {
          getRandomValues: function(arr){ for (var i = 0; i < arr.length; i++) arr[i] = Math.floor(Math.random()*256); return arr; },
          subtle: {
            digest: async function(algo, data){ var name = (typeof algo === "string") ? algo : (algo && algo.name); var hex = __cryptoCall("digest", name, __tobytes(data)); return __fromhex(hex); },
            importKey: async function(){ throw new Error("importKey not supported"); },
            sign: async function(){ throw new Error("sign not supported"); },
            verify: async function(){ throw new Error("verify not supported"); }
          }
        };
        function __tobytes(d){ if (typeof d === "string") { return Array.prototype.slice.call(new TextEncoder().encode(d)); } if (d && d.buffer) { return Array.prototype.slice.call(new Uint8Array(d.buffer)); } if (d && d.length != null) { return Array.prototype.slice.call(d); } return []; }
        function __fromhex(hex){ var b = []; for (var i = 0; i < hex.length; i += 2) b.push(parseInt(hex.substr(i, 2), 16)); return b; }
        globalThis.atob = function(s){ return __b64dec(String(s)); };
        globalThis.btoa = function(s){ return __b64enc(String(s)); };
        function __load(html){ return __cheerio("load", html); }
        function __sel(doc, q){ var ids = JSON.parse(__cheerio("select", doc, q)); return ids; }
        globalThis.cheerioLoad = __load;
    """.trimIndent().replace("__SCRAPER_ID__", JSONObject.quote(scraperId))
        .replace("__SCRAPER_SETTINGS__", JSONObject.quote("{}"))

    private const val DRIVER = """
        (async function() {
          try {
            var getStreams = (typeof module !== 'undefined' && module.exports && module.exports.getStreams) || globalThis.getStreams;
            if (!getStreams) { __capture_result("[]"); return; }
            var args = JSON.parse(__CALL_ARGS__);
            var r = await getStreams(args.tmdbId, args.mediaType, args.season == null ? undefined : args.season, args.episode == null ? undefined : args.episode);
            __capture_result(JSON.stringify(r || []));
          } catch (e) { __capture_result("[]"); }
        })();
    """

    private const val SETTINGS_DRIVER = """
        (async function() {
          try {
            var onSettings = (typeof module !== 'undefined' && module.exports && module.exports.onSettings) || globalThis.onSettings;
            if (typeof onSettings === 'function') { var layout = await onSettings(); __capture_result(JSON.stringify(layout || [])); }
            else { __capture_result("[]"); }
          } catch (e) { __capture_result("[]"); }
        })();
    """

    private fun runRhino(
        code: String, scraperId: String, tmdbId: String, mediaType: String, season: Int?, episode: Int?,
    ): List<NuvioResult> {
        val raw = runScript(code, scraperId, DRIVER.replace(
            "__CALL_ARGS__",
            JSONObject.quote("""{"tmdbId":"$tmdbId","mediaType":"$mediaType","season":${season ?: "null"},"episode":${episode ?: "null"}}"""),
        )) ?: return emptyList()
        return parseResults(raw)
    }

    private fun runRhinoSettings(code: String, scraperId: String): String? {
        return runScript(code, scraperId, SETTINGS_DRIVER)
    }

    private fun runScript(code: String, scraperId: String, driver: String): String? {
        val host = Host(scraperId)
        val cx = Context.enter()
        try {
            cx.optimizationLevel = 9
            val scope = cx.initStandardObjects()
            injectFns(cx, scope, host)
            cx.evaluateString(scope, prelude(scraperId), "prelude", 1, null)
            // Wrap scraper code in a function scope like the reference does.
            cx.evaluateString(scope, "var module = { exports: {} };\nvar exports = module.exports;\n(function(){\n$code\n})();", "scraper", 1, null)
            cx.evaluateString(scope, driver, "driver", 1, null)
            // Give in-flight promise jobs a chance to settle on this thread.
            host.latch.await(55, TimeUnit.SECONDS)
            return host.captured.get()
        } catch (t: Throwable) {
            Log.w("nuvio-js", "scraper $scraperId error: ${t.message}")
            return "[]"
        } finally {
            Context.exit()
            host.dom.clear()
        }
    }

    fun parseResults(rawJson: String): List<NuvioResult> {
        return runCatching {
            val array = json.parseToJsonElement(rawJson) as? JsonArray ?: return emptyList()
            array.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val url = when (val v = item["url"]) {
                    is kotlinx.serialization.json.JsonPrimitive -> v.contentOrNull?.takeIf { it.isNotBlank() }
                    is JsonObject -> v["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    else -> null
                } ?: return@mapNotNull null
                val headers = (item["headers"] as? JsonObject)?.entries?.mapNotNull { (k, v) ->
                    v.jsonPrimitive.contentOrNull?.let { k to it }
                }?.toMap()?.takeIf { it.isNotEmpty() }
                val subs = (item["subtitles"] as? JsonArray)?.mapNotNull { (it as? JsonObject) }?.mapNotNull {
                    val u = it["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    SubItem(it["language"]?.jsonPrimitive?.contentOrNull ?: "und", u)
                } ?: emptyList()
                NuvioResult(
                    title = item["title"]?.jsonPrimitive?.contentOrNull
                        ?: item["name"]?.jsonPrimitive?.contentOrNull ?: "Unknown",
                    url = url,
                    quality = item["quality"]?.jsonPrimitive?.contentOrNull,
                    size = item["size"]?.jsonPrimitive?.contentOrNull,
                    language = item["language"]?.jsonPrimitive?.contentOrNull,
                    provider = item["provider"]?.jsonPrimitive?.contentOrNull,
                    headers = headers,
                    subtitles = subs,
                )
            }.filter { it.url.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    // -- native fetch (blocking, on the scraper worker thread) ----------------

    private fun nativeFetch(url: String, method: String, headersJson: String, bodyKind: String, body: String, followRedirects: Boolean): String {
        return try {
            val headers = runCatching {
                (json.parseToJsonElement(headersJson) as? JsonObject)?.entries?.mapNotNull { (k, v) ->
                    v.jsonPrimitive.contentOrNull?.let { k to it }
                }?.toMap() ?: emptyMap()
            }.getOrDefault(emptyMap()).toMutableMap()
            if (!headers.containsKey("User-Agent")) headers["User-Agent"] = Net.UA
            val reqBuilder = Request.Builder().url(url).header("User-Agent", headers.remove("User-Agent") ?: Net.UA)
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }
            val req = if (method == "GET" || bodyKind == "none") reqBuilder.get().build()
            else reqBuilder.method(method, body.toRequestBody("text/plain; charset=utf-8".toMediaTypeOrNull())).build()
            Net.client.newCall(req).execute().use { r ->
                val bytes = r.body?.bytes() ?: ByteArray(0)
                val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
                val h = r.headers.toMultimap().entries.associate { (k, v) -> k.lowercase() to (v.firstOrNull() ?: "") }
                JsonObject(
                    mapOf(
                        "ok" to JsonPrimitive(r.code in 200..299),
                        "status" to JsonPrimitive(r.code),
                        "url" to JsonPrimitive(r.request.url.toString()),
                        "statusText" to JsonPrimitive(r.message),
                        "body" to JsonPrimitive(text),
                        "bodyBase64" to JsonPrimitive(java.util.Base64.getEncoder().encodeToString(bytes)),
                        "headers" to JsonObject(h.mapValues { JsonPrimitive(it.value) }),
                    ),
                ).toString()
            }
        } catch (t: Throwable) {
            JsonObject(
                mapOf(
                    "ok" to JsonPrimitive(false),
                    "status" to JsonPrimitive(0),
                    "statusText" to JsonPrimitive(t.message ?: "fetch failed"),
                    "url" to JsonPrimitive(url),
                    "body" to JsonPrimitive(""),
                    "bodyBase64" to JsonPrimitive(""),
                    "headers" to JsonObject(emptyMap()),
                ),
            ).toString()
        }
    }

    private fun parseUrl(urlString: String): String {
        return try {
            val u = java.net.URI(urlString)
            val port = if (u.port < 0) "" else u.port.toString()
            JsonObject(
                mapOf(
                    "protocol" to JsonPrimitive((u.scheme ?: "") + ":"),
                    "host" to JsonPrimitive(u.host + if (port.isNotEmpty()) ":$port" else ""),
                    "hostname" to JsonPrimitive(u.host ?: ""),
                    "port" to JsonPrimitive(port),
                    "pathname" to JsonPrimitive(u.rawPath?.ifBlank { "/" } ?: "/"),
                    "search" to JsonPrimitive(u.rawQuery?.let { "?$it" } ?: ""),
                    "hash" to JsonPrimitive(u.rawFragment?.let { "#$it" } ?: ""),
                ),
            ).toString()
        } catch (t: Throwable) {
            JsonObject(
                mapOf(
                    "protocol" to JsonPrimitive(""), "host" to JsonPrimitive(""),
                    "hostname" to JsonPrimitive(""), "port" to JsonPrimitive(""),
                    "pathname" to JsonPrimitive("/"), "search" to JsonPrimitive(""),
                    "hash" to JsonPrimitive(""),
                ),
            ).toString()
        }
    }

    // -- crypto (JCA, same op names as the reference bridge) -------------------

    private object JsCrypto {
        fun call(args: List<String>): String {
            return try {
                when (args.getOrNull(0)) {
                    "digest" -> {
                        val algo = jcaDigest(args.getOrNull(1) ?: "SHA256")
                        val data = hexToBytes(args.getOrNull(2).orEmpty())
                        java.security.MessageDigest.getInstance(algo).digest(data).toHex()
                    }
                    "hmac" -> {
                        val algo = "Hmac" + (args.getOrNull(1) ?: "SHA256").uppercase().replace("-", "")
                        val key = hexToBytes(args.getOrNull(2).orEmpty())
                        val data = hexToBytes(args.getOrNull(3).orEmpty())
                        val mac = javax.crypto.Mac.getInstance(algo)
                        mac.init(javax.crypto.spec.SecretKeySpec(key, algo))
                        mac.doFinal(data).toHex()
                    }
                    "random" -> {
                        val n = args.getOrNull(1)?.toIntOrNull() ?: 16
                        java.security.SecureRandom().generateSeed(n).toHex()
                    }
                    "aes-enc" -> aes(args, javax.crypto.Cipher.ENCRYPT_MODE)
                    "aes-dec" -> aes(args, javax.crypto.Cipher.DECRYPT_MODE)
                    "pbkdf2" -> {
                        val pass = hexToBytes(args.getOrNull(1).orEmpty())
                        val salt = hexToBytes(args.getOrNull(2).orEmpty())
                        val iter = args.getOrNull(3)?.toIntOrNull() ?: 1000
                        val bits = args.getOrNull(4)?.toIntOrNull() ?: 256
                        val f = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        f.generateSecret(javax.crypto.spec.PBEKeySpec(String(pass, Charsets.UTF_8).toCharArray(), salt, iter, bits)).encoded.toHex()
                    }
                    else -> ""
                }
            } catch (t: Throwable) {
                ""
            }
        }

        private fun aes(args: List<String>, mode: Int): String {
            val transformation = when ((args.getOrNull(1) ?: "AES-CBC").uppercase()) {
                "AES-GCM" -> "AES/GCM/NoPadding"
                else -> "AES/CBC/PKCS5Padding"
            }
            val key = hexToBytes(args.getOrNull(2).orEmpty())
            val iv = hexToBytes(args.getOrNull(3).orEmpty())
            val data = hexToBytes(args.getOrNull(4).orEmpty())
            val c = javax.crypto.Cipher.getInstance(transformation)
            val ks = javax.crypto.spec.SecretKeySpec(key, "AES")
            if (transformation.contains("GCM")) {
                c.init(mode, ks, javax.crypto.spec.GCMParameterSpec(128, iv))
            } else {
                c.init(mode, ks, javax.crypto.spec.IvParameterSpec(iv))
            }
            return c.doFinal(data).toHex()
        }

        private fun jcaDigest(a: String): String = when (a.uppercase().replace("-", "")) {
            "SHA1" -> "SHA-1"
            "SHA512" -> "SHA-512"
            "MD5" -> "MD5"
            else -> "SHA-256"
        }

        private fun ByteArray.toHex(): String = joinToString("") { it.toUByte().toString(16).padStart(2, '0') }

        private fun hexToBytes(h: String): ByteArray {
            val s = h.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
            return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }
    }

    // -- cheerio-style DOM over jsoup ------------------------------------------

    private class JsDom {
        private var docCounter = 0
        private val docs = mutableMapOf<String, org.jsoup.nodes.Document>()
        private val els = mutableMapOf<String, org.jsoup.nodes.Element>()

        fun clear() {
            docs.clear()
            els.clear()
        }

        fun call(op: String, args: List<String>): String = when (op) {
            "load" -> {
                val id = "doc${docCounter++}"
                docs[id] = org.jsoup.Jsoup.parse(args.getOrNull(0).orEmpty())
                if (docs.size > 8) docs.remove(docs.keys.first())
                id
            }
            "select" -> {
                val doc = docs[args.getOrNull(0)] ?: return "[]"
                val q = args.getOrNull(1).orEmpty()
                val found = runCatching { doc.select(q) }.getOrDefault(emptyList())
                ids(found, args[0])
            }
            "find" -> {
                val el = els[args.getOrNull(1)] ?: return "[]"
                val q = args.getOrNull(2).orEmpty()
                val found = runCatching { el.select(q) }.getOrDefault(emptyList())
                ids(found, args[0])
            }
            "text" -> args.getOrNull(1).orEmpty().split(",").filter { it.isNotEmpty() }
                .mapNotNull { els[it]?.text() }.joinToString(" ")
            "html" -> {
                val elId = args.getOrNull(1).orEmpty()
                if (elId.isEmpty()) docs[args.getOrNull(0)]?.html().orEmpty()
                else els[elId]?.html().orEmpty()
            }
            "inner_html" -> els[args.getOrNull(1)]?.html().orEmpty()
            "attr" -> {
                val v = els[args.getOrNull(1)]?.attr(args.getOrNull(2).orEmpty())
                if (v.isNullOrEmpty()) "__UNDEFINED__" else v
            }
            "next" -> sib(args, true)
            "prev" -> sib(args, false)
            else -> ""
        }

        private fun ids(found: List<org.jsoup.nodes.Element>, docId: String): String {
            val out = found.map { el ->
                val id = "$docId:${el.hashCode()}"
                els[id] = el
                "\"$id\""
            }
            return "[" + out.joinToString(",") + "]"
        }

        private fun sib(args: List<String>, next: Boolean): String {
            val el = els[args.getOrNull(1)] ?: return "__NONE__"
            val s = if (next) el.nextElementSibling() else el.previousElementSibling()
                ?: return "__NONE__"
            val id = args[0] + ":" + s.hashCode()
            els[id] = s
            return id
        }
    }

    // minimal org.json quote without the dependency weight in JS strings
    private object JSONObject {
        fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r") + "\""
    }
}
