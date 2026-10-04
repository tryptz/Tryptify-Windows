package tf.monochrome.desktop.data.local.watcher

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tf.monochrome.desktop.data.local.scanner.ScanCoordinator
import java.io.File
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/**
 * Watches known music directories for file changes in real time.
 * Debounces events and triggers incremental scans when files change.
 *
 * Desktop: Android's `FileObserver` (inotify on one directory per observer)
 * becomes `java.nio.file.WatchService`. On Windows one registration watches a
 * whole tree (`ExtendedWatchEventModifier.FILE_TREE`, ReadDirectoryChangesW
 * with subtree); elsewhere every directory under the roots is registered and
 * new subdirectories join as they appear. One daemon thread blocks on the
 * service and turns events into the same 500 ms debounced incremental scan.
 * It is not an Android Service and never was one; the name is kept.
 *
 * A folder created or moved into a watched tree also triggers a scan: on
 * Android a moved-in album folder fired one event for the folder, whose name
 * has no audio extension, and was ignored until the next manual scan.
 */
@Singleton
class FileObserverService @Inject constructor(
    private val scanCoordinator: ScanCoordinator
) {
    // SupervisorJob so one failed incremental scan doesn't cancel the whole
    // scope and silently kill file watching for the rest of the session.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var debounceJob: Job? = null
    @Volatile private var isRunning = false
    private var watchService: WatchService? = null
    private var pump: Thread? = null

    /** Each registration's directory, to resolve the relative names events carry. */
    private val keys = ConcurrentHashMap<WatchKey, Path>()
    /** Directories registered one by one (the non-Windows path), to recognise a deleted folder. */
    private val watchedDirs: MutableSet<Path> = ConcurrentHashMap.newKeySet()
    /** True when the roots are watched as whole trees (Windows). */
    @Volatile private var treeMode = false

    fun startWatching(directories: List<String>) {
        synchronized(lock) {
            if (isRunning) return
            val service = try {
                FileSystems.getDefault().newWatchService()
            } catch (e: IOException) {
                Log.w(TAG, "file watching unavailable", e)
                return
            }
            isRunning = true
            watchService = service
            treeMode = FILE_TREE != null

            for (dirPath in directories) {
                val dir = File(dirPath)
                if (!dir.exists() || !dir.isDirectory) continue
                register(service, dir.toPath().toAbsolutePath().normalize())
            }
            pump = thread(name = "library-watcher", isDaemon = true) { pumpEvents(service) }
        }
    }

    fun stopWatching() {
        synchronized(lock) {
            isRunning = false
            debounceJob?.cancel()
            // Closing the service wakes the pump thread out of take().
            runCatching { watchService?.close() }
            watchService = null
            pump = null
            keys.clear()
            watchedDirs.clear()
        }
    }

    private fun register(service: WatchService, root: Path) {
        val modifier = FILE_TREE
        if (modifier != null) {
            try {
                keys[root.register(service, EVENT_KINDS, modifier)] = root
                return
            } catch (_: UnsupportedOperationException) {
                // Not Windows after all: fall through to one key per folder.
                treeMode = false
            } catch (e: IOException) {
                Log.w(TAG, "cannot watch $root", e)
                return
            }
        }
        registerTree(service, root)
    }

    /** Registers [root] and every folder below it, skipping hidden ones as the scan does. */
    private fun registerTree(service: WatchService, root: Path) {
        try {
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = dir.fileName?.toString().orEmpty()
                    if (dir != root && (name.startsWith('.') || name.startsWith('$'))) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    try {
                        keys[dir.register(service, *EVENT_KINDS)] = dir
                        watchedDirs.add(dir) // not +=: a Path is an Iterable<Path>
                    } catch (e: IOException) {
                        Log.w(TAG, "cannot watch $dir", e)
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                    FileVisitResult.CONTINUE
            })
        } catch (e: IOException) {
            Log.w(TAG, "cannot watch $root", e)
        } catch (_: ClosedWatchServiceException) {
            // Stopped while registering.
        }
    }

    private fun pumpEvents(service: WatchService) {
        while (isRunning) {
            val key = try {
                service.take()
            } catch (_: InterruptedException) {
                break
            } catch (_: ClosedWatchServiceException) {
                break
            }
            val dir = keys[key]
            var relevant = false
            for (event in key.pollEvents()) {
                val kind = event.kind()
                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    // Events were dropped; whatever they were, a scan finds it.
                    relevant = true
                    continue
                }
                val name = event.context() as? Path ?: continue
                val child = dir?.resolve(name) ?: continue
                if (isRelevant(service, kind, child)) relevant = true
            }
            if (!key.reset()) {
                keys.remove(key)
                dir?.let { watchedDirs.remove(it) }
            }
            if (relevant) scheduleScan()
        }
    }

    private fun isRelevant(service: WatchService, kind: WatchEvent.Kind<*>, child: Path): Boolean {
        val ext = child.fileName?.toString()?.substringAfterLast('.', "")?.lowercase().orEmpty()
        // Only react to audio files
        if (ext in audioExtensions) return true
        if (kind == StandardWatchEventKinds.ENTRY_CREATE &&
            Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
        ) {
            // A folder copied or moved in brings its tracks with it, and on
            // the per-folder path it needs watching itself.
            if (!treeMode) registerTree(service, child)
            return true
        }
        // A folder deleted or moved away takes its tracks with it.
        return kind == StandardWatchEventKinds.ENTRY_DELETE && (child in watchedDirs || (treeMode && ext.isEmpty()))
    }

    private fun scheduleScan() {
        synchronized(lock) {
            if (!isRunning) return
            // Debounce: wait 500ms after last event before scanning.
            // Catch exceptions explicitly — an unhandled throw from
            // incrementalScan() would reach the scope's handler even with
            // the SupervisorJob above.
            debounceJob?.cancel()
            debounceJob = scope.launch {
                try {
                    delay(500)
                    scanCoordinator.runIncrementalScan()
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (t: Throwable) {
                    Log.w(TAG, "incremental scan failed, will retry on next event", t)
                }
            }
        }
    }

    companion object {
        private const val TAG = "FileObserverService"

        private val EVENT_KINDS: Array<WatchEvent.Kind<Path>> = arrayOf(
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_DELETE,
            StandardWatchEventKinds.ENTRY_MODIFY,
        )

        /**
         * Windows' whole-tree watch, from the JDK's `jdk.unsupported` module.
         * Looked up reflectively so a runtime image without that module, or
         * another OS, falls back to per-folder registration.
         */
        private val FILE_TREE: WatchEvent.Modifier? = runCatching {
            if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) return@runCatching null
            Class.forName("com.sun.nio.file.ExtendedWatchEventModifier")
                .getField("FILE_TREE").get(null) as WatchEvent.Modifier
        }.getOrNull()

        // Broad coverage so a file drop into a watched folder in any of the
        // common audio containers triggers a rescan. The scanner downstream
        // (MediaStoreSource + TagReader) decides whether we can actually
        // identify / play the file; dropping it here based on extension alone
        // hides hi-res / lossless formats even before we've seen them.
        private val audioExtensions = setOf(
            // Lossy
            "mp3", "aac", "m4a", "m4b", "m4p", "3gp",
            "ogg", "oga", "opus",
            "wma", "mpc", "mp2", "mp1",
            // Lossless
            "flac", "alac", "wav", "wave", "w64",
            "aif", "aiff", "aifc",
            "ape", "tak", "wv", "tta", "shn",
            // High-res / DSD / niche
            "dsf", "dff", "dsd",
            // Container-only (codec inside)
            "mka", "caf", "mpd",
            // Misc
            "au", "amr", "ra", "rm",
            // Desktop: what the folder scan also admits — Dolby/DTS streams,
            // and the video containers it takes Atmos audio from.
            "spx", "rf64", "ac3", "ec3", "eac3", "dts",
            "mp4", "m4v", "mov", "mkv", "webm", "ts", "m2ts", "mts",
        )
    }
}
