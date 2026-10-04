package tf.monochrome.desktop.platform

import java.io.File

/**
 * Where the desktop app keeps its files.
 *
 * Android handed every class a Context and the Context knew `filesDir` and
 * `cacheDir`. On Windows the same roles are `%LOCALAPPDATA%\Tryptify\data` and
 * `%LOCALAPPDATA%\Tryptify\cache`; downloads default to the user's Music
 * folder. On Linux (the build machine's smoke tests) the XDG directories are
 * used instead. Every directory exists by the time the object is built.
 */
class AppPaths(root: File = defaultRoot(), cacheRoot: File = defaultCacheRoot(root)) {
    /** Persistent data: database, preferences, artwork, custom fonts, HRTFs, presets. */
    val dataDir: File = root.resolve("data")
    /** Disposable data: stream caches, image cache, temp downloads. */
    val cacheDir: File = cacheRoot
    val dbFile: File = dataDir.resolve("monochrome_db")
    val prefsDir: File = dataDir.resolve("datastore")
    val artworkDir: File = dataDir.resolve("artwork")
    val fontsDir: File = dataDir.resolve("custom_fonts")
    val hrtfDir: File = dataDir.resolve("hrtf")
    val projectMDir: File = dataDir.resolve("projectm")
    val logsDir: File = dataDir.resolve("logs")
    /** Default download location; Settings can point this elsewhere. */
    val downloadsDir: File = defaultMusicDir().resolve("Tryptify")

    init {
        listOf(dataDir, cacheDir, prefsDir, artworkDir, fontsDir, hrtfDir, projectMDir, logsDir).forEach { it.mkdirs() }
    }

    companion object {
        val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

        fun defaultRoot(): File {
            System.getProperty("tryptify.home")?.takeIf { it.isNotBlank() }?.let { return File(it) }
            val local = System.getenv("LOCALAPPDATA")
            if (isWindows && !local.isNullOrBlank()) return File(local, "Tryptify")
            val xdg = System.getenv("XDG_DATA_HOME")
            val base = if (!xdg.isNullOrBlank()) File(xdg) else File(System.getProperty("user.home"), ".local/share")
            return base.resolve("tryptify")
        }

        fun defaultCacheRoot(root: File): File {
            if (isWindows) return root.resolve("cache")
            val xdg = System.getenv("XDG_CACHE_HOME")
            val base = if (!xdg.isNullOrBlank()) File(xdg) else File(System.getProperty("user.home"), ".cache")
            return base.resolve("tryptify")
        }

        fun defaultMusicDir(): File {
            val home = File(System.getProperty("user.home"))
            val music = home.resolve("Music")
            return if (music.isDirectory) music else home
        }
    }
}
