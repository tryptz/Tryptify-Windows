package tf.monochrome.desktop.platform

import java.awt.GraphicsEnvironment
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.JOptionPane
import kotlin.system.exitProcess

/**
 * Desktop: the part of starting up that Android never had to think about,
 * because Android loaded every native library from the APK and showed its own
 * crash dialog when the Application failed.
 *
 * On Windows the app runs under jpackage's launcher. That launcher knows one
 * thing about the Java side: the exit code. Whenever it is not zero, for any
 * reason, it shows "Failed to launch JVM" (JvmLauncher.cpp in the JDK). So an
 * exception in `main()` before the window exists, say an `UnsatisfiedLinkError`
 * because Windows refused to load a DLL, is reported as a JVM that would not
 * start, with the actual cause lost. Two things here keep that from happening.
 *
 * [configureNativeExtraction] decides where the libraries that are not shipped
 * as files get unpacked. Skiko's DLL is in the install folder (the Compose
 * plugin passes `-Dskiko.library.path=$APPDIR`), and ours are loaded by path
 * (see [NativeLibraries]). But FFmpeg (JavaCPP) unpacks itself into
 * `%USERPROFILE%\.javacpp\cache` and LWJGL into `%TEMP%\lwjgl<user>`. Those are
 * exactly the places Windows security watches most closely: Smart App Control
 * and attack-surface-reduction rules refuse to load unsigned DLLs that a
 * process has just written under the profile or the temp folder, and Defender
 * quarantines from them freely. Unpacking under the app's own cache folder
 * instead gives the user one folder to exclude, keeps the DLLs from being
 * mistaken for a drive-by, and makes the next error message name a path that
 * is obviously ours.
 *
 * [reportStartupFailure] replaces the launcher's dialog with the truth: the
 * exception, written to `logs\startup-crash.log` and shown in a dialog, and
 * then an exit code of zero so the launcher stays quiet. Zero only under the
 * launcher (it sets `jpackage.app-path`); a terminal or the smoke test still
 * sees a failing exit code and the trace on stderr.
 */
object Startup {

    /** Called before any class that might touch JavaCPP or LWJGL is loaded. */
    fun configureNativeExtraction(paths: AppPaths) {
        val natives = paths.cacheDir.resolve("natives")
        // Only when nothing chose otherwise: a developer may want the shared
        // ~/.javacpp cache, and the launcher's .cfg could pin a path too.
        setDefault("org.bytedeco.javacpp.cachedir", natives.resolve("javacpp"))
        setDefault("org.lwjgl.system.SharedLibraryExtractPath", natives.resolve("lwjgl"))
    }

    private fun setDefault(key: String, dir: File) {
        if (System.getProperty(key).isNullOrBlank()) {
            runCatching { dir.mkdirs() }
            System.setProperty(key, dir.absolutePath)
        }
    }

    /** Never returns. */
    fun reportStartupFailure(error: Throwable, paths: AppPaths?): Nothing {
        val trace = StringWriter().also { PrintWriter(it).use(error::printStackTrace) }.toString()
        System.err.println("Tryptify failed to start")
        System.err.print(trace)

        val log = paths?.let { writeLog(it, trace) }
        // Only the launcher has no console to print to; from Gradle or a
        // terminal the trace above is the report, and a modal dialog would
        // hang the smoke test.
        val underLauncher = !System.getProperty("jpackage.app-path").isNullOrBlank()
        if (underLauncher && !GraphicsEnvironment.isHeadless()) {
            runCatching {
                JOptionPane.showMessageDialog(
                    null,
                    message(error, log),
                    "Tryptify could not start",
                    JOptionPane.ERROR_MESSAGE,
                )
            }
        }
        // Under the launcher our dialog has said what went wrong; a non-zero
        // exit would only add "Failed to launch JVM" on top of it.
        exitProcess(if (underLauncher) 0 else 1)
    }

    private fun writeLog(paths: AppPaths, trace: String): File? = runCatching {
        val dir = paths.logsDir.also { it.mkdirs() }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val header = buildString {
            appendLine("Tryptify startup failure, $stamp")
            appendLine("os:   ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
            appendLine("java: ${System.getProperty("java.runtime.version")} at ${System.getProperty("java.home")}")
            appendLine("app:  ${System.getProperty("jpackage.app-path") ?: "(not under the launcher)"}")
            appendLine("javacpp cache: ${System.getProperty("org.bytedeco.javacpp.cachedir")}")
            appendLine("lwjgl extract: ${System.getProperty("org.lwjgl.system.SharedLibraryExtractPath")}")
            appendLine()
        }
        dir.resolve(LOG_NAME).also { it.writeText(header + trace) }
    }.getOrNull()

    private fun message(error: Throwable, log: File?): String {
        val root = generateSequence(error) { it.cause }.last()
        val blockedDll = root is UnsatisfiedLinkError || root.message.orEmpty().contains(".dll", ignoreCase = true)
        return buildString {
            appendLine("Tryptify could not start.")
            appendLine()
            appendLine("${root.javaClass.simpleName}: ${root.message ?: "(no message)"}")
            appendLine()
            if (blockedDll) {
                appendLine("A native library (DLL) would not load. Windows security often causes this:")
                appendLine("Smart App Control, Defender, or an exploit-protection rule refused a DLL")
                appendLine("because it is unsigned. Check Windows Security > Protection history, or")
                appendLine("Event Viewer > Applications and Services Logs > Microsoft > Windows >")
                appendLine("CodeIntegrity > Operational, then allow the file or exclude the app's folders.")
                appendLine()
            }
            if (log != null) appendLine("Details were written to:\n${log.absolutePath}")
        }
    }

    private const val LOG_NAME = "startup-crash.log"
}
