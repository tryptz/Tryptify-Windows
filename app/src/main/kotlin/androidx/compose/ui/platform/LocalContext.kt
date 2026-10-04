package androidx.compose.ui.platform

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Android's `LocalContext`, which Compose for Desktop does not have. main()
 * provides the app's single desktop [Context] (paths, assets, strings), so
 * the 40-odd screens that read `LocalContext.current` keep doing so.
 */
val LocalContext = staticCompositionLocalOf<Context> { error("LocalContext not provided; main() supplies it") }
