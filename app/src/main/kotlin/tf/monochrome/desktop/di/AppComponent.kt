package tf.monochrome.desktop.di

import android.content.Context
import dagger.BindsInstance
import dagger.Component
import javax.inject.Singleton
import tf.monochrome.desktop.data.collections.di.CollectionModule
import tf.monochrome.desktop.data.local.di.LocalMediaModule
import tf.monochrome.desktop.platform.AppPaths
import tf.monochrome.desktop.platform.PlatformModule
import tf.monochrome.desktop.platform.windows.MediaTransportControls
import tf.monochrome.desktop.player.engine.PlaybackModule
import tf.monochrome.desktop.AppLifecycle

/**
 * The whole object graph, built once in main(). Where Hilt generated this
 * from @InstallIn(SingletonComponent::class), the modules are listed here;
 * they are the Android app's modules unchanged apart from Android-only
 * providers, plus PlatformModule (paths, Context, app scope) and the
 * ViewModel map, and PlaybackModule (the desktop engine that replaces
 * ExoPlayer and PlaybackService).
 */
@Singleton
@Component(
    modules = [
        AppModule::class,
        DatabaseModule::class,
        DspModule::class,
        NetworkModule::class,
        ApiModule::class,
        PerformanceModule::class,
        CollectionModule::class,
        LocalMediaModule::class,
        PlatformModule::class,
        PlaybackModule::class,
        ViewModelModule::class,
    ],
)
interface AppComponent {
    val viewModelFactory: AppViewModelFactory
    val appLifecycle: AppLifecycle
    val context: Context
    val mediaTransportControls: MediaTransportControls

    @Component.Factory
    interface Factory {
        fun create(@BindsInstance paths: AppPaths): AppComponent
    }
}
