package tf.monochrome.desktop.platform

import javax.inject.Qualifier

/** The process-wide coroutine scope (Android's `MonochromeApp.appScope`). */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class AppScope
