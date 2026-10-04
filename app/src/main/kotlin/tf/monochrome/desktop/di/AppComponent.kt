package tf.monochrome.desktop.di

import dagger.BindsInstance
import dagger.Component
import javax.inject.Singleton
import tf.monochrome.desktop.data.collections.di.CollectionModule
import tf.monochrome.desktop.data.local.di.LocalMediaModule
import tf.monochrome.desktop.platform.AppPaths
import tf.monochrome.desktop.platform.PlatformModule

/**
 * The whole object graph, built once in main(). Where Hilt generated this
 * from @InstallIn(SingletonComponent::class), the modules are listed here;
 * they are the Android app's modules unchanged apart from Android-only
 * providers, plus PlatformModule (paths, Context, app scope) and the
 * ViewModel map.
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
        ViewModelModule::class,
    ],
)
interface AppComponent {
    val viewModelFactory: AppViewModelFactory
    val appLifecycle: AppLifecycle

    @Component.Factory
    interface Factory {
        fun create(@BindsInstance paths: AppPaths): AppComponent
    }
}
