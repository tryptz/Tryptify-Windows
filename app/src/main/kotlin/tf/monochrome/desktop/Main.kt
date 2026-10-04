package tf.monochrome.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.delay
import tf.monochrome.desktop.di.DaggerAppComponent
import tf.monochrome.desktop.di.LocalAppComponent
import tf.monochrome.desktop.platform.AppPaths
import tf.monochrome.desktop.platform.FilePickers
import tf.monochrome.desktop.platform.ToastHost
import tf.monochrome.desktop.res.stringResource

/**
 * Entry point: what Android split between the Application (the object graph,
 * startup work) and MainActivity (the window).
 *
 * `-Dtryptify.smoke=true` or `TRYPTIFY_SMOKE=1` builds the graph, runs the
 * startup work, renders one frame and exits: the headless check the Linux
 * build runs under Xvfb.
 */
fun main() {
    // Before anything can wake Dispatchers.Default: this sets the coroutine
    // scheduler's pool sizes from the performance tier (see MonochromeApp).
    MonochromeApp.profile
    val component = DaggerAppComponent.factory().create(AppPaths())
    component.appLifecycle.onCreate()

    val smoke = System.getProperty("tryptify.smoke") == "true" || System.getenv("TRYPTIFY_SMOKE") == "1"
    application {
        CompositionLocalProvider(
            LocalAppComponent provides component,
            LocalContext provides component.context,
        ) {
            Window(onCloseRequest = ::exitApplication, title = stringResource(R.string.app_name)) {
                // File dialogs are modal to this window.
                FilePickers.owner = window
                // Media keys and the Windows "now playing" card attach to this window's HWND.
                LaunchedEffect(window) { component.mediaTransportControls.attach(window.windowHandle) }
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.app_name))
                        ToastHost()
                    }
                }
                if (smoke) {
                    LaunchedEffect(Unit) {
                        delay(1500)
                        System.err.println("SMOKE_OK first frame rendered")
                        exitApplication()
                    }
                }
            }
        }
    }
}
