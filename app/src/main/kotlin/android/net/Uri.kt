// Desktop stand-in for android.net.Uri.
//
// Thirty of the ported files carry stream locations, deep links and cache keys
// as Uri values. This covers the members they use (parse, fromFile, encode and
// decode, the component getters, buildUpon and the query helpers) over
// java.net.URI-compatible parsing, so those files stay identical to the
// Android app. It is not the whole Android class: there is no Parcelable, no
// opaque-vs-hierarchical distinction beyond what the getters need, and
// decoding follows RFC 3986 rather than Android's leniencies.
package android.net

import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class Uri private constructor(private val raw: String) : Comparable<Uri> {

    val scheme: String?
        get() {
            val colon = raw.indexOf(':')
            if (colon <= 0) return null
            val s = raw.substring(0, colon)
            return if (s.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' } && s[0].isLetter()) s else null
        }

    val isAbsolute: Boolean get() = scheme != null
    val isRelative: Boolean get() = !isAbsolute

    /** Everything after "scheme:" */
    val schemeSpecificPart: String get() = scheme?.let { raw.substring(it.length + 1) } ?: raw

    val isHierarchical: Boolean get() = scheme == null || schemeSpecificPart.startsWith("/")
    val isOpaque: Boolean get() = !isHierarchical

    val encodedAuthority: String?
        get() {
            val ssp = schemeSpecificPart
            if (!ssp.startsWith("//")) return null
            val rest = ssp.substring(2)
            val end = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
            return rest.substring(0, end).takeIf { it.isNotEmpty() }
        }
    val authority: String? get() = encodedAuthority?.let { decode(it) }

    val host: String?
        get() {
            val auth = encodedAuthority ?: return null
            val afterUser = auth.substringAfterLast('@')
            return if (afterUser.startsWith("[")) afterUser.substringBefore(']') + "]"
            else afterUser.substringBefore(':').takeIf { it.isNotEmpty() }
        }

    val port: Int
        get() {
            val auth = encodedAuthority ?: return -1
            val afterUser = auth.substringAfterLast('@')
            val hostPort = if (afterUser.startsWith("[")) afterUser.substringAfter(']') else afterUser
            val colon = hostPort.lastIndexOf(':')
            return if (colon >= 0) hostPort.substring(colon + 1).toIntOrNull() ?: -1 else -1
        }

    val userInfo: String? get() = encodedAuthority?.takeIf { it.contains('@') }?.substringBeforeLast('@')?.let { decode(it) }

    val encodedPath: String?
        get() {
            if (isOpaque) return null
            val ssp = schemeSpecificPart
            val start = if (ssp.startsWith("//")) {
                val rest = ssp.substring(2)
                val i = rest.indexOfAny(charArrayOf('/', '?', '#'))
                if (i < 0 || rest[i] != '/') return if (i < 0) "" else ""
                2 + i
            } else 0
            val end = ssp.indexOfAny(charArrayOf('?', '#'), start).let { if (it < 0) ssp.length else it }
            return ssp.substring(start, end)
        }
    val path: String? get() = encodedPath?.let { decode(it) }

    val pathSegments: List<String>
        get() = (encodedPath ?: return emptyList()).split('/').filter { it.isNotEmpty() }.map { decode(it) }

    val lastPathSegment: String? get() = pathSegments.lastOrNull()

    val encodedQuery: String?
        get() {
            if (isOpaque) return null
            val q = raw.indexOf('?')
            if (q < 0) return null
            val h = raw.indexOf('#', q)
            return raw.substring(q + 1, if (h < 0) raw.length else h)
        }
    val query: String? get() = encodedQuery?.let { decode(it) }

    val encodedFragment: String?
        get() {
            val h = raw.indexOf('#')
            return if (h < 0) null else raw.substring(h + 1)
        }
    val fragment: String? get() = encodedFragment?.let { decode(it) }

    fun getQueryParameter(key: String): String? {
        val q = encodedQuery ?: return null
        val encodedKey = encode(key, null)
        for (pair in q.split('&')) {
            val eq = pair.indexOf('=')
            val k = if (eq < 0) pair else pair.substring(0, eq)
            if (k == encodedKey || decode(k) == key) {
                return if (eq < 0) "" else decode(pair.substring(eq + 1).replace('+', ' '))
            }
        }
        return null
    }

    fun getQueryParameters(key: String): List<String> {
        val q = encodedQuery ?: return emptyList()
        return q.split('&').mapNotNull { pair ->
            val eq = pair.indexOf('=')
            val k = if (eq < 0) pair else pair.substring(0, eq)
            if (decode(k) == key) (if (eq < 0) "" else decode(pair.substring(eq + 1).replace('+', ' '))) else null
        }
    }

    fun getQueryParameterNames(): Set<String> =
        (encodedQuery ?: return emptySet()).split('&').filter { it.isNotEmpty() }.map { decode(it.substringBefore('=')) }.toSet()

    fun getBooleanQueryParameter(key: String, defaultValue: Boolean): Boolean {
        val v = getQueryParameter(key) ?: return defaultValue
        return !(v.equals("false", true) || v == "0")
    }

    fun buildUpon(): Builder = Builder().apply {
        scheme(this@Uri.scheme)
        encodedAuthority(this@Uri.encodedAuthority)
        encodedPath(this@Uri.encodedPath)
        encodedQuery(this@Uri.encodedQuery)
        encodedFragment(this@Uri.encodedFragment)
        if (isOpaque) encodedOpaquePart(schemeSpecificPart)
    }

    fun normalizeScheme(): Uri {
        val s = scheme ?: return this
        val lower = s.lowercase()
        return if (lower == s) this else Uri(lower + raw.substring(s.length))
    }

    override fun toString(): String = raw
    override fun equals(other: Any?): Boolean = other is Uri && other.raw == raw
    override fun hashCode(): Int = raw.hashCode()
    override fun compareTo(other: Uri): Int = raw.compareTo(other.raw)

    class Builder {
        private var scheme: String? = null
        private var opaquePart: String? = null
        private var authority: String? = null
        private var path: String? = null
        private var query: String? = null
        private var fragment: String? = null

        fun scheme(scheme: String?): Builder = apply { this.scheme = scheme }
        fun encodedOpaquePart(part: String?): Builder = apply { opaquePart = part }
        fun opaquePart(part: String?): Builder = apply { opaquePart = part?.let { encode(it, "/?#@:") } }
        fun authority(authority: String?): Builder = apply { this.authority = authority?.let { encode(it, "@:[]") } }
        fun encodedAuthority(authority: String?): Builder = apply { this.authority = authority }
        fun path(path: String?): Builder = apply { this.path = path?.let { encode(it, "/") } }
        fun encodedPath(path: String?): Builder = apply { this.path = path }
        fun appendPath(segment: String): Builder = appendEncodedPath(encode(segment, null))
        fun appendEncodedPath(segment: String): Builder = apply {
            val base = path ?: ""
            path = when {
                base.isEmpty() -> "/$segment"
                base.endsWith("/") -> base + segment
                else -> "$base/$segment"
            }
        }
        fun query(query: String?): Builder = apply { this.query = query?.let { encode(it, " &=;") } }
        fun encodedQuery(query: String?): Builder = apply { this.query = query }
        fun clearQuery(): Builder = apply { query = null }
        fun appendQueryParameter(key: String, value: String?): Builder = apply {
            val pair = encode(key, null) + "=" + encode(value ?: "", null)
            query = if (query.isNullOrEmpty()) pair else "$query&$pair"
        }
        fun fragment(fragment: String?): Builder = apply { this.fragment = fragment?.let { encode(it, null) } }
        fun encodedFragment(fragment: String?): Builder = apply { this.fragment = fragment }

        fun build(): Uri {
            val sb = StringBuilder()
            scheme?.let { sb.append(it).append(':') }
            val opaque = opaquePart
            if (opaque != null) {
                sb.append(opaque)
            } else {
                authority?.let { sb.append("//").append(it) }
                path?.let { p ->
                    if (authority != null && p.isNotEmpty() && !p.startsWith("/")) sb.append('/')
                    sb.append(p)
                }
                query?.let { sb.append('?').append(it) }
            }
            fragment?.let { sb.append('#').append(it) }
            return Uri(sb.toString())
        }
    }

    companion object {
        @JvmField val EMPTY: Uri = Uri("")

        @JvmStatic fun parse(uriString: String): Uri = Uri(uriString)

        @JvmStatic
        fun fromFile(file: File): Uri = Builder().scheme("file").encodedAuthority("").encodedPath(encode(file.absolutePath.replace('\\', '/'), "/")).build()

        @JvmStatic
        fun fromParts(scheme: String, ssp: String, fragment: String?): Uri =
            Builder().scheme(scheme).opaquePart(ssp).fragment(fragment).build()

        @JvmStatic
        fun withAppendedPath(baseUri: Uri, pathSegment: String): Uri = baseUri.buildUpon().appendEncodedPath(encode(pathSegment, null)).build()

        /** Percent-encodes everything but unreserved characters and [allow]. */
        @JvmStatic
        fun encode(s: String, allow: String? = null): String {
            val sb = StringBuilder(s.length + 16)
            val bytes = s.toByteArray(StandardCharsets.UTF_8)
            // Encode per character so multi-byte sequences stay together.
            var i = 0
            while (i < s.length) {
                val c = s[i]
                val keep = c.isLetterOrDigit() && c.code < 128 || c in "_-!.~'()*" || (allow != null && c in allow)
                if (keep) {
                    sb.append(c)
                    i++
                } else {
                    val cp = s.codePointAt(i)
                    val chunk = String(Character.toChars(cp)).toByteArray(StandardCharsets.UTF_8)
                    for (b in chunk) sb.append('%').append("%02X".format(b.toInt() and 0xFF))
                    i += Character.charCount(cp)
                }
            }
            @Suppress("UNUSED_VARIABLE") val unused = bytes
            return sb.toString()
        }

        @JvmStatic
        fun encode(s: String): String = encode(s, null)

        @JvmStatic
        fun decode(s: String): String = try {
            // URLDecoder also turns '+' into a space, which Uri.decode does not.
            URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            s
        }
    }
}
