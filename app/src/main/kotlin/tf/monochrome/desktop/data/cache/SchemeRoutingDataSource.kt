package tf.monochrome.desktop.data.cache

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Picks a [DataSource] by URI scheme at open time: the app's own `qobuz://`
 * URIs go to [qobuz], `deezer://` to [deezer], everything else (file,
 * content, asset, http) to [default].
 *
 * DefaultDataSource has a closed set of schemes and no extension point, so this
 * sits in front of it rather than trying to replace it — the standard schemes
 * keep their standard handling.
 *
 * Desktop: there is no DefaultDataSource. The engine hands file paths and
 * http(s) URLs straight to libavformat and only opens through a DataSource for
 * the schemes that need one of the app's own sources, so [default] is the
 * fallback for anything else: [HttpDataSource] (the desktop DefaultHttpDataSource)
 * or the shim's FileDataSource, whichever the caller expects.
 */
@UnstableApi
class SchemeRoutingDataSource(
    private val default: DataSource,
    private val qobuz: DataSource,
    private val deezer: DataSource,
) : DataSource {

    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        // Registered on all: which one handles a given open() isn't known yet,
        // and the bandwidth meter needs the events either way.
        default.addTransferListener(transferListener)
        qobuz.addTransferListener(transferListener)
        deezer.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val target = dataSpec.uri.toString()
        val source = when {
            QobuzPartialDataSource.isQobuzUri(target) -> qobuz
            DeezerPartialDataSource.isDeezerUri(target) -> deezer
            else -> default
        }
        active = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        requireNotNull(active) { "read() before open()" }.read(buffer, offset, length)

    // Desktop: the DataSource shim exposes Media3's getUri()/getResponseHeaders()
    // as Kotlin properties.
    override val uri: Uri? get() = active?.uri

    override val responseHeaders: Map<String, List<String>>
        get() = active?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }
}
