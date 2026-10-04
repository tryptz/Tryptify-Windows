// Desktop stand-in for Media3's byte-source API (androidx.media3.datasource).
//
// The app's stream caches (Qobuz and Deezer partial caches, the decrypting
// source, the scheme router) are DataSources: open a DataSpec, read bytes,
// close. The desktop PlaybackEngine's decoder reads through the same interface
// (FFmpeg gets a custom AVIOContext fed by it), so these classes port
// unchanged. Independent re-implementation of the API shape, as with the
// audio shim.
package androidx.media3.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import java.io.IOException

class DataSpec @JvmOverloads constructor(
    @JvmField val uri: Uri,
    @JvmField val position: Long = 0,
    @JvmField val length: Long = C.LENGTH_UNSET.toLong(),
    @JvmField val key: String? = null,
    @JvmField val httpRequestHeaders: Map<String, String> = emptyMap(),
    @JvmField val flags: Int = 0,
    @JvmField val httpMethod: Int = HTTP_METHOD_GET,
    @JvmField val httpBody: ByteArray? = null,
    @JvmField val customData: Any? = null,
) {
    /** Media3 kept uriPositionOffset separate from position; the app only uses position. */
    @JvmField val uriPositionOffset: Long = 0

    fun buildUpon(): Builder = Builder().setUri(uri).setPosition(position).setLength(length).setKey(key)
        .setHttpRequestHeaders(httpRequestHeaders).setFlags(flags).setHttpMethod(httpMethod).setHttpBody(httpBody).setCustomData(customData)

    fun subrange(offset: Long): DataSpec = subrange(offset, if (length == C.LENGTH_UNSET.toLong()) length else length - offset)
    fun subrange(offset: Long, newLength: Long): DataSpec =
        if (offset == 0L && length == newLength) this else buildUpon().setPosition(position + offset).setLength(newLength).build()
    fun withUri(uri: Uri): DataSpec = buildUpon().setUri(uri).build()
    fun withRequestHeaders(headers: Map<String, String>): DataSpec = buildUpon().setHttpRequestHeaders(headers).build()
    fun isFlagSet(flag: Int): Boolean = flags and flag == flag

    override fun toString(): String = "DataSpec[$uri, $position, $length, $key]"

    class Builder {
        private var uri: Uri? = null
        private var position = 0L
        private var length = C.LENGTH_UNSET.toLong()
        private var key: String? = null
        private var headers: Map<String, String> = emptyMap()
        private var flags = 0
        private var httpMethod = HTTP_METHOD_GET
        private var httpBody: ByteArray? = null
        private var customData: Any? = null

        fun setUri(uri: Uri): Builder = apply { this.uri = uri }
        fun setUri(uri: String): Builder = apply { this.uri = Uri.parse(uri) }
        fun setPosition(position: Long): Builder = apply { this.position = position }
        fun setLength(length: Long): Builder = apply { this.length = length }
        fun setKey(key: String?): Builder = apply { this.key = key }
        fun setHttpRequestHeaders(headers: Map<String, String>): Builder = apply { this.headers = headers }
        fun setFlags(flags: Int): Builder = apply { this.flags = flags }
        fun setHttpMethod(method: Int): Builder = apply { this.httpMethod = method }
        fun setHttpBody(body: ByteArray?): Builder = apply { this.httpBody = body }
        fun setCustomData(data: Any?): Builder = apply { this.customData = data }
        fun build(): DataSpec = DataSpec(checkNotNull(uri) { "DataSpec needs a uri" }, position, length, key, headers, flags, httpMethod, httpBody, customData)
    }

    companion object {
        const val HTTP_METHOD_GET = 1
        const val HTTP_METHOD_POST = 2
        const val HTTP_METHOD_HEAD = 3
        const val FLAG_ALLOW_GZIP = 1
        const val FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN = 1 shl 1
        const val FLAG_ALLOW_CACHE_FRAGMENTATION = 1 shl 2
        const val FLAG_MIGHT_NOT_USE_FULL_NETWORK_SPEED = 1 shl 3
    }
}

interface TransferListener {
    fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
    fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
    fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {}
    fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
}

interface DataSource : DataReader {
    fun interface Factory { fun createDataSource(): DataSource }

    fun addTransferListener(transferListener: TransferListener)

    /** Opens the source; returns the number of bytes available from the position, or C.LENGTH_UNSET. */
    @Throws(IOException::class)
    fun open(dataSpec: DataSpec): Long

    val uri: Uri?
    val responseHeaders: Map<String, List<String>> get() = emptyMap()

    @Throws(IOException::class)
    fun close()
}

/** Media3's base: transfer-listener bookkeeping for subclasses. */
abstract class BaseDataSource(private val isNetwork: Boolean) : DataSource {
    private val listeners = ArrayList<TransferListener>(1)
    private var dataSpec: DataSpec? = null

    override fun addTransferListener(transferListener: TransferListener) {
        if (transferListener !in listeners) listeners.add(transferListener)
    }

    protected fun transferInitializing(dataSpec: DataSpec) {
        for (l in listeners) l.onTransferInitializing(this, dataSpec, isNetwork)
    }

    protected fun transferStarted(dataSpec: DataSpec) {
        this.dataSpec = dataSpec
        for (l in listeners) l.onTransferStart(this, dataSpec, isNetwork)
    }

    protected fun bytesTransferred(bytesTransferred: Int) {
        val spec = dataSpec ?: return
        for (l in listeners) l.onBytesTransferred(this, spec, isNetwork, bytesTransferred)
    }

    protected fun transferEnded() {
        val spec = dataSpec ?: return
        for (l in listeners) l.onTransferEnd(this, spec, isNetwork)
        dataSpec = null
    }
}

open class DataSourceException(message: String?, cause: Throwable?, @JvmField val reason: Int) : IOException(message, cause) {
    constructor(reason: Int) : this(null, null, reason)
    constructor(cause: Throwable?, reason: Int) : this(null, cause, reason)
    constructor(message: String?, reason: Int) : this(message, null, reason)

    companion object {
        const val POSITION_OUT_OF_RANGE = 2008
    }
}

/** A DataSource over a local file, the desktop equivalent of FileDataSource. */
class FileDataSource : BaseDataSource(false) {
    private var file: java.io.RandomAccessFile? = null
    private var remaining = 0L
    private var opened = false
    override var uri: Uri? = null
        private set

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val path = dataSpec.uri.path ?: throw IOException("file uri has no path: ${dataSpec.uri}")
        val raf = java.io.RandomAccessFile(java.io.File(path), "r")
        file = raf
        raf.seek(dataSpec.position)
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) raf.length() - dataSpec.position else dataSpec.length
        if (remaining < 0) throw DataSourceException(DataSourceException.POSITION_OUT_OF_RANGE)
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val n = file!!.read(buffer, offset, minOf(remaining, length.toLong()).toInt())
        if (n > 0) { remaining -= n; bytesTransferred(n) }
        return if (n < 0) C.RESULT_END_OF_INPUT else n
    }

    override fun close() {
        uri = null
        try { file?.close() } finally {
            file = null
            if (opened) { opened = false; transferEnded() }
        }
    }
}
