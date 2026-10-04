package tf.monochrome.desktop.share

import tf.monochrome.desktop.R
import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.net.toFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tf.monochrome.desktop.data.cache.QobuzStreamCacheManager
import tf.monochrome.desktop.data.db.dao.DownloadDao
import tf.monochrome.desktop.data.db.entity.DownloadedTrackEntity
import tf.monochrome.desktop.domain.model.AudioQuality
import tf.monochrome.desktop.domain.model.Track
import tf.monochrome.desktop.platform.DesktopActions
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands a track's actual audio file to the user so they can pass the FLAC (or
 * MP3) on to anything else.
 *
 * Android: an `Intent.ACTION_SEND` with the file behind a FileProvider URI,
 * into the system share sheet. Desktop: Windows has no share sheet a JVM app
 * can reach, so "share" does what sharing a file means on a PC — the file is
 * put on the clipboard (as the file itself, so pasting into Explorer, a chat
 * app or an e-mail attaches it, and as its path, so pasting into a text field
 * gives the location) and revealed, selected, in Explorer.
 *
 * Resolution order:
 *   1. DownloadedTrackEntity.filePath — the user-initiated download (always
 *      an absolute path on the desktop; a `file:` URI is accepted too).
 *   2. QobuzStreamCacheManager — for tracks that have been streamed
 *      through the cache-on-demand path.
 *
 * If neither source has a file, the track is fetched into the playback cache
 * first; failing that, a toast says there is nothing to share.
 */
@Singleton
class TrackShareHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadDao: DownloadDao,
    private val qobuzCache: QobuzStreamCacheManager,
) {
    /** Suspends on the DB lookup and, when nothing is local, on the fetch. */
    suspend fun shareTrack(track: Track): Boolean = withContext(Dispatchers.IO) {
        val downloaded = runCatching { downloadDao.getDownloadedTrack(track.id) }.getOrNull()
        if (downloaded != null) {
            return@withContext shareDownloadedTrack(downloaded)
        }
        // Already cached from a previous play? Try the qualities we save under
        // — LOSSLESS first, then HI_RES, since the cache key encodes (id,
        // quality) and we don't know which qualities the user has played at.
        for (quality in arrayOf(AudioQuality.LOSSLESS, AudioQuality.HI_RES, AudioQuality.HIGH, AudioQuality.LOW)) {
            val cached = runCatching { qobuzCache.peekCached(track.id, quality) }.getOrNull()
            if (cached != null) {
                return@withContext shareLocalFile(cached, track.title)
            }
        }
        // Download-on-demand: nothing local, so reach out to the Qobuz
        // instance and pull the file into the playback cache, then share
        // from there. This may take several seconds for a typical FLAC —
        // surface a Toast so the user knows the click registered.
        // PlayerViewModel.shareTrack drives this from viewModelScope so
        // the fetch outlives the originating click.
        toast(text().getString(R.string.share_preparing, track.title))
        val fetched = runCatching {
            qobuzCache.getOrFetch(track.id, AudioQuality.LOSSLESS)
        }.getOrNull()
        if (fetched != null) {
            return@withContext shareLocalFile(fetched, track.title)
        }
        toast(text().getString(R.string.share_no_file))
        Log.i(TAG, "shareTrack: no local file and download-on-demand failed for trackId=${track.id}")
        false
    }

    /** The app context, in the app's language (one process-wide language on the desktop). */
    private fun text(): Context = tf.monochrome.desktop.locale.AppLanguage.wrap(context)

    // Desktop: Toasts post from any thread, so there is no hop to a main dispatcher.
    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun shareDownloadedTrack(entity: DownloadedTrackEntity): Boolean {
        val file = localFile(entity.filePath) ?: return noFile(entity.title)
        return shareLocalFile(file, entity.title)
    }

    /**
     * Share a file we already hold the path (or `file:` URI) for — the local
     * library case. [shareTrack] can't serve it: it looks for a download row
     * and then a Qobuz cache entry, finds neither for a scanned file, and
     * falls through to *fetching* the track from the Qobuz instance.
     */
    fun shareLocalPath(pathOrUri: String, title: String): Boolean {
        val file = localFile(pathOrUri) ?: return noFile(title)
        return shareLocalFile(file, title)
    }

    /**
     * A path or `file:` URI as an existing file. Desktop: a `content://` value
     * (an Android SAF document) names nothing on this machine and yields null.
     */
    private fun localFile(pathOrUri: String): File? = runCatching {
        when {
            pathOrUri.startsWith("file:", ignoreCase = true) -> Uri.parse(pathOrUri).toFile()
            pathOrUri.startsWith("content://") -> null
            else -> File(pathOrUri)
        }
    }.getOrNull()?.takeIf { it.isFile }

    private fun shareLocalFile(file: File, title: String): Boolean {
        if (!file.isFile) return noFile(title)
        return launchShare(file, title)
    }

    private fun noFile(title: String): Boolean {
        toast(text().getString(R.string.share_no_file))
        Log.i(TAG, "share: no file on disk for \"$title\"")
        return false
    }

    /**
     * The desktop share: the file on the clipboard, then Explorer with it
     * selected. Either half succeeding counts — the clipboard can be held by
     * another program for a moment, and a machine without a file manager
     * (a headless Linux build box) still gets the clipboard.
     */
    private fun launchShare(file: File, title: String): Boolean {
        val copied = runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(FileSelection(file), null)
            true
        }.onFailure { Log.w(TAG, "share: clipboard unavailable for \"$title\"", it) }.getOrDefault(false)
        val revealed = runCatching {
            DesktopActions.reveal(file)
            true
        }.onFailure { Log.w(TAG, "share: could not reveal ${file.absolutePath}", it) }.getOrDefault(false)
        if (copied) toast(text().getString(R.string.desktop_copied_to_clipboard))
        return copied || revealed
    }

    /** A file on the clipboard: Explorer and chat apps take the file, text fields its path. */
    private class FileSelection(private val file: File) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> =
            arrayOf(DataFlavor.javaFileListFlavor, DataFlavor.stringFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
            flavor == DataFlavor.javaFileListFlavor || flavor == DataFlavor.stringFlavor

        override fun getTransferData(flavor: DataFlavor): Any = when (flavor) {
            DataFlavor.javaFileListFlavor -> listOf(file)
            DataFlavor.stringFlavor -> file.absolutePath
            else -> throw UnsupportedFlavorException(flavor)
        }
    }

    companion object {
        private const val TAG = "TrackShareHelper"
    }
}
