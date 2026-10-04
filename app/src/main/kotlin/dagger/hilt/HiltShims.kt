// Desktop stand-ins for the Hilt annotations the ported sources carry.
//
// Hilt is Android-only; the desktop build wires the same @Module/@Provides and
// @Inject classes into one plain Dagger 2 component (tf.monochrome.desktop.di).
// These annotations only have to exist so the files compile unchanged: Dagger
// ignores @InstallIn and @HiltViewModel, and the ViewModel map in
// di/ViewModelModule.kt is what makes a @HiltViewModel class reachable.
package dagger.hilt

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class InstallIn(vararg val value: KClass<*>)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class EntryPoint
