package tf.monochrome.desktop.data.cache

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Desktop: the http(s) [DataSource] that Media3's DefaultHttpDataSource was on
 * Android — what the player's data-source factory used for streams, radio and
 * anything else with a URL.
 *
 * Built on the JDK's HttpURLConnection, the same transport DefaultHttpDataSource
 * used: a read timeout applies to every socket read (a stalled Icecast server
 * fails the read instead of hanging the decode thread) and [close] from another
 * thread aborts a blocked read. It covers what the app relied on:
 *
 * - request headers: the factory's defaults, then the [DataSpec]'s own, and a
 *   User-Agent;
 * - byte ranges: `Range: bytes=<position>-[<end>]` for a seek or a bounded read,
 *   and a server that ignores the range (200 instead of 206) is skipped forward
 *   to the position, as Media3 does;
 * - redirects followed by hand (up to [MAX_REDIRECTS]) so that a redirect between
 *   http and https is allowed only when [Factory.setAllowCrossProtocolRedirects]
 *   says so — DefaultHttpDataSource's rule, and the one the radio code turns on;
 * - gzip only when the [DataSpec] carries [DataSpec.FLAG_ALLOW_GZIP], otherwise
 *   `Accept-Encoding: identity` so lengths and offsets stay byte-accurate.
 */
