// Desktop stand-in: marks a ViewModel the Dagger ViewModel factory must know.
// di/ViewModelModule.kt lists them; `tools/check_viewmodels.py` keeps the two in step.
package dagger.hilt.android.lifecycle

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class HiltViewModel
