package tf.monochrome.desktop.ui.main

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import tf.monochrome.desktop.data.downloads.AppNotifier
import tf.monochrome.desktop.data.downloads.Notifier
import tf.monochrome.desktop.data.preferences.PreferencesManager
import tf.monochrome.desktop.performance.PerformanceProfile
import tf.monochrome.desktop.player.QueueManager
import tf.monochrome.desktop.ui.components.TrackSourceResolver

/**
 * What MainActivity had field-injected, for the window that replaced it.
 *
 * Desktop: an Activity was a Hilt entry point and a composable is not, and the
 * Dagger component exposes only the few roots main() needs. A ViewModel is the
 * one thing a composable can get from the graph, so the window's content takes
 * its singletons from this one, scoped to the window (`windowViewModel()`) as
 * the Activity was. It holds no state of its own.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    val preferences: PreferencesManager,
    val queueManager: QueueManager,
    val trackSourceResolver: TrackSourceResolver,
    val performanceProfile: PerformanceProfile,
    private val appNotifier: AppNotifier,
) : ViewModel() {

    /**
     * Hands the download queue's and the playlist import's notifications to
     * [notifier]. The window installs the tray here at startup; AppNotifier
     * logs them until then.
     */
    fun installNotifier(notifier: Notifier) = appNotifier.install(notifier)
}
