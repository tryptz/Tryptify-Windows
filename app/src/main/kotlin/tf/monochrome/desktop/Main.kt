package tf.monochrome.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.event.KeyEvent
import kotlinx.coroutines.delay
import tf.monochrome.desktop.audio.eq.FrequencyTargets
import tf.monochrome.desktop.data.downloads.LogNotifier
import tf.monochrome.desktop.di.DaggerAppComponent
import tf.monochrome.desktop.di.LocalAppComponent
import tf.monochrome.desktop.di.LocalWindowViewModelStoreOwner
import tf.monochrome.desktop.di.windowViewModel
import tf.monochrome.desktop.locale.AppLanguage
import tf.monochrome.desktop.platform.AppPaths
import tf.monochrome.desktop.platform.DesktopUriHandler
import tf.monochrome.desktop.platform.FilePickers
import tf.monochrome.desktop.platform.ToastHost
import tf.monochrome.desktop.platform.windows.WindowChrome
import tf.monochrome.desktop.res.stringResource
import tf.monochrome.desktop.ui.main.LocalWindowTitleBar
import tf.monochrome.desktop.ui.main.MainViewModel
import tf.monochrome.desktop.ui.main.TrayNotifier
import tf.monochrome.desktop.ui.main.TryptifyApp
import tf.monochrome.desktop.ui.main.WindowTitleBar

/**
 * Entry point: what Android split between the Application (the object graph,
 * startup work) and MainActivity (the window). The window's content is
 * [TryptifyApp]; this file is the Activity's other half, the window itself.
 *
 * `-Dtryptify.smoke=true` or `TRYPTIFY_SMOKE=1` builds the graph, runs the
 * startup work, renders one frame and exits: the headless check the Linux
 * build runs under Xvfb.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // Before anything can wake Dispatchers.Default: this sets the coroutine
    // scheduler's pool sizes from the performance tier (see MonochromeApp).
    MonochromeApp.profile
    val component = DaggerAppComponent.factory().create(AppPaths())
    component.appLifecycle.onCreate()
    // What MainActivity did before its first frame: attachBaseContext put the
    // app in the chosen language, and onCreate pointed the EQ targets at the
    // bundled assets.
    AppLanguage.wrap(component.context)
    FrequencyTargets.init(component.context)

    // The Activity's ViewModelStore. The ViewModels Android scoped to
    // MainActivity (the player, settings, the onboarding wizard) live as long
    // as the window, and are cleared when the app exits.
    val windowOwner = WindowViewModelStoreOwner()

    val smoke = System.getProperty("tryptify.smoke") == "true" || System.getenv("TRYPTIFY_SMOKE") == "1"
    application {
        DisposableEffect(windowOwner) {
            onDispose { windowOwner.viewModelStore.clear() }
        }
        CompositionLocalProvider(
            LocalAppComponent provides component,
            LocalContext provides component.context,
        ) {
            // Portrait and resizable: the screens were laid out for a phone held
            // upright, and nothing in them assumes a phone's width or a desktop's.
            val windowState = rememberWindowState(
                size = initialWindowSize(),
                position = WindowPosition(Alignment.Center),
            )
            val appIcon = painterResource(R.drawable.app_icon)
            Window(
                onCloseRequest = ::exitApplication,
                state = windowState,
                title = stringResource(R.string.app_name),
                icon = appIcon,
            ) {
                // File dialogs are modal to this window.
                FilePickers.owner = window
                // Media keys and the Windows "now playing" card attach to this window's HWND.
                LaunchedEffect(window) { component.mediaTransportControls.attach(window.awaitHandle()) }
                // Narrower or shorter than a small phone, the rows, the tab bar
                // and the player stop fitting; the window cannot be dragged below it.
                LaunchedEffect(window) { window.minimumSize = minimumWindowSize() }

                // The title bar follows the theme's ground, as the system bars
                // did on Android (see TryptifyApp), or a screen's own while it
                // claims the bar (see WindowTitleBar). A flow, so the root does
                // not recompose for it and DWM hears only actual changes.
                val titleBar = remember { WindowTitleBar() }
                LaunchedEffect(window) {
                    val hwnd = window.awaitHandle()
                    titleBar.colors.collect { (background, ink) ->
                        WindowChrome.apply(
                            hwnd,
                            dark = background.luminance() <= 0.5f,
                            captionArgb = background.toArgb(),
                            textArgb = ink.toArgb(),
                        )
                    }
                }

                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides windowOwner,
                    LocalWindowViewModelStoreOwner provides windowOwner,
                    LocalWindowTitleBar provides titleBar,
                    LocalUriHandler provides DesktopUriHandler,
                ) {
                    TrayNotifications(
                        icon = appIcon,
                        onActivate = {
                            windowState.isMinimized = false
                            window.toFront()
                            window.requestFocus()
                        },
                    )
                    // Escape is Back: Compose's window hands an Escape nobody
                    // consumed to the back dispatcher BackHandler and the NavHost
                    // listen on. The mouse's back button is sent as that Escape.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .onPointerEvent(PointerEventType.Release) { event ->
                                if (event.button == PointerButton.Back) pressEscape(window)
                            },
                    ) {
                        TryptifyApp(onThemeColors = titleBar::setTheme)
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

/** Holds the ViewModels scoped to the window; see main(). */
private class WindowViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()
}

