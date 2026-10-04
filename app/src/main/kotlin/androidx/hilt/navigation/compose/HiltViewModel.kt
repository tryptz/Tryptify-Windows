// Desktop stand-in for hiltViewModel(): the same call, served by the Dagger
// ViewModel factory in tf.monochrome.desktop.di. Scoping is unchanged — the
// owner defaults to LocalViewModelStoreOwner, which inside a NavHost is the
// NavBackStackEntry, so a screen's ViewModel is cleared when it leaves the
// back stack exactly as on Android.
package androidx.hilt.navigation.compose

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import tf.monochrome.desktop.di.appViewModel

@Composable
inline fun <reified VM : ViewModel> hiltViewModel(
    viewModelStoreOwner: ViewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current) {
        "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner"
    },
    key: String? = null,
): VM = appViewModel(viewModelStoreOwner, key)
