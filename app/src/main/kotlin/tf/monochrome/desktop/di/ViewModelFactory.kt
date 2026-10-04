package tf.monochrome.desktop.di

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.MapKey
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.reflect.KClass

/**
 * How ViewModels reach the screens without Hilt.
 *
 * Hilt built each `@HiltViewModel` on demand from its `@Inject` constructor and
 * handed the navigation arguments in as a `SavedStateHandle`. Plain Dagger has
 * no ViewModel support, so [ViewModelModule] binds every ViewModel into a map
 * keyed by class, and [AppViewModelFactory] is the `ViewModelProvider.Factory`
 * the Compose `viewModel()` call uses. The six ViewModels that take a
 * `SavedStateHandle` go through [SavedStateVmFactory], an assisted factory the
 * module binds the same way; the handle comes from the NavBackStackEntry's
 * creation extras, so `handle["albumId"]` reads the route argument as before.
 */
@MapKey
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ViewModelKey(val value: KClass<out ViewModel>)

/** Assisted construction for ViewModels that take the navigation SavedStateHandle. */
interface SavedStateVmFactory<T : ViewModel> {
    fun create(handle: SavedStateHandle): T
}

@Singleton
class AppViewModelFactory @Inject constructor(
    private val plain: Map<Class<out ViewModel>, @JvmSuppressWildcards Provider<ViewModel>>,
    private val assisted: Map<Class<out ViewModel>, @JvmSuppressWildcards SavedStateVmFactory<*>>,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
        val java = modelClass.java
        assisted[java]?.let { return it.create(extras.createSavedStateHandle()) as T }
        plain[java]?.let { return it.get() as T }
        error("No ViewModel binding for ${java.name}: add it to ViewModelModule")
    }
}

/** The Dagger graph, provided at the window root. */
val LocalAppComponent = staticCompositionLocalOf<AppComponent> { error("AppComponent not provided") }

/**
 * A ViewModelStoreOwner that lives as long as the window: the stand-in for the
 * Activity-scoped ViewModels (the player, settings, library) the Android app
 * shared across screens.
 */
val LocalWindowViewModelStoreOwner = staticCompositionLocalOf<ViewModelStoreOwner> { error("window ViewModelStoreOwner not provided") }

@Composable
inline fun <reified T : ViewModel> appViewModel(
    owner: ViewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current),
    key: String? = null,
): T {
    val factory = LocalAppComponent.current.viewModelFactory
    val extras = if (owner is HasDefaultViewModelProviderFactory) owner.defaultViewModelCreationExtras else CreationExtras.Empty
    return viewModel(owner, key, factory, extras)
}

/** A ViewModel scoped to the window rather than the current navigation entry. */
@Composable
inline fun <reified T : ViewModel> windowViewModel(key: String? = null): T =
    appViewModel(LocalWindowViewModelStoreOwner.current, key)