/**
 * The tray takes over the download queue's and the playlist import's
 * notifications for as long as the window is open; before and after, they go
 * to the log (see AppNotifier).
 */
@Composable
private fun TrayNotifications(icon: Painter, onActivate: () -> Unit) {
    // AppNotifier is not exposed on AppComponent, so it comes through the
    // window's MainViewModel, as the window content's other singletons do.
    val main: MainViewModel = windowViewModel()
    val density = LocalDensity.current
    val appName = stringResource(R.string.app_name)
    val activate by rememberUpdatedState(onActivate)
    // Not keyed on the density: moving the window to another screen would
    // take the icon out of the tray and put it back.
    DisposableEffect(main, icon) {
        val image = icon.toAwtImage(density, LayoutDirection.Ltr, Size(TRAY_ICON_PX, TRAY_ICON_PX))
        val tray = TrayNotifier(appName, image, onActivate = { activate() })
        main.installNotifier(tray)
        onDispose {
            main.installNotifier(LogNotifier)
            tray.dispose()
        }
    }
}

/** Larger than any tray slot Windows asks for, so it is only ever scaled down. */
private const val TRAY_ICON_PX = 64f

private const val WINDOW_WIDTH_DP = 480
private const val WINDOW_HEIGHT_DP = 900
private const val MIN_WIDTH_DP = 360
private const val MIN_HEIGHT_DP = 640

/** The screen's usable area (less the taskbar), in the window's units; null when there is no screen. */
private fun usableScreen(): Rectangle? =
    runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }.getOrNull()

/** About a phone and a half tall, never taller than the screen it opens on. */
private fun initialWindowSize(): DpSize {
    val height = usableScreen()?.let { minOf(WINDOW_HEIGHT_DP, it.height) } ?: WINDOW_HEIGHT_DP
    return DpSize(WINDOW_WIDTH_DP.dp, height.dp)
}

/** A small phone's size, short of the screen on one that is shorter still. */
private fun minimumWindowSize(): Dimension {
    val height = usableScreen()?.let { minOf(MIN_HEIGHT_DP, it.height) } ?: MIN_HEIGHT_DP
    return Dimension(MIN_WIDTH_DP, height)
}

/**
 * The window's HWND, once it has one. The content is composed before the
 * window is shown, and until then there is no native window: the handle reads
 * as 0, which DWM and the media controls would take as "no window".
 */
private suspend fun ComposeWindow.awaitHandle(): Long {
    while (true) {
        if (isDisplayable) {
            val handle = runCatching { windowHandle }.getOrDefault(0L)
            if (handle != 0L) return handle
        }
        delay(16)
    }
}

/**
 * The mouse's back button, as an Escape press. Compose's window turns an
 * unconsumed Escape into Back, so the button walks back exactly as the key
 * does, through the same handlers.
 */
private fun pressEscape(window: ComposeWindow) {
    val target = window.mostRecentFocusOwner ?: window
    val now = System.currentTimeMillis()
    for (id in intArrayOf(KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED)) {
        target.dispatchEvent(KeyEvent(target, id, now, 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED))
    }
}
