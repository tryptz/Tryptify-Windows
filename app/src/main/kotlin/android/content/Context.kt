// Desktop stand-in for android.content.Context.
//
// Thirty-four injected singletons and a number of screens take a Context and use
// a small part of it: the data and cache directories, the bundled assets, the
// translated strings. This class offers exactly that part over AppPaths and the
// desktop resources, so those files keep their constructor and their calls.
// Two more pieces have a real desktop meaning and are here for that reason:
// contentResolver reads and writes the files the desktop pickers return, and
// startActivity opens links in the browser, files in Explorer and shares text
// through the clipboard. Anything else Android-only (system services,
// broadcasts) is not here on purpose: such a file needs a desktop
// implementation, not a stub that pretends.
package android.content

import android.content.res.AssetManager
import android.content.res.Resources
import java.io.File
import tf.monochrome.desktop.platform.AppPaths
import tf.monochrome.desktop.platform.DesktopActions
import tf.monochrome.desktop.res.PluralKey
import tf.monochrome.desktop.res.StringKey
import tf.monochrome.desktop.res.Strings

open class Context(val paths: AppPaths) {
    open val applicationContext: Context get() = this

    val filesDir: File get() = paths.dataDir
    val cacheDir: File get() = paths.cacheDir
    val noBackupFilesDir: File get() = paths.dataDir.resolve("no_backup").also { it.mkdirs() }
    val codeCacheDir: File get() = paths.cacheDir.resolve("code").also { it.mkdirs() }

    /** Android's app-specific external storage; on the desktop it is a data subfolder. */
    fun getExternalFilesDir(type: String?): File? =
        (if (type == null) paths.dataDir.resolve("external") else paths.dataDir.resolve("external").resolve(type)).also { it.mkdirs() }

    val externalCacheDir: File? get() = paths.cacheDir.resolve("external").also { it.mkdirs() }

    fun getDir(name: String, mode: Int = 0): File = paths.dataDir.resolve("app_$name").also { it.mkdirs() }
    fun getDatabasePath(name: String): File = paths.dataDir.resolve("databases").also { it.mkdirs() }.resolve(name)

    val assets: AssetManager = AssetManager
    val resources: Resources = Resources

    val packageName: String get() = "tf.monochrome.desktop"

    fun getString(key: StringKey): String = Strings.get(key)
    fun getString(key: StringKey, vararg formatArgs: Any?): String = Strings.get(key, *formatArgs)

    val contentResolver: ContentResolver get() = sharedResolver

    /** See [DesktopActions]: VIEW, SEND and the chooser; anything else is not found. */
    open fun startActivity(intent: Intent) = DesktopActions.start(intent)

    companion object {
        const val MODE_PRIVATE = 0
        private val sharedResolver = ContentResolver()
    }
}

/** The Android ContextWrapper shape, for code that only needs to pass a Context along. */
open class ContextWrapper(base: Context) : Context(base.paths) {
    val baseContext: Context = base
}
