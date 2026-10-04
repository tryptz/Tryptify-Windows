// Desktop stand-in for the parts of android.content.res the ported code uses:
// AssetManager.open over the bundled resources/assets tree, and
// Resources.getQuantityString/getString over the translation tables.
package android.content.res

import java.io.FileNotFoundException
import java.io.InputStream
import tf.monochrome.desktop.res.PluralKey
import tf.monochrome.desktop.res.StringKey
import tf.monochrome.desktop.res.Strings

object AssetManager {
    /** Opens `assets/<name>` from the classpath; throws like Android when it is missing. */
    @Throws(FileNotFoundException::class)
    fun open(fileName: String): InputStream =
        AssetManager::class.java.getResourceAsStream("/assets/${fileName.trimStart('/')}")
            ?: throw FileNotFoundException("asset not bundled: $fileName")

    /** Lists the entries under an assets folder, from an index written at build time when present. */
    fun list(path: String): Array<String> {
        val index = AssetManager::class.java.getResourceAsStream("/assets/${path.trim('/')}/.index")
            ?: return emptyArray()
        return index.bufferedReader().use { r -> r.readLines().filter { it.isNotBlank() }.toTypedArray() }
    }
}

object Resources {
    fun getString(key: StringKey): String = Strings.get(key)
    fun getString(key: StringKey, vararg formatArgs: Any?): String = Strings.get(key, *formatArgs)
    fun getQuantityString(key: PluralKey, quantity: Int): String = Strings.plural(key, quantity)
    fun getQuantityString(key: PluralKey, quantity: Int, vararg formatArgs: Any?): String = Strings.plural(key, quantity, *formatArgs)
}
