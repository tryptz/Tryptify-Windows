// Desktop stand-in for Hilt's @ApplicationContext. A real Dagger qualifier here,
// so `@ApplicationContext private val context: Context` keeps compiling and is
// satisfied by PlatformModule's single desktop Context.
package dagger.hilt.android.qualifiers

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
annotation class ApplicationContext
