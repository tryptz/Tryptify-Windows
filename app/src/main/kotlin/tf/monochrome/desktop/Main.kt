package tf.monochrome.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.delay
import tf.monochrome.desktop.res.stringResource

/**
 * Entry point. `-Dtryptify.smoke=true` opens the window, renders one frame and
 * exits: the headless check the Linux build runs under Xvfb.
 */
fun main() = application {
    val smoke = System.getProperty("tryptify.smoke") == "true" || System.getenv("TRYPTIFY_SMOKE") == "1"
    Window(onCloseRequest = ::exitApplication, title = stringResource(R.string.app_name)) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.app_name) + " for Windows: scaffold")
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
