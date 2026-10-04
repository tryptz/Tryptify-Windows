package tf.monochrome.desktop.ui.main

import tf.monochrome.desktop.ui.input.ThemedScrollbars
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tf.monochrome.desktop.di.windowViewModel
import tf.monochrome.desktop.ui.navigation.MonochromeNavHost
import tf.monochrome.desktop.ui.onboarding.OnboardingScreen
import tf.monochrome.desktop.ui.theme.MonochromeTheme
import tf.monochrome.desktop.ui.theme.ColorBlend
import tf.monochrome.desktop.ui.theme.rememberDynamicPalette
import tf.monochrome.desktop.performance.LocalPerformanceProfile

/**
 * The window's content: what MainActivity's onCreate and setContent did.
 *
 * Desktop: there is no Activity. main() owns the window and calls this inside
 * it, and what the Activity had injected comes from [MainViewModel], scoped to
 * the window as the Activity was. Left behind with the Activity, because a
 * desktop window has no counterpart for them:
 * - the splash screen and edge-to-edge. A window has no status or navigation
 *   bar to style; the theme's ground goes to the Windows title bar through
 *   [onThemeColors] instead.
 * - the notification permission prompt. A desktop app holds no runtime
 *   permissions, and the tray needs none.
 * - the volume-key interception for the libusb path. Windows handles the
 *   volume keys itself, for its mixer and its flyout, and AWT has no key code
 *   for them; the bypass volume is the player's slider.
 * - the OAuth deep links (`tryptify://…`, `tf.monotrypt.android://…`). They
 *   arrived as intents. Sign-in here finishes through a loopback redirect the
 *   Spotify, Last.fm and Supabase auth managers listen on themselves; opening
 *   the app from a link would need a registered URL protocol, which is the
 *   installer's work.
 *
 * The language (attachBaseContext) and FrequencyTargets.init are applied by
 * main() before the window opens, and the Supabase session is restored by
 * AppLifecycle.onCreate, which the Activity's own initialize() call duplicated.
 *
 * @param onThemeColors receives the theme's background and the ink on it each
 *   time the theme root recomposes, so the window can colour its title bar to
 *   match. It is where Android re-applied edge-to-edge with a matching
 *   SystemBarStyle.
 */
