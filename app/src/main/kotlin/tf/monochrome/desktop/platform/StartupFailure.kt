package tf.monochrome.desktop.platform

import java.awt.GraphicsEnvironment
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.JOptionPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.system.exitProcess
import tf.monochrome.desktop.BuildConfig
import tf.monochrome.desktop.R
import tf.monochrome.desktop.res.StringKey
import tf.monochrome.desktop.res.Strings

/**
 * What the user sees when Tryptify cannot start, instead of the Windows
 * launcher's "Failed to launch JVM".
 *
 * An error out of `main()` ends the JVM with status 1, and the jpackage
 * launcher answers any non-zero status from the JVM with that one line: it
 * names no file and no reason. The usual cause on Windows is an unsigned DLL
 * that Smart App Control or an antivirus refused to load, which the user can
 * act on only once they know which file it was. So [report] writes a report
 * to the logs folder, explains the failure in the user's language, and ends
 * the process itself, before the launcher can add its own box.
 */
object StartupFailure {
    private const val FILE_PREFIX = "tryptify-startup-"
    private const val KEEP_REPORTS = 10
    private const val MAX_CAUSES = 16
    private const val MAX_DETAIL = 600

    /** `System.loadLibrary`'s message for a library that is not there at all. */
    private val MISSING = Regex("""^no (\S+) in java\.library\.path""")

    /** What the JVM appends to an initializer's error when it is rethrown later. */
    private val THREAD_SUFFIX = Regex("""\s*\[in thread "[^"]*"]$""")

    /**
     * Reports [error] and exits with status 1. [showDialog] false (the smoke
     * run) writes the report and the trace without waiting on anyone.
     */
    fun report(error: Throwable, showDialog: Boolean): Nothing {
        error.printStackTrace()
        val message = runCatching { describe(error) }.getOrElse { error.toString() }
        val file = runCatching { writeReport(error, message) }.getOrNull()
        if (showDialog && !GraphicsEnvironment.isHeadless()) {
            runCatching {
                if (SwingUtilities.isEventDispatchThread()) show(message, file)
                else SwingUtilities.invokeAndWait { show(message, file) }
            }
        }
        exitProcess(1)
    }

    /**
     * The explanation for [error]. A library that would not link is named
     * with the reason Windows gave; anything else is shown as the innermost
     * cause, which is the one that says what actually went wrong.
     */
    internal fun describe(error: Throwable): String {
        val chain = generateSequence(error) { e -> e.cause?.takeIf { it !== e } }.take(MAX_CAUSES).toList()
        val messages = chain.mapNotNull { it.message }
        val reason = NativeLibraries.failures().values.firstOrNull { r -> messages.any { r in it } }
            ?: chain.firstOrNull { it is UnsatisfiedLinkError }?.let { it.message ?: it.toString() }
            // A class whose initializer failed to link throws NoClassDefFoundError
            // from then on; the original error survives only as text in its cause.
            ?: messages.firstNotNullOfOrNull { m ->
                m.substringAfter("UnsatisfiedLinkError: ", "").takeIf { it.isNotEmpty() }?.replace(THREAD_SUFFIX, "")
            }
        if (reason == null) return string(R.string.desktop_startup_failed_other, chain.last().toString().take(MAX_DETAIL))
            ?: error.toString()
        MISSING.find(reason)?.let { match ->
            string(R.string.desktop_startup_failed_missing, NativeLibraries.fileName(match.groupValues[1]))?.let { return it }
        }
        val blocked = string(R.string.desktop_startup_failed_blocked, reason.take(MAX_DETAIL)) ?: return reason
        val security = string(R.string.desktop_startup_failed_security) ?: return blocked
        return "$blocked\n\n$security"
    }

    private fun writeReport(error: Throwable, message: String): File {
        val dir = runCatching { AppPaths().logsDir }.getOrNull()?.takeIf { it.isDirectory || it.mkdirs() }
            ?: File(System.getProperty("java.io.tmpdir"))
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val stack = StringWriter().also { sw -> PrintWriter(sw).use { error.printStackTrace(it) } }.toString()
        val failures = NativeLibraries.failures()
        val text = buildString {
            appendLine("Tryptify startup failure")
            appendLine("========================")
            appendLine("timestamp: $timestamp")
            appendLine("app:       ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("os:        ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
            appendLine("java:      ${System.getProperty("java.runtime.version") ?: System.getProperty("java.version")} (${System.getProperty("java.vendor")})")
            appendLine()
            appendLine("--- what the user was told ---")
            appendLine(message)
            appendLine()
            appendLine("--- native libraries ---")
            appendLine("searched: ${runCatching { NativeLibraries.candidateDirs().joinToString(File.pathSeparator) }.getOrDefault("?")}")
            if (failures.isEmpty()) appendLine("no load failures recorded")
            failures.forEach { (name, why) -> appendLine("$name: $why") }
            appendLine()
            appendLine("--- stack trace ---")
            appendLine(stack)
        }
        val file = File(dir, "$FILE_PREFIX$timestamp.log")
        file.writeText(text, Charsets.UTF_8)
        runCatching {
            dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(".log") }
                ?.sortedByDescending { it.name } // the timestamp in the name sorts chronologically
                ?.drop(KEEP_REPORTS)
                ?.forEach { it.delete() }
        }
        return file
    }

    /** A plain Swing dialog: Compose may be the very thing that failed to load. */
    private fun show(message: String, report: File?) {
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        val body = if (report == null) message
        else message + "\n\n" + (string(R.string.desktop_startup_failed_report, report.absolutePath) ?: report.absolutePath)
        val area = JTextArea(body).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            border = null
            UIManager.getFont("Label.font")?.let { font = it }
            columns = 50
            // A wrapping text area reports its height only once it has a width.
            setSize(preferredSize.width, 1)
        }
        val open = if (report != null) string(R.string.desktop_startup_failed_open_folder) else null
        val close = string(R.string.action_close) ?: UIManager.getString("OptionPane.okButtonText") ?: "OK"
        val options = listOfNotNull(open, close).toTypedArray()
        val choice = JOptionPane.showOptionDialog(
            null, area, string(R.string.desktop_startup_failed_title) ?: "Tryptify",
            JOptionPane.DEFAULT_OPTION, JOptionPane.ERROR_MESSAGE, null, options, close,
        )
        if (open != null && report != null && choice == 0) runCatching { DesktopActions.reveal(report) }
    }

    /** Null when the translations themselves cannot be read, so the caller can fall back to the raw error. */
    private fun string(key: StringKey, vararg args: Any?): String? = runCatching { Strings.get(key, *args) }.getOrNull()
}
