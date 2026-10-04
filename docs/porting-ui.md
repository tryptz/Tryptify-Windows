# Porting the screens

How a screen from the Android app becomes a desktop screen. Read
[`porting-plan.md`](porting-plan.md) for why the port is done this way and
[`ui-invariants.md`](ui-invariants.md) before touching glass, search bars,
press feedback, themes, the globe or the visualizer.

The rule that matters most: **change as little as possible.** A screen that
still reads like its Android original can be diffed against it when the
Android app moves on. Most of the platform differences are absorbed by shims
that sit under the Android package names, so the imports stay as they were.

## Moving a file

`port/pending/tf/monochrome/desktop/ui/<pkg>/X.kt` moves to
`app/src/main/kotlin/tf/monochrome/desktop/ui/<pkg>/X.kt` with `git mv`, so
history follows it. Then edit it in place.

When a feature has no desktop meaning, remove it with a one-line
`// Desktop: <why>` comment where it was. Never leave a stub that pretends.

## What keeps working unchanged

| Android code | On the desktop |
|---|---|
| `androidx.compose.ui.res.stringResource(R.string.x, …)`, `pluralStringResource(R.plurals.x, n, …)` | Shim in `androidx/compose/ui/res`. `R.string.x` is a `StringKey`, `R.plurals.x` a `PluralKey`. Translations follow the app language. |
| `painterResource(R.drawable.x)` | Same shim. `R.drawable.x` is a Compose Multiplatform `DrawableResource`. |
| `Font(R.font.x, weight)`, `Font("fonts/x.ttf", context.assets, weight)`, `Font(file, weight)` | Shim in `androidx/compose/ui/text/font`. |
| `LocalContext.current` | Shim in `androidx/compose/ui/platform`. It is the app's one desktop `Context`: `filesDir`, `cacheDir`, `assets`, `getString`, `contentResolver`, `startActivity`, `paths`. |
| `Toast.makeText(context, text, LENGTH_SHORT).show()` | Shim. Drawn by the window as a transient message. |
| `BackHandler(enabled) { … }` | Shim over Compose Multiplatform's. Escape is back. |
| `rememberLauncherForActivityResult(ActivityResultContracts.GetContent / OpenDocument / OpenDocumentTree / CreateDocument(mime) / RequestPermission / RequestMultiplePermissions / StartActivityForResult)` | Shim. The pickers are native Windows dialogs and return `file:` URIs. Permissions are always granted. `StartActivityForResult` reports a cancel. |
| `context.contentResolver.openInputStream(uri)`, `openOutputStream(uri[, mode])`, `query(uri, …)` for `OpenableColumns.DISPLAY_NAME`/`SIZE`, `takePersistableUriPermission` | Shim over `file:` URIs (no-op for the permission). |
| `Intent(ACTION_VIEW, uri)`, `Intent(ACTION_SEND).putExtra(EXTRA_TEXT …)`, `Intent.createChooser`, `context.startActivity`, `ActivityNotFoundException` | Shim. VIEW opens the browser or the file's program. SEND puts text on the clipboard with a toast, or reveals a shared file in Explorer. Anything else throws `ActivityNotFoundException`. |
| `android.graphics.RuntimeShader`, `RenderEffect.createRuntimeShaderEffect / createBlurEffect / createChainEffect`, `Shader.TileMode`, `BitmapShader`, `.asComposeRenderEffect()`, `ShaderBrush(runtimeShader)` | Shim over Skia, which is what AGSL ran on. **AGSL sources stay byte-identical.** Uniform setters and `setInputShader` are the same calls. |
| `android.graphics.Bitmap` | A typealias for `org.jetbrains.skia.Bitmap`, which is what Coil's `image.toBitmap()` returns on the JVM. `width`/`height` work, Android's `Bitmap.createBitmap`/`compress`/`getPixel` do not. |
| `android.graphics.Color.argb/rgb/red/…/HSVToColor/colorToHSV/parseColor/luminance` | Shim with Android's arithmetic. |
| `android.os.Build.VERSION.SDK_INT` | 36. API gates take their modern branch, so the RuntimeShader paths are live. `@RequiresApi` (androidx.annotation) compiles and can stay. |
| `hiltViewModel()`, `@HiltViewModel`, `@Inject constructor`, `@ApplicationContext` | Shims plus the Dagger ViewModel map. After porting ViewModels, run `python3 tools/generate_viewmodel_module.py`. |
| A ViewModel taking `SavedStateHandle` | Add `@AssistedInject` on the constructor and `@Assisted` on the handle, plus `@AssistedFactory interface Factory : SavedStateVmFactory<ThisVm>` inside the class. The generator binds it. |
| `androidx.lifecycle.*` (`ViewModel`, `viewModelScope`, `collectAsStateWithLifecycle`, `LocalLifecycleOwner`, `LifecycleStartEffect`, `SavedStateHandle`) | The JetBrains multiplatform artifacts, same packages. |
| `androidx.navigation.compose.*` (`NavHost`, `composable`, `navArgument`, `NavType`, `rememberNavController`, `currentBackStackEntryAsState`) | JetBrains multiplatform navigation 2.9, same packages. |
| Haze (`hazeSource`, `hazeEffect`, `HazeState`, `HazeStyle`, `HazeTint`) | Haze 1.7.3, multiplatform. |
| Coil 3 (`AsyncImage`, `SubcomposeAsyncImage`, `ImageRequest`, `SingletonImageLoader`) | Coil 3, multiplatform. See the exceptions below. |
| `LocalHapticFeedback`, `LocalDensity`, `LocalSoftwareKeyboardController`, `pointerInput`, `nestedScroll`, `Dialog`, `ModalBottomSheet`, material icons | Compose Multiplatform. Haptics are a no-op on the desktop. |

