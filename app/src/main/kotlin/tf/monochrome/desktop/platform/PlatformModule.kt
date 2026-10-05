package tf.monochrome.desktop.platform

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import tf.monochrome.desktop.dj.controller.HidBus
import tf.monochrome.desktop.dj.controller.MidiBus
import tf.monochrome.desktop.platform.windows.WindowsHid

/**
 * What Android's Application used to be: the desktop Context, the app-wide
 * coroutine scope and the paths, and the platform's own devices (HID, MIDI).
 * Bound once in AppComponent.
 */
@Module
object PlatformModule {
    @Provides
    @Singleton
    @ApplicationContext
    fun context(paths: AppPaths): Context = Context(paths)

    /** The unqualified Context, for classes that take one without @ApplicationContext. */
    @Provides
    @Singleton
    fun plainContext(@ApplicationContext context: Context): Context = context

    @Provides
    @Singleton
    @AppScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** USB HID, for the DJ controllers. Elsewhere than Windows it lists no devices. */
    @Provides
    fun hidBus(): HidBus = WindowsHid

    /** USB MIDI, for the DJ controllers: the JDK's, WinMM on Windows. */
    @Provides
    fun midiBus(): MidiBus = JavaMidiBus
}
