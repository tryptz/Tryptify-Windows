package tf.monochrome.desktop.platform

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * What Android's Application used to be: the desktop Context, the app-wide
 * coroutine scope and the paths. Bound once in AppComponent.
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
}
