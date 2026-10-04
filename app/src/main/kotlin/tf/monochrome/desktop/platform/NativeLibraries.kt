package tf.monochrome.desktop.platform

import android.util.Log
import java.io.File

/**
 * Finds and loads the native libraries the engine needs.
 *
 * On Android `System.loadLibrary("monochrome_dsp")` found the .so inside the
 * APK. On the desktop the libraries sit in a folder the installer ships
 * (`compose.application.resources.dir`, the Compose packaging convention) or,
 * during development, under `app/resources/<os>-<arch>`.
 *
 * `System.loadLibrary` only searches `java.library.path`, which is fixed at
 * JVM start, and a library already loaded by absolute path does not satisfy
 * it. So every loader ported from Android calls [load] instead: it resolves
 * the file in [candidateDirs], loads it by path once, and falls back to
 * `loadLibrary` so a `-Djava.library.path` still works. It throws the same
 * [UnsatisfiedLinkError] `loadLibrary` would, which keeps the loaders'
 * existing error handling meaningful.
 */
object NativeLibraries {
    private const val TAG = "NativeLibraries"

    /** Libraries in dependency order; missing ones are reported, not fatal. */
    val NAMES = listOf(
        "monochrome_dsp", "monochrome_stretch", "monochrome_atmos_jni",
        "monochrome_usb", "monochrome_wasapi", "monochrome_visualizer",
    )

    private val loaded = LinkedHashMap<String, Boolean>()

    val platformFolder: String by lazy {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        val osPart = when {
            os.startsWith("windows") -> "windows"
            os.startsWith("mac") -> "macos"
            else -> "linux"
        }
        val archPart = if (arch == "aarch64" || arch == "arm64") "arm64" else "x64"
        "$osPart-$archPart"
    }

    /** Candidate directories, most specific first. */
    fun candidateDirs(): List<File> {
        val dirs = ArrayList<File>()
        System.getProperty("tryptify.native.dir")?.takeIf { it.isNotBlank() }?.let { dirs += File(it) }
        System.getProperty("compose.application.resources.dir")?.takeIf { it.isNotBlank() }?.let { dirs += File(it) }
        val cwd = File(System.getProperty("user.dir"))
        dirs += cwd.resolve("app/resources/$platformFolder")
        dirs += cwd.resolve("resources/$platformFolder")
        dirs += cwd.resolve("../app/resources/$platformFolder")
        return dirs.filter { it.isDirectory }.map { it.absoluteFile }.distinct()
    }

    fun fileName(name: String): String =
        if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) "$name.dll"
        else if (System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)) "lib$name.dylib"
        else "lib$name.so"

    /** Loads [name] once; throws [UnsatisfiedLinkError] when it cannot be found or linked. */
    @Synchronized
    fun load(name: String) {
        if (loaded[name] == true) return
        val file = candidateDirs().asSequence().map { it.resolve(fileName(name)) }.firstOrNull { it.isFile }
        if (file != null) System.load(file.absolutePath) else System.loadLibrary(name)
        loaded[name] = true
    }

    /**
     * Loads every library up front, off the UI thread, so the first class
     * that needs one does not pay for the link on its own thread. Missing
     * libraries are logged, not fatal: the features behind them report
     * themselves unavailable, as they did on Android.
     */
    @Synchronized
    fun preload(): Map<String, Boolean> {
        for (name in NAMES) {
            if (loaded[name] == true) continue
            loaded[name] = try {
                load(name)
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "$name unavailable: ${e.message}")
                false
            }
        }
        Log.i(TAG, "native libraries: ${loaded.entries.joinToString { "${it.key}=${if (it.value) "ok" else "missing"}" }}")
        return loaded.toMap()
    }

    @Synchronized
    fun isLoaded(name: String): Boolean = loaded[name] == true
}
