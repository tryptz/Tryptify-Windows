package tf.monochrome.desktop

import android.content.Context
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.crossfade
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tf.monochrome.desktop.audio.usb.UsbExclusiveController
import tf.monochrome.desktop.data.auth.SupabaseAuthManager
import tf.monochrome.desktop.data.device.DeviceRegistry
import tf.monochrome.desktop.data.local.coil.AudioFileCoverFetcher
import tf.monochrome.desktop.data.local.scanner.ScanRunner
import tf.monochrome.desktop.data.preferences.PreferencesManager
import tf.monochrome.desktop.data.repository.GenreGraphRepository
import tf.monochrome.desktop.data.sync.LibraryRestoreCoordinator
import tf.monochrome.desktop.data.sync.SettingsSyncCoordinator
import tf.monochrome.desktop.debug.CrashLogger
import tf.monochrome.desktop.debug.DebugLogCollector
import tf.monochrome.desktop.dj.controller.ControllerManager
import tf.monochrome.desktop.performance.DeviceCapabilities
import tf.monochrome.desktop.performance.PerformanceProfile
import tf.monochrome.desktop.platform.AppScope
import tf.monochrome.desktop.platform.NativeLibraries
import tf.monochrome.desktop.player.PlaybackStateRepository
import tf.monochrome.desktop.player.engine.AudioOutputController
import tf.monochrome.desktop.player.engine.EngineController
import tf.monochrome.desktop.visualizer.ProjectMAssetInstaller

/**
 * The static half of Android's `Application`: what has to be decided before
 * anything else runs.
 *
 * On Android this was `MonochromeApp`'s companion object, initialised when
 * the Application class loaded. On the desktop main() touches [profile] first
 * thing, for the same reason: the coroutine scheduler reads its pool sizes
 * exactly once, on the first use of `Dispatchers.Default`, so they must be
 * set before the object graph or the first frame wakes it.
 */
object MonochromeApp {
    @JvmStatic
    val profile: PerformanceProfile =
        PerformanceProfile.forTier(DeviceCapabilities.detect().first)

    init {
        System.setProperty("kotlinx.coroutines.scheduler.core.pool.size", profile.corePoolSize.toString())
        System.setProperty("kotlinx.coroutines.scheduler.max.pool.size", profile.maxPoolSize.toString())
        val snapshot = DeviceCapabilities.detect().second
        Log.i(
            "MonoPerf",
            "tier=${profile.tier} cores=${snapshot.cores} big=${snapshot.bigCores} " +
                "maxFreq=${snapshot.maxFreqMhz}MHz ram=${snapshot.ramMb}MB " +
                "pool=${profile.corePoolSize}/${profile.maxPoolSize} " +
                "spectrumFps=${profile.spectrumFps} haze=${profile.allowHazeBlur}"
        )
    }
}

/**
 * The other half: `Application.onCreate`, as an injected singleton that
 * main() calls once the graph exists.
 *
 * Desktop: three things from the Android list are gone. The notification
 * channel (Windows has no channels; the tray Notifier posts directly), the
 * system-wide AutoEQ (Android attached an effect to the global output mix;
 * the Windows equivalent is an APO driver the app cannot install, so AutoEQ
 * applies to the app's own output, which it always did) and the artwork
 * migration (it moved covers out of an Android cache directory that no
 * desktop install ever had).
 */
@Singleton
class AppLifecycle @Inject constructor(
    private val context: Context,
    @AppScope private val appScope: CoroutineScope,
    private val crashLogger: CrashLogger,
    private val debugLogCollector: DebugLogCollector,
    private val authManager: SupabaseAuthManager,
    private val deviceRegistry: DeviceRegistry,
    private val settingsSyncCoordinator: SettingsSyncCoordinator,
    private val libraryRestoreCoordinator: LibraryRestoreCoordinator,
    private val playbackStateRepository: PlaybackStateRepository,
    private val engineController: EngineController,
    private val audioOutput: AudioOutputController,
    private val scanRunner: ScanRunner,
    private val usbExclusiveController: UsbExclusiveController,
    private val controllerManager: ControllerManager,
    // Providers: these are only warmed on a background coroutine, so building
    // them here would move their cost onto the startup path the warm-up clears.
    private val genreGraph: Provider<GenreGraphRepository>,
    private val projectMAssets: Provider<ProjectMAssetInstaller>,
    private val preferencesProvider: Provider<PreferencesManager>,
) {
    private var created = false

    fun onCreate() {
        if (created) return
        created = true
        // First, so a crash anywhere below is captured.
        crashLogger.install()
        // The debug log screen reads this buffer; start it before the noise.
        debugLogCollector.start()
        installImageLoader()
        // Link the native libraries off the UI thread. Every loader goes
        // through NativeLibraries.load, so whichever class touches a library
        // first simply finds it already loaded.
        appScope.launch { NativeLibraries.preload() }
        // Restore auth, then register this device against whoever is signed
        // in. The collector re-fires on sign-in and sign-out.
        appScope.launch {
            authManager.initialize()
            authManager.userProfile
                .distinctUntilChanged { a, b -> a?.id == b?.id }
                .collect { user ->
                    if (user != null) deviceRegistry.registerCurrentDevice()
                    else deviceRegistry.clearOnSignOut()
                }
        }
        settingsSyncCoordinator.start(appScope)
        libraryRestoreCoordinator.start(appScope)
        // The queue, track and second the app was last closed on. Paused:
        // nothing resolves or plays until the user presses play.
        playbackStateRepository.start(appScope)
        // What PlaybackService did when Media3 bound it: the listener, the
        // preference collectors and the per-track side effects.
        engineController.start()
        // The persisted output device and mode (WASAPI shared or exclusive).
        audioOutput.start()
        // The Exclusive USB DAC toggle: claims a WinUSB-bound DAC and routes
        // the engine to it while on; without this the toggle does nothing.
        usbExclusiveController.start()
        // Desktop: DJ controllers, found as they are plugged in, from any screen.
        controllerManager.start()
        appScope.launch {
            runCatching { preferencesProvider.get().retireRemovedKeys() }
        }
        // WorkManager carried an interrupted first scan across restarts on
        // Android; the runner keeps a marker file for the same purpose.
        scanRunner.resumeIfInterrupted()
        warmFirstUseCaches()
    }

    /**
     * One ImageLoader for the whole app, so the memory and disk caches
     * survive navigation instead of every call site building its own.
     */
    private fun installImageLoader() {
        val p = MonochromeApp.profile
        SingletonImageLoader.setSafe { platformContext: PlatformContext ->
            ImageLoader.Builder(platformContext)
                .memoryCache {
                    MemoryCache.Builder()
                        .maxSizePercent(platformContext, p.coilMemoryPercent)
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(context.cacheDir.resolve("image_cache"))
                        .maxSizeBytes(p.coilDiskBytes)
                        .build()
                }
                .crossfade(true)
                .components {
                    // Embedded album art straight from audio files, for local
                    // and downloaded tracks the artwork cache has not reached.
                    add(AudioFileCoverFetcher.Factory(context))
                }
                .build()
        }
    }

    /**
     * Work that is otherwise paid for by whoever opens a screen first, done
     * after the first frame and selectively (see the Android original's notes:
     * the larger JSON tables stay lazy because they are already suspending and
     * cached). Nothing here is load-bearing.
     */
    private fun warmFirstUseCaches() {
        appScope.launch {
            runCatching { genreGraph.get().warm() }
        }
        appScope.launch {
            runCatching {
                if (preferencesProvider.get().visualizerEngineEnabled.first()) {
                    projectMAssets.get().ensureInstalled()
                }
            }
        }
    }
}