## What has to change

- **Resource ids as values.** `@StringRes Int` becomes `StringKey`,
  `@PluralsRes Int` becomes `PluralKey`, and `@DrawableRes Int` becomes
  `org.jetbrains.compose.resources.DrawableResource`. That applies to fields,
  parameters and maps holding an `R.string.x`; drop the annotation.
  `context.getString(key)` takes a `StringKey`.
- **Coil and the Context.** Coil's `PlatformContext` on the JVM is a singleton
  our `Context` cannot be, so `ImageRequest.Builder(context)` becomes
  `ImageRequest.Builder(PlatformContext.INSTANCE)`. Likewise
  `SingletonImageLoader.get(context)`. In a composable, `LocalPlatformContext.current`
  also works. `allowHardware(…)` is Android-only: delete it (there are no
  hardware bitmaps).
- **Palette.** `androidx.palette.graphics.Palette` becomes
  `com.kmpalette.palette.graphics.Palette`. It is built from pixels:
  `Palette.from(argbIntArray, width, height).generate()`. Get the pixels from
  `ImageBitmap.readPixels` or from the Skia bitmap. Swatches and targets carry
  the same names.
- **Text and paths on a native canvas.** `drawIntoCanvas { it.nativeCanvas … }`
  with `android.graphics.Paint`/`Path`/`RectF` has to be rewritten. On the
  desktop `nativeCanvas` is a Skia canvas. Prefer Compose:
  `rememberTextMeasurer()` + `drawText(…)`, Compose `Path`, `drawRect`/`drawArc`.
- **Navigation arguments.** `backStackEntry.arguments?.getString("id")`
  becomes `backStackEntry.arguments?.read { getStringOrNull("id") }`
  (`import androidx.savedstate.read`). Use `getIntOrNull`, `getLongOrNull` and
  so on for other types. `SavedStateHandle` reads (`handle["id"]`) are
  unchanged.
- **Activity-scoped ViewModels.** `hiltViewModel(activity)`,
  `viewModel(LocalContext.current as ComponentActivity)` and the like become
  `windowViewModel()` (`tf.monochrome.desktop.di`). It returns the one
  instance for the window.
- **The player connection.** There is no MediaController. `EngineController`
  (`player/engine`) owns the engine; `engineController.engine` *is* a
  `Player` (the Media3 interface shim), so code that held a `MediaController`
  holds that instead. Listeners are called on the UI thread, as Media3's were.
- **`LocalConfiguration`.** `fontScale` becomes `LocalDensity.current.fontScale`.
  `locales` becomes `tf.monochrome.desktop.res.Strings.locale` (a
  `java.util.Locale`). Screen size becomes `LocalWindowInfo.current.containerSize`.
- **`LocalView`, `ViewTreeObserver`, `Window`, `WindowCompat`, `enableEdgeToEdge`,
  splash screen, `keepScreenOn`.** These are Android view-system concepts with
  no desktop counterpart. A pre-draw listener becomes a `withFrameNanos` loop or
  an invalidation from the modifier that draws. The rest go, with a note.
- **Sensors** (the glass tilt) have no desktop source. The tilt stays at rest,
  which is what Android did on a device without the sensor.
- **Runtime permissions** (`android.Manifest`, accompanist permissions,
  notification and storage permission prompts) do not exist. Treat them as
  granted and remove the prompt UI with a note. Onboarding skips such a step.
- **System settings, MediaStore, `MediaProjection`, `AudioManager`.** These
  have no counterpart. Remove them with a note, or route to the desktop
  service the plan names (WASAPI device list for audio output, a folder for
  the library).
- **Widgets and Android Auto** are dropped (`port/dropped`).

## Window size

The screens were designed for a phone held upright. The desktop window opens
at a portrait-ish size and is resizable. Nothing in the screens should assume
a phone's width, and nothing should assume a desktop's either.

## Done means

- Every file in the package is under `app/src/main/kotlin`, and no import
  is left that the desktop does not have.
- Public composables and ViewModel APIs keep their names and parameters,
  because other packages call them.
- The pure unit tests for the package are moved from `port/pending-tests`
  when they have no Android imports.
- Anything needed outside the package is written down, not improvised.
