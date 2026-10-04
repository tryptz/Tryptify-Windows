// Desktop stand-ins for the Hilt class markers. No effect; kept so a ported
// file compiles while its Android lifecycle class is still being replaced.
package dagger.hilt.android

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class AndroidEntryPoint

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class HiltAndroidApp
