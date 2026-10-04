package tf.monochrome.desktop.platform

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FilenameFilter
import javax.swing.JFileChooser

/**
 * Native open/save dialogs for the pickers Android's Storage Access Framework
 * provided. Files use java.awt.FileDialog, which is the real Windows common
 * dialog; folders use JFileChooser, because the AWT dialog cannot pick a
 * folder on Windows. The last folder visited is remembered for the session.
 */
object FilePickers {
    /** The main window, set by main(); dialogs are modal to it. */
    @Volatile var owner: Frame? = null

    private var lastDir: File? = null
    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    fun openFile(mimeTypes: List<String>): File? {
        val dialog = FileDialog(owner, null, FileDialog.LOAD)
        applyFilter(dialog, extensionsFor(mimeTypes), suggested = null)
        return show(dialog)
    }

    fun saveFile(suggestedName: String, mimeType: String): File? {
        val dialog = FileDialog(owner, null, FileDialog.SAVE)
        applyFilter(dialog, extensionsFor(listOf(mimeType)), suggested = suggestedName)
        return show(dialog)
    }

    fun chooseFolder(): File? {
        val chooser = JFileChooser(lastDir).apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        return if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile?.also { lastDir = it }
        } else null
    }

    private fun show(dialog: FileDialog): File? {
        lastDir?.let { dialog.directory = it.absolutePath }
        dialog.isVisible = true
        val name = dialog.file ?: return null
        val dir = dialog.directory ?: return null
        return File(dir, name).also { lastDir = it.parentFile }
    }

    /**
     * The Windows dialog ignores FilenameFilter and takes its filter from the
     * file field instead ("*.json;*.txt"); other platforms honour the filter.
     */
    private fun applyFilter(dialog: FileDialog, extensions: List<String>, suggested: String?) {
        if (extensions.isEmpty()) {
            suggested?.let { dialog.file = it }
            return
        }
        dialog.filenameFilter = FilenameFilter { _, name -> extensions.any { name.endsWith(".$it", ignoreCase = true) } }
        dialog.file = suggested ?: if (windows) extensions.joinToString(";") { "*.$it" } else null
    }

    /** MIME types the app passes, mapped to extensions; anything unknown shows every file. */
    internal fun extensionsFor(mimeTypes: List<String>): List<String> {
        if (mimeTypes.any { it == "*/*" || it.isBlank() }) return emptyList()
        return mimeTypes.flatMap { mime ->
            when (mime.lowercase()) {
                "application/json" -> listOf("json")
                "text/plain" -> listOf("txt")
                "text/csv", "text/comma-separated-values" -> listOf("csv")
                "text/*" -> listOf("txt", "csv", "tsv", "json")
                "audio/x-mpegurl", "audio/mpegurl", "application/vnd.apple.mpegurl" -> listOf("m3u", "m3u8")
                "font/ttf", "font/otf", "font/*", "application/x-font-ttf", "application/font-sfnt" -> listOf("ttf", "otf")
                "application/x-hdf5", "application/x-sofa" -> listOf("sofa")
                "audio/*" -> listOf("flac", "wav", "mp3", "m4a", "aac", "ogg", "opus", "alac", "aiff", "aif", "wv", "ape", "dsf", "dff")
                else -> emptyList()
            }
        }.distinct().let { exts -> if (mimeTypes.any { it.lowercase() !in KNOWN }) emptyList() else exts }
    }

    private val KNOWN = setOf(
        "application/json", "text/plain", "text/csv", "text/comma-separated-values", "text/*",
        "audio/x-mpegurl", "audio/mpegurl", "application/vnd.apple.mpegurl",
        "font/ttf", "font/otf", "font/*", "application/x-font-ttf", "application/font-sfnt",
        "application/x-hdf5", "application/x-sofa", "audio/*",
    )
}