@UnstableApi
class HttpDataSource private constructor(
    private val userAgent: String?,
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int,
    private val allowCrossProtocolRedirects: Boolean,
    private val defaultRequestProperties: Map<String, String>,
) : BaseDataSource(/* isNetwork = */ true) {

    private var dataSpec: DataSpec? = null
    private var connection: HttpURLConnection? = null
    private var input: InputStream? = null
    private var headers: Map<String, List<String>> = emptyMap()
    private var bytesToRead: Long = C.LENGTH_UNSET.toLong()
    private var bytesRead: Long = 0L
    private var opened = false

    /** The URL the bytes are coming from: the last redirect's target once open. */
    override val uri: Uri?
        get() = connection?.url?.let { Uri.parse(it.toString()) } ?: dataSpec?.uri

    override val responseHeaders: Map<String, List<String>> get() = headers

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        bytesRead = 0L
        bytesToRead = 0L
        transferInitializing(dataSpec)

        val conn = try {
            connectFollowingRedirects(dataSpec)
        } catch (e: IOException) {
            closeConnectionQuietly()
            throw HttpDataSourceException("Unable to connect to ${dataSpec.uri}", e, dataSpec)
        }
        connection = conn

        val code = try {
            conn.responseCode
        } catch (e: IOException) {
            closeConnectionQuietly()
            throw HttpDataSourceException("Unable to connect to ${dataSpec.uri}", e, dataSpec)
        }
        headers = conn.headerFields.filterKeys { it != null }

        if (code !in 200..299) {
            // Asking for the range that starts exactly at the end of the resource
            // is not an error: there are simply no bytes left.
            if (code == 416 && dataSpec.position > 0 &&
                dataSpec.position == totalLengthFromContentRange(conn.getHeaderField("Content-Range"))
            ) {
                opened = true
                transferStarted(dataSpec)
                return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else 0L
            }
            val message = conn.responseMessage
            closeConnectionQuietly()
            throw InvalidResponseCodeException(code, message, headers, dataSpec)
        }

        // A range from a non-zero position answered with 200 means the server
        // ignored it and is sending the whole resource: skip to the position.
        val bytesToSkip = if (code == 200 && dataSpec.position != 0L) dataSpec.position else 0L

        val compressed = conn.getHeaderField("Content-Encoding").equals("gzip", ignoreCase = true)
        bytesToRead = if (!compressed) {
            if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                dataSpec.length
            } else {
                val contentLength = contentLengthOf(conn)
                if (contentLength != C.LENGTH_UNSET.toLong()) contentLength - bytesToSkip else C.LENGTH_UNSET.toLong()
            }
        } else {
            // The wire length says nothing about the decompressed one.
            dataSpec.length
        }

        try {
            val raw = conn.inputStream
            input = if (compressed) GZIPInputStream(raw) else raw
        } catch (e: IOException) {
            closeConnectionQuietly()
            throw HttpDataSourceException("Unable to open the response body of ${dataSpec.uri}", e, dataSpec)
        }

        opened = true
        transferStarted(dataSpec)

        try {
            skipFully(bytesToSkip, dataSpec)
        } catch (e: IOException) {
            closeConnectionQuietly()
            throw e
        }
        return bytesToRead
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        var readLength = length
        if (bytesToRead != C.LENGTH_UNSET.toLong()) {
            val remaining = bytesToRead - bytesRead
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            readLength = minOf(readLength.toLong(), remaining).toInt()
        }
        val stream = input ?: return C.RESULT_END_OF_INPUT
        val n = try {
            stream.read(buffer, offset, readLength)
        } catch (e: IOException) {
            throw HttpDataSourceException("Read failed for ${dataSpec?.uri}", e, dataSpec)
        }
        if (n == -1) return C.RESULT_END_OF_INPUT
        bytesRead += n
        bytesTransferred(n)
        return n
    }

    @Throws(IOException::class)
    override fun close() {
        try {
            try {
                input?.close()
            } catch (e: IOException) {
                // A body abandoned half way is the normal case for a seek; the
                // connection is being dropped either way.
            }
        } finally {
            input = null
            closeConnectionQuietly()
            dataSpec = null
            headers = emptyMap()
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    // ── connection ───────────────────────────────────────────────────────────

    private fun connectFollowingRedirects(dataSpec: DataSpec): HttpURLConnection {
        var url = toUrl(dataSpec.uri.toString())
        var method = dataSpec.httpMethod
        var body = dataSpec.httpBody
        repeat(MAX_REDIRECTS + 1) {
            val conn = makeConnection(url, method, body, dataSpec)
            val code = try {
                conn.responseCode
            } catch (e: IOException) {
                conn.disconnect()
                throw e
            }
            if (code !in REDIRECT_CODES) return conn
            val location = conn.getHeaderField("Location")
            conn.disconnect()
            url = resolveRedirect(url, location)
            // 303, and 301/302 after a POST, continue as a GET without the body,
            // which is what browsers (and Media3) do.
            if (code == 303 || (method == DataSpec.HTTP_METHOD_POST && code in 300..302)) {
                method = DataSpec.HTTP_METHOD_GET
                body = null
            }
        }
        throw java.net.NoRouteToHostException("Too many redirects: ${dataSpec.uri}")
    }

    private fun makeConnection(url: URL, method: Int, body: ByteArray?, dataSpec: DataSpec): HttpURLConnection {
        val conn = url.openConnection() as? HttpURLConnection
            ?: throw IOException("Not an http(s) URL: $url")
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.instanceFollowRedirects = false
        conn.useCaches = false

        for ((name, value) in defaultRequestProperties) conn.setRequestProperty(name, value)
        for ((name, value) in dataSpec.httpRequestHeaders) conn.setRequestProperty(name, value)

        rangeHeader(dataSpec.position, dataSpec.length)?.let { conn.setRequestProperty("Range", it) }
        userAgent?.let { conn.setRequestProperty("User-Agent", it) }
        conn.setRequestProperty(
            "Accept-Encoding",
            if (dataSpec.isFlagSet(DataSpec.FLAG_ALLOW_GZIP)) "gzip" else "identity",
        )

        conn.requestMethod = when (method) {
            DataSpec.HTTP_METHOD_POST -> "POST"
            DataSpec.HTTP_METHOD_HEAD -> "HEAD"
            else -> "GET"
        }
        try {
            if (body != null && method == DataSpec.HTTP_METHOD_POST) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.connect()
                conn.outputStream.use { it.write(body) }
            } else {
                conn.connect()
            }
        } catch (e: IOException) {
            conn.disconnect()
            throw e
        }
        return conn
    }

    private fun resolveRedirect(from: URL, location: String?): URL {
        if (location.isNullOrBlank()) throw IOException("Redirect without a Location header from $from")
        val target = try {
            from.toURI().resolve(location).toURL()
        } catch (e: Exception) {
            @Suppress("DEPRECATION")
            URL(from, location)
        }
        val protocol = target.protocol
        if (protocol != "http" && protocol != "https") {
            throw IOException("Unsupported protocol redirect: $protocol ($from -> $target)")
        }
        if (!allowCrossProtocolRedirects && protocol != from.protocol) {
            throw IOException("Disallowed cross-protocol redirect (${from.protocol} to $protocol): $from -> $target")
        }
        return target
    }

    private fun skipFully(count: Long, dataSpec: DataSpec) {
        if (count == 0L) return
        val stream = input ?: return
        val scratch = ByteArray(4096)
        var left = count
        while (left > 0) {
            val n = stream.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            if (n == -1) {
                throw HttpDataSourceException(
                    "Server ended the body before position ${dataSpec.position}",
                    null,
                    dataSpec,
                    DataSourceException.POSITION_OUT_OF_RANGE,
                )
            }
            left -= n
            bytesTransferred(n)
        }
    }

    private fun closeConnectionQuietly() {
        connection?.let { runCatching { it.disconnect() } }
        connection = null
    }

    // ── errors ───────────────────────────────────────────────────────────────

    /** Media3's HttpDataSource.HttpDataSourceException: a failure talking to the server. */
    open class HttpDataSourceException(
        message: String?,
        cause: Throwable?,
        @JvmField val dataSpec: DataSpec?,
        reason: Int = REASON_IO,
    ) : DataSourceException(message, cause, reason)

    /** Media3's HttpDataSource.InvalidResponseCodeException: the server answered, but not with 2xx. */
    class InvalidResponseCodeException(
        @JvmField val responseCode: Int,
        @JvmField val responseMessage: String?,
        @JvmField val headerFields: Map<String, List<String>>,
        dataSpec: DataSpec,
    ) : HttpDataSourceException("Response code: $responseCode", null, dataSpec, REASON_BAD_HTTP_STATUS)

    // ── factory ──────────────────────────────────────────────────────────────

    /** Same setters as DefaultHttpDataSource.Factory, so the player's wiring reads the same. */
    class Factory : DataSource.Factory {
        private var userAgent: String? = null
        private var connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MILLIS
        private var readTimeoutMs = DEFAULT_READ_TIMEOUT_MILLIS
        private var allowCrossProtocolRedirects = false
        private val defaultRequestProperties = LinkedHashMap<String, String>()

        fun setUserAgent(userAgent: String?): Factory = apply { this.userAgent = userAgent }
        fun setConnectTimeoutMs(timeoutMs: Int): Factory = apply { connectTimeoutMs = timeoutMs }
        fun setReadTimeoutMs(timeoutMs: Int): Factory = apply { readTimeoutMs = timeoutMs }
        fun setAllowCrossProtocolRedirects(allow: Boolean): Factory = apply { allowCrossProtocolRedirects = allow }
        fun setDefaultRequestProperties(properties: Map<String, String>): Factory = apply {
            defaultRequestProperties.clear()
            defaultRequestProperties.putAll(properties)
        }

        override fun createDataSource(): HttpDataSource = HttpDataSource(
            userAgent = userAgent,
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
            allowCrossProtocolRedirects = allowCrossProtocolRedirects,
            defaultRequestProperties = LinkedHashMap(defaultRequestProperties),
        )
    }

    companion object {
        /** Media3's DefaultHttpDataSource defaults. */
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 8_000
        const val DEFAULT_READ_TIMEOUT_MILLIS = 8_000
        const val MAX_REDIRECTS = 20

        /** Media3's PlaybackException error codes for the two failure kinds. */
        const val REASON_IO = 2000
        const val REASON_BAD_HTTP_STATUS = 2004

        private val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)

        /** `bytes=<position>-[<end>]`, or null for the whole resource. */
        internal fun rangeHeader(position: Long, length: Long): String? {
            if (position == 0L && length == C.LENGTH_UNSET.toLong()) return null
            val end = if (length != C.LENGTH_UNSET.toLong()) (position + length - 1).toString() else ""
            return "bytes=$position-$end"
        }

        /** The total after the slash of `Content-Range: bytes a-b/total`, or LENGTH_UNSET. */
        internal fun totalLengthFromContentRange(contentRange: String?): Long =
            contentRange?.substringAfterLast('/', "")?.trim()?.toLongOrNull() ?: C.LENGTH_UNSET.toLong()

        /** The span of `Content-Range: bytes a-b/total`, or LENGTH_UNSET. */
        internal fun lengthFromContentRange(contentRange: String?): Long {
            val match = contentRange?.let { CONTENT_RANGE.find(it) } ?: return C.LENGTH_UNSET.toLong()
            val first = match.groupValues[1].toLongOrNull() ?: return C.LENGTH_UNSET.toLong()
            val last = match.groupValues[2].toLongOrNull() ?: return C.LENGTH_UNSET.toLong()
            return last - first + 1
        }

        private val CONTENT_RANGE = Regex("""bytes (\d+)-(\d+)/(?:\d+|\*)""")

        /**
         * The body length from Content-Length and Content-Range; when both are
         * present and disagree, the larger, as Media3's HttpUtil does.
         */
        private fun contentLengthOf(conn: HttpURLConnection): Long {
            val fromLength = conn.getHeaderField("Content-Length")?.trim()?.toLongOrNull() ?: C.LENGTH_UNSET.toLong()
            val fromRange = lengthFromContentRange(conn.getHeaderField("Content-Range"))
            return when {
                fromLength == C.LENGTH_UNSET.toLong() -> fromRange
                fromRange == C.LENGTH_UNSET.toLong() -> fromLength
                else -> maxOf(fromLength, fromRange)
            }
        }

        private fun toUrl(spec: String): URL = try {
            URI(spec).toURL()
        } catch (e: Exception) {
            // URI is stricter than URL about unescaped characters, which some
            // radio directories hand out; URL takes them as they are.
            @Suppress("DEPRECATION")
            URL(spec)
        }
    }
}
