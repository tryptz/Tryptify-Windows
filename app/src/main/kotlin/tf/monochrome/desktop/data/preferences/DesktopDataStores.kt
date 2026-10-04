package tf.monochrome.desktop.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.util.concurrent.ConcurrentHashMap
import kotlin.properties.ReadOnlyProperty
import okio.Path.Companion.toOkioPath
import tf.monochrome.desktop.platform.AppPaths

/**
 * Desktop: the preferences files Android's `Context.preferencesDataStore(name)`
 * delegate used to open.
 *
 * That delegate lives in the Android-only `datastore-preferences` artifact. Here
 * every file is opened with [PreferenceDataStoreFactory.createWithPath] under
 * [AppPaths.prefsDir], which is the same layout Android used
 * (`files/datastore/<name>.preferences_pb`, here
 * `%LOCALAPPDATA%\Tryptify\data\datastore\<name>.preferences_pb`).
 *
 * DataStore must never have two instances on one file — the second throws, or
 * worse, the two overwrite each other — so instances are held here, one per
 * file path, for the life of the process. Keyed by the absolute path rather
 * than the name so a test with its own [AppPaths] gets its own file.
 */
object DesktopDataStores {
    private val stores = ConcurrentHashMap<String, DataStore<Preferences>>()

    fun get(paths: AppPaths, name: String): DataStore<Preferences> {
        val file = paths.prefsDir.resolve("$name.preferences_pb")
        return stores.computeIfAbsent(file.absolutePath) {
            file.parentFile?.mkdirs()
            PreferenceDataStoreFactory.createWithPath { file.toOkioPath() }
        }
    }
}

/**
 * The shape of Android's `preferencesDataStore(name = …)` property delegate, so
 * `private val Context.x by preferencesDataStore(name = "…")` reads the same in
 * both repos; only the import differs.
 */
fun preferencesDataStore(name: String): ReadOnlyProperty<Context, DataStore<Preferences>> =
    ReadOnlyProperty<Context, DataStore<Preferences>> { context, _ -> DesktopDataStores.get(context.paths, name) }