@Composable
fun TryptifyApp(
    onThemeColors: (background: Color, onBackground: Color) -> Unit = { _, _ -> },
    viewModel: MainViewModel = windowViewModel(),
) {
    val preferences = viewModel.preferences
    val queueManager = viewModel.queueManager
    val trackSourceResolver = viewModel.trackSourceResolver
    val performanceProfile = viewModel.performanceProfile

    /**
     * Route the main app should open right after onboarding hands off
     * ("library", "settings?tab=7"), null for the default start screen.
     */
    var pendingPostRoute by remember { mutableStateOf<String?>(null) }

    val themeName by preferences.theme.collectAsStateWithLifecycle(initialValue = "monochrome_dark")
    val storedFontScale by preferences.fontScale.collectAsStateWithLifecycle(initialValue = 1.0f)
    val followSystemFontScale by preferences.fontScaleFollowSystem.collectAsStateWithLifecycle(initialValue = false)
    // "Follow system" hands typography over to the OS accessibility font
    // size. Configuration.fontScale already reflects the user's Display >
    // Font size setting, so no extra permission or listener is needed —
    // a config change recomposes this and the type updates live.
    // Desktop: there is no Configuration, and Compose carries no font scale on
    // the desktop; the system's is Windows' own text size (SystemTextScale),
    // re-read while it is followed.
    val systemFontScale = rememberSystemFontScale(followSystemFontScale)
    val fontScale = if (followSystemFontScale) systemFontScale else storedFontScale
    val customFontPath by preferences.customFontUri.collectAsStateWithLifecycle(initialValue = null)
    val dynamicColorsEnabled by preferences.dynamicColors.collectAsStateWithLifecycle(initialValue = false)
    // Whether that palette is also allowed to repaint the menus, and
    // whether it gets the ground as well as the accent.
    val dynamicColorMenus by preferences.dynamicColorMenus.collectAsStateWithLifecycle(initialValue = false)
    val dynamicColorKeepBackground by preferences.dynamicColorKeepBackground
        .collectAsStateWithLifecycle(initialValue = false)
    val themePaper by preferences.themePaper.collectAsStateWithLifecycle(initialValue = "crisp")
    // Custom colours override the preset when the switch is on. Read here
    // so a change repaints the whole app the same frame the store emits.
    val customThemeEnabled by preferences.customThemeEnabled.collectAsStateWithLifecycle(initialValue = false)
    val customAccent by preferences.customAccentColor.collectAsStateWithLifecycle(initialValue = 0xFF5865F2.toInt())
    val customBackground by preferences.customBackgroundColor.collectAsStateWithLifecycle(initialValue = 0xFF101014.toInt())
    val currentTrack by queueManager.currentTrack.collectAsStateWithLifecycle()
    // Settings › System › Display › Full screen. Published as a
    // composition local so the visualiser's own fullscreen mode can read
    // the app-wide setting — see SystemBarsHidden.
    val immersiveFullScreen by preferences.immersiveFullScreen
        .collectAsStateWithLifecycle(initialValue = false)
    // The user's manual low-performance overrides, from Settings ›
    // System › Performance.
    val showExplicitBadges by preferences.showExplicitBadges
        .collectAsStateWithLifecycle(initialValue = true)
    val lowPerformance by preferences.lowPerformanceSettings
        .collectAsStateWithLifecycle(
            initialValue = tf.monochrome.desktop.performance.LowPerformanceSettings()
        )
    // The album palette crosses over at the Color transition length —
    // a few hundred milliseconds by default, not the audio blend, which
    // could make every track change a six-second repaint.
    val colorTransitionMs by preferences.colorTransitionMs
        .collectAsStateWithLifecycle(initialValue = ColorBlend.DEFAULT_MS)
    // Held as state, not unwrapped: `by` here would subscribe this whole
    // composable to a value that changes every frame of the colour
    // cross-fade, recomposing the root and every provider under it for
    // the length of the fade. MonochromeTheme passes it down and only
    // the surfaces that paint with it read it.
    val dynamicPalette = rememberDynamicPalette(
        coverUrl = currentTrack?.coverUrl,
        enabled = dynamicColorsEnabled,
        // Instant colour change with animations off: the palette is a
        // continuous cross-fade rather than a one-off transition, so it
        // goes on producing values for the whole blend window.
        blendMillis = if (lowPerformance.disableAnimations) 0
        else ColorBlend.millisFor(colorTransitionMs),
    )

    // Handles both a bundled `asset:` font and an imported file path —
    // see loadAppFontFamily. Keyed on the id so switching fonts in
    // Settings re-reads immediately.
    val fontLoadContext = androidx.compose.ui.platform.LocalContext.current
    val customFontFamily = remember(customFontPath, fontLoadContext) {
        tf.monochrome.desktop.ui.theme.loadAppFontFamily(fontLoadContext, customFontPath)
    }

    // "Remove liquid glass" is folded into the detected profile rather
    // than checked separately: allowHazeBlur is already the flag every
    // `Modifier.liquidGlass` call site consults, so turning it off here
    // reaches all of them — list rows, the mini player, the nav pill,
    // the audio-tools sheet — without touching one of them.
    val effectiveProfile = remember(performanceProfile, lowPerformance.disableLiquidGlass) {
        if (lowPerformance.disableLiquidGlass) {
            performanceProfile.copy(allowHazeBlur = false)
        } else {
            performanceProfile
        }
    }

    CompositionLocalProvider(
        LocalPerformanceProfile provides effectiveProfile,
        tf.monochrome.desktop.performance.LocalLowPerformance provides lowPerformance,
        tf.monochrome.desktop.ui.theme.LocalShowExplicitBadges provides showExplicitBadges,
        LocalImmersiveFullScreen provides immersiveFullScreen,
        // Every list row's source tag reads its catalog from here.
        tf.monochrome.desktop.ui.components.LocalTrackSource provides trackSourceResolver::of,
    ) {
        MonochromeTheme(
            themeName = themeName,
            fontScale = fontScale,
            customFontFamily = customFontFamily,
            dynamicPalette = dynamicPalette,
            paper = if (themePaper == "warm") {
                tf.monochrome.desktop.ui.theme.Paper.Warm
            } else {
                tf.monochrome.desktop.ui.theme.Paper.Crisp
            },
            customColors = if (customThemeEnabled) {
                tf.monochrome.desktop.ui.theme.CustomThemeColors(
                    accent = customAccent,
                    background = customBackground,
                )
            } else {
                null
            },
            dynamicMenus = dynamicColorsEnabled && dynamicColorMenus,
            dynamicMenusKeepBackground = dynamicColorKeepBackground,
        ) {
            // Desktop: where Android re-applied edge-to-edge with a
            // SystemBarStyle tuned to the theme, the window's title bar is
            // what follows it. Windows draws a light caption unless told
            // otherwise, a white slab over a dark theme; main() hands these
            // to WindowChrome, and only when they change.
            val background = MaterialTheme.colorScheme.background
            val onBackground = MaterialTheme.colorScheme.onBackground
            SideEffect {
                onThemeColors(background, onBackground)
            }
            // Desktop: no SystemBarsHidden(immersiveFullScreen) here; a window
            // has no status or navigation bar to hide.
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                // First-run gate. null = flag not read yet (the window shows
                // the theme's ground for that instant, where Android held its
                // splash); false = wizard; true = main app.
                // Collected (not read once) so Settings flipping the
                // flag back re-enters onboarding on the next frame.
                val onboardingComplete by preferences.onboardingComplete
                    .collectAsStateWithLifecycle(initialValue = null)
                when (onboardingComplete) {
                    null -> Unit
                    false -> OnboardingScreen(
                        onFinished = { pendingPostRoute = it }
                    )
                    // Scrollbars in the theme's ink: Compose's default is black,
                    // and the app's grounds are mostly dark.
                    true -> ThemedScrollbars { MonochromeNavHost(initialRoute = pendingPostRoute) }
                }
            }
        }
    }
}
