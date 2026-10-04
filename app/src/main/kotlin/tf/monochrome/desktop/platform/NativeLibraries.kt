package tf.monochrome.desktop.platform

import android.util.Log
import java.io.File

/**
 * Finds and loads the native libraries the engine needs.
 *
 * On Android `System.loadLibrary("monochrome_dsp")` found the .so inside the
 * APK. On the desktop the libraries sit in a folder the installer ships
 * (`compose.application.resources.dir`, the Compose packaging convention) or,
 * during development, under `app/resources/<os>-<arch>`. [preload] loads every
 * library it finds by absolute path, once, before any class with a
 * `System.loadLibrary` initialiser runs; a later `loadLibrary` of the same
 * library is then a no-op, so the loaders ported from Android stay unchanged.
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

    @Synchronized
    fun preload(): Map<String, Boolean> {
        if (loaded.isNotEmpty()) return loaded
        val dirs = candidateDirs()
        for (name in NAMES) {
            val file = dirs.asSequence().map { it.resolve(fileName(name)) }.firstOrNull { it.isFile }
            loaded[name] = if (file == null) {
                Log.w(TAG, "$name not found in ${dirs.joinToString()}")
                false
            } else {
                try {
                    System.load(file.absolutePath)
                    true
                } catch (e: UnsatisfiedLinkError) {
                    Log.e(TAG, "failed to load ${file.absolutePath}", e)
                    false
                }
            }
        }
        Log.i(TAG, "native libraries: ${loaded.entries.joinToString { "${it.key}=${if (it.value) "ok" else "missing"}" }}")
        return loaded
    }

    fun isLoaded(name: String): Boolean = loaded[name] == true
}
