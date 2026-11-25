package android.net

class Uri private constructor(private val uri: java.net.URI) {
    val scheme: String? get() = uri.scheme
    val host: String? get() = uri.host
    val port: Int get() = uri.port
    val path: String? get() = uri.path
    val query: String? get() = uri.query
    val fragment: String? get() = uri.fragment

    val lastPathSegment: String?
        get() = uri.path?.split("/")?.lastOrNull()?.takeIf { it.isNotEmpty() }

    val pathSegments: List<String>
        get() = uri.path?.split("/")?.filter { it.isNotEmpty() } ?: emptyList()

    fun getQueryParameter(name: String): String? {
        val q = uri.rawQuery ?: return null
        return q.split("&").mapNotNull {
            val i = it.indexOf('=')
            if (i < 0) null
            else java.net.URLDecoder.decode(it.substring(0, i), "UTF-8") to
                java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.firstOrNull { it.first == name }?.second
    }

    fun buildUpon(): Builder = Builder(uri.toString())

    override fun toString(): String = uri.toString()

    class Builder(private var base: String) {
        private val params = mutableListOf<Pair<String, String>>()

        fun appendQueryParameter(key: String, value: String): Builder {
            params += key to value
            return this
        }

        fun appendPath(segment: String): Builder {
            base = base.trimEnd('/') + "/" + segment.trim('/')
            return this
        }

        fun scheme(scheme: String): Builder = apply { base = "$scheme://" + base.substringAfter("://", base) }

        fun authority(authority: String): Builder = apply {
            base = base.replace(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/]*"), "$0")
        }

        fun build(): Uri = parse(
            if (params.isEmpty()) base
            else base + (if (base.contains("?")) "&" else "?") +
                params.joinToString("&") {
                    java.net.URLEncoder.encode(it.first, "UTF-8") + "=" + java.net.URLEncoder.encode(it.second, "UTF-8")
                },
        )
    }

    companion object {
        @JvmStatic fun parse(uriString: String): Uri = Uri(java.net.URI(uriString))

        @JvmStatic fun withAppendedPath(baseUri: Uri, pathSegment: String): Uri =
            parse(baseUri.toString().trimEnd('/') + "/" + pathSegment.trim('/'))

        @JvmStatic fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

        @JvmStatic fun decode(s: String): String = java.net.URLDecoder.decode(s, "UTF-8")
    }
}
