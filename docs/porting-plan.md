# Tryptify for Windows — the port, how it is built and why

This repository is the Windows (desktop JVM) port of [Tryptify](https://github.com/tryptz/Tryptify),
the Android hi-fi music player. The Android app stays where it is; this repo
carries the same features to Windows as a Compose for Desktop application, and
is laid out so that fixes can flow between the two with a diff.

The port is a work in progress. The **Status** section at the end says what is
done, what is in flight and what is not started, and is kept current.

## The one decision everything else follows from

The Android app is 156k lines of Kotlin: Jetpack Compose UI, Hilt, Room,
DataStore, Ktor, Coil, Haze, Supabase, and a Media3 ExoPlayer pipeline that runs
27k lines of native C++ (the DSP console, Atmos renderer, pitch shifter,
projectM visualizer, libusb USB DAC driver) through JNI.

Every UI toolkit other than Compose would mean rewriting the 79k lines of UI.
**Compose Multiplatform** publishes the very same `androidx.compose.*` packages
for the JVM, and JetBrains ships desktop ports of Lifecycle and Navigation
under the same `androidx.*` names; Room, DataStore, Paging, Coil, Haze, Ktor,
kotlinx and Supabase-kt all have JVM artifacts. So the port is **the Android
code compiled for the JVM**, with a thin layer standing in for what Android
alone provided:

| Android | Desktop | How |
|---|---|---|
| Media3 ExoPlayer (decode, sink, session) | own `PlaybackEngine`: FFmpeg (JavaCV) decode → the app's own processor chain → WASAPI / JavaSound / libusb sink | rewrite of `player/PlaybackService.kt` only; the 14-stage processor chain is reused as-is behind a Media3-shaped shim |
| `android.media.AudioTrack` | `native/wasapi/` (new C++), event-driven shared or exclusive mode | JNI |
| Hilt | plain Dagger 2 with a ViewModel map factory | shims for the annotations, one generated module |
| `android.content.Context` | `AppPaths` + a `Context` class offering `filesDir`, `cacheDir`, `assets`, `getString` | shim in the same package |
| Android resources (`R.string`, `R.plurals`, `R.font`, `R.drawable`) | generated `R` object over JSON translation tables, bundled fonts, Compose vector drawables | `app/l10n/generate_strings.py` |
| `android.util.Log`, `android.net.Uri` | same-package re-implementations | shim |
| MediaStore scanner, SAF, permissions | folder walk with JAudioTagger, file dialogs, no permissions | rewrite |
| Services, notifications, MediaSession, widget, Auto, Cast, system-wide EQ | dropped (SMTC and a tray icon are later options) | — |
| GLSurfaceView / TextureView for projectM | offscreen GL render into a Compose `ImageBitmap` (keeps the glass layering) | rewrite of the two views |

### Why shims under Android's package names

`androidx.media3.common.audio.AudioProcessor`, `android.util.Log`, `android.net.Uri`,
`android.content.Context` and the Hilt annotations are re-implemented here *in
their original packages* (`app/src/main/kotlin/android`, `androidx`, `dagger`).
This is unusual and deliberate: the processors, the data layer and the ViewModels
then compile **byte-identical** to the Android sources, so a bug fixed in one repo
is a `diff` away from the other, and the port's own code stays small and
reviewable. The shims are small (the Media3 one is an interface, a format class
and a few constants) and are independent re-implementations of a documented API
shape, not copies. Nothing Android-only is faked: a shim covers what the ported
code actually calls, and a file that needs a system service, a content resolver or
an Intent gets a real desktop implementation or is dropped.

## Repository layout

```
app/                     the Compose for Desktop application (Gradle module)
  build.gradle.kts       Kotlin 2.4.20, Compose Multiplatform 1.12.1, KSP, Dagger, Room
  l10n/                  strings.tsv (shared with the Android app) + generate_strings.py
  src/main/kotlin/
    tf/monochrome/desktop/   the app, package-renamed from tf.monochrome.android
      res/               Strings, StringResources (stringResource, pluralStringResource, Font)
      platform/          AppPaths, PlatformModule, AppScope
      di/                AppComponent, ViewModelFactory, ViewModelModule
      R.kt               generated: R.string.*, R.plurals.*, R.font.*, R.drawable.*
    android/, androidx/, dagger/   the shims (see above)
  src/main/resources/    i18n/strings_<lang>.json, fonts/, assets/ (the Android assets tree)
  src/main/composeResources/drawable/   the vector icons
  resources/<os>-<arch>/ the native libraries the installer ships (built by native/)
  src/test/              the JVM unit tests (ported from the Android app)
native/                  C++: dsp, stretch, atmos, usb, visualizer bridge, wasapi; CMake
third_party/             projectM 4.1.6, libusb, zlib, glew-cmake (submodules); libmysofa, signalsmith-stretch (vendored)
port/pending/            Android sources not yet ported (shrinks to nothing)
docs/                    this plan, ui-invariants.md, agent-playbook.md
tools/                   the Android repo's data-generation scripts (genre graph, world radio)
```

Package name: `tf.monochrome.desktop`. The Android app is `tf.monochrome.android`;
the rename is a one-segment substitution (sources and the 121 JNI symbol names),
so `diff -r` between the repos works after the substitution.

## The stack (verified on Maven Central, October 2026)

| | Version | Notes |
|---|---|---|
| Kotlin | 2.4.20 | Compose Multiplatform ≥ 1.11 needs Kotlin language 2.2+; current Haze/Coil/Supabase are built with 2.4 |
| Compose Multiplatform | 1.12.1 | plugin dependency aliases are deprecated → every artifact pinned in `libs.versions.toml` |
| Material3 | `org.jetbrains.compose.material3:material3:1.9.0` | versioned apart from CMP; a superset of the Android app's 1.3.2 |
| Material icons | `androidx.compose.material:material-icons-extended:1.7.8` (+core) | frozen artifacts; ProGuard trims the 38 MB jar |
| Lifecycle / Navigation | `org.jetbrains.androidx.lifecycle:*:2.11.0`, `org.jetbrains.androidx.navigation:navigation-compose:2.9.2` | Navigation 2.9 stores arguments as `SavedState`, not `Bundle` |
| KSP / Dagger | 2.3.12 / 2.60.1 | KSP versions independently of Kotlin since 2.3; Dagger's KSP support is stable from 2.60 |
| Room / SQLite | 2.8.5 / `sqlite-bundled` 2.7.1 | JVM target: `Room.databaseBuilder<Db>(path)`, migrations take `SQLiteConnection`, DAOs must be `suspend`/`Flow` |
| DataStore | 1.2.1 (`datastore-preferences-core`) | `PreferenceDataStoreFactory.createWithPath` |
| Paging | 3.5.1 | publishes desktop variants |
| Coil | 3.6.3 | `PlatformContext.INSTANCE` on the JVM |
| Haze | **1.7.3** | 2.0 renamed `hazeEffect`/`HazeStyle`; the glass code and `docs/ui-invariants.md` are written against 1.7 |
| Ktor | 3.5.1 | the line Supabase-kt 3.8.0 is built on |
| Supabase-kt | 3.8.0 (`auth-kt`, `postgrest-kt`) | OAuth via browser + loopback redirect on desktop |
| JavaCV / FFmpeg | 1.5.14 / 8.1.2 (LGPL build, `windows-x86_64` + `linux-x86_64`) | decoding |
| Palette | `com.materialkolor.palette:androidx-palette:4.0.0` | pure-Kotlin port of androidx.palette |
| JNA | 5.19.1 | Windows DPAPI for stored tokens |
| JAudioTagger | 3.0.1 | tags and embedded art for the library scanner |

## The native layer

`native/CMakeLists.txt` builds the same sources as the Android app for Windows
x64 (MSVC, or MinGW-w64 cross-compiled from Linux through
`native/cmake/toolchain-mingw-w64-x86_64.cmake`) and Linux x64. Six libraries:
`monochrome_dsp`, `monochrome_stretch`, `monochrome_atmos_jni`, `monochrome_usb`,
`monochrome_visualizer` (projectM 4.1.6 linked statically, with GLEW) and the new
`monochrome_wasapi`. `<android/log.h>` is answered from `native/compat/`, and the
only source changes against Android are `M_PI` needing `_USE_MATH_DEFINES`, two
POSIX calls in the projectM bridge, and the USB driver gaining
`openByIds`/`listDevices` (Windows has no `UsbDeviceConnection` file descriptor;
a DAC must be bound to WinUSB with Zadig for libusb to claim it, so the default
bit-perfect path on Windows is WASAPI exclusive mode, and libusb is the
advanced option).

The 28 host tests (DSP, Atmos, stretch) are registered with CTest; the sanitizer
runs stay in `native/dsp/tests/run_host_tests.sh`.

## The playback engine (replacing Media3)

The Android app's `QueueManager` is already a pure state holder and stays the
queue's owner. `PlaybackService` (2254 lines of ExoPlayer wiring, Android
services and listeners) is replaced by two classes in `player/engine`:
`PlaybackEngine`, which implements the Media3 `Player` interface shim so the
code that held a `MediaController` holds the engine instead, and
`EngineController`, which carries the service's logic over (queue advance,
retries with backoff, live reconnects, history, scrobbling, the Discord card,
the visualizer's per-track preset, and every preference collector).

- **Decode thread** per open stream: FFmpeg (JavaCV) demuxes and decodes into
  a lock-free single-producer ring. It produces 16-bit when the codec is
  natively 16-bit, so a CD-quality file can stay bit-exact, and float
  otherwise. How the bytes arrive is the `PlaybackModule` opener's choice:
  `qobuz://` and `deezer://` go through the app's own `DataSource`s (the
  partial cache that plays a download while it is still arriving, and the
  Deezer stripe decryption), behind the same `SchemeRoutingDataSource` as on
  Android. Everything else is opened by libavformat itself, so radio keeps
  native HTTP reconnects and HLS. TIDAL's inline DASH manifest goes to the
  DASH demuxer with the segment protocols whitelisted.
- **Render thread**: pulls from the ring and runs the app's
  `AudioProcessorChain` (`[ToFloatPcm, ChannelDetector, BpmTap, Atmos, Upmix,
  MixBus, Downmix, AutoEq, ParametricEq, SpectrumTap, ProjectMTap, VariRate,
  Stretch, FloatSonic]`, the Android USB/hi-res chain). Membership is re-read
  every block, so a stage that a live control wakes (a speed away from 1x, a
  pitch shift) joins mid-track. The engine then packs to the device format and
  writes to the sink with back-pressure. Crossfade mixes the outgoing and
  incoming sources before the chain, so there is one DSP state, not two.
- **Gapless**: when the next track's format matches, its ring is spliced onto
  the same sink with no drain, and the transition is reported when it is
  heard (the sink's played-frame clock), not when it is decoded.
- **Sinks** behind one interface: `WasapiSink` (shared through the Windows
  mixer; or exclusive and bit-perfect: 16-bit sources stay 16-bit, float
  sources take the deepest integer format the device accepts from 24-in-32,
  24, 16 and float), `LibusbUacSink` (a USB DAC driven directly, bound to
  WinUSB) and `JavaSoundSink` (the fallback, and what the Linux smoke test
  plays through). `AudioOutputController` persists the listener's choice,
  follows WASAPI's endpoint notifications, and falls back to the default
  device while the chosen one is unplugged.

## Platform integration

- **Native libraries** are loaded by absolute path from the installer's
  resources folder (`NativeLibraries.load`). `System.loadLibrary` only
  searches `java.library.path`, which is fixed at JVM start, so every loader
  ported from Android goes through `NativeLibraries.load` instead.
- **Graphics**: AGSL is Skia's SkSL under another name, and Compose for
  Desktop draws with Skia. `android.graphics.RuntimeShader`, `RenderEffect`
  and `BitmapShader` are therefore thin shims over Skia's `RuntimeEffect` and
  `ImageFilter`. The liquid-glass shaders and their uniforms run unchanged,
  which is what keeps `ui-invariants.md` true on Windows.
- **Android's system UI** maps onto the desktop:
  - Toasts are drawn by the window.
  - `ActivityResultContracts` pickers open the native Windows dialogs and
    return `file:` URIs, which the `ContentResolver` shim reads.
  - `ACTION_VIEW` opens the browser or Explorer, and `ACTION_SEND` copies to
    the clipboard.
  - `BackHandler` is the Escape key.
- **Windows-only additions**:
  - System Media Transport Controls (`native/smtc`, C++/WinRT, MSVC only)
    give the app media keys, the volume flyout's "now playing" card and
    headset buttons, which Android got from its MediaSession.
  - `WindowChrome` colours the title bar to match the theme through DWM.
  - Download notifications go to the system tray.

## Verification

- The native DSP has host tests, run on Linux, under the sanitizers in CI and
  with MSVC on Windows.
- The Windows DLLs, cross-built with MinGW, have been run under Wine with
  the Windows JRE. The WASAPI library enumerates endpoints, negotiates
  formats, and opens and plays a shared stream with a correct played-frame
  clock.
- Exclusive mode cannot be exercised there: Wine does not implement
  exclusive mode with event callbacks. So exclusive mode is verified only on
  real Windows hardware.

## Resources and strings

`app/l10n/strings.tsv` is the same translation table as the Android app's (seven
languages). `generate_strings.py` here writes `resources/i18n/strings_<lang>.json`
and `R.kt`; `res/Strings.kt` resolves and formats with `String.format`, exactly
as Android's `getString` does (`%1$s`, `%2$d`, `%1$.1f`), and selects plural
forms with the same CLDR rules the generator checks. Compose Multiplatform's own
string resources are not used: they format differently and only from a
composable, and 36 call sites read strings from ViewModels and repositories.
Fonts and the `assets/` tree are plain JVM resources read through the `Context`
shim; the 28 vector icons are Compose resources behind `R.drawable`.

## Migration method

Sources are copied from the Android app with the package rename into
`port/pending/`, and moved into `app/src/main/kotlin` in waves, bottom-up
(domain → audio → data → player → ui), each wave compiled and its JVM tests
carried over. A file moves when its dependencies are present; a file that needs
a desktop implementation (REWRITE) gets one; Android-only files (widget, Auto,
foreground services, MediaStore, permissions UI) are dropped and recorded here.
`port/pending/` is therefore the honest list of what is not yet ported.

Rules carried over from the Android repo: `docs/ui-invariants.md` describes the
accepted look and must stay true (the glass material, the search bars, the one
pager, the row heights, the themes); `docs/agent-playbook.md` carries the
realtime-audio and cloud-sync invariants. Commits are authored by `tryptz`, with
no tool attribution.

## Keeping up with Android

The port was taken from Android at `5340a92` ("Local library: optional
titles from file names", 4 October 2026) and has since been brought up to
**`e927efd`** (1.9.3, 8 October 2026). The next merge starts from that
commit: update this line when it is done.

A merge is a three-way merge per file, the same thing `git merge` does,
with the package renamed on the Android side:

- *base*: the Android file at the last synced commit, renamed;
- *theirs*: the Android file now, renamed;
- *ours*: the file here.

`git merge-file ours base theirs` then carries Android's changes over the
desktop's own. A file the port never changed simply takes Android's version;
a conflict is where both sides edited the same lines, and is resolved by
hand. Files Android added are copied (renamed), and each one that needs a
system service, a content resolver or MediaCodec gets a desktop version or
goes to `port/dropped/` with a header saying why. A change Android made in
a file the port replaced (`PlaybackService`, `LibusbAudioSink`,
`MainActivity`, `DownloadQueueWorker`) is carried into the replacement by
hand (`EngineController`, `LibusbUacSink`, `MonochromeNavHost`,
`DownloadQueueRunner`), or recorded as Android-only. Afterwards, read the
merged code for Android-only behaviour that compiles anyway: the compiler
catches a missing API, not a setting that does nothing on Windows.

What the 1.9.3 merge left Android-only: "Play alongside other apps" (no
audio focus on Windows), the previous process's native-crash report
(ApplicationExitInfo), the DAC as the media session's remote volume and the
one-time player-volume reset that went with it (Ctrl+Up/Down reach the DAC
here instead), FloatPcmGuard and SourceDepthMediaCodecAdapterFactory
(MediaCodec), SafPaths (document links), and the tilt sliders for the new
lyric glass and god rays (no tilt sensor). Per-device AutoEQ recognises USB
DACs only, by product name: Windows does not say whether an endpoint is a
speaker or a headphone.

## Status

- **Done**: native layer builds for Linux and cross-compiles to six Windows
  DLLs; 28 native tests pass; Gradle scaffold compiles (Kotlin 2.4.20 /
  CMP 1.12.1 / KSP / Dagger / Room on the classpath); translation pipeline and
  `R` class; Media3, Log, Uri, Context, Hilt shims; Dagger ViewModel factory;
  the `domain` package and the pure/shim-only files of `audio.*` moved.
- **In flight**: compile-driven migration of `audio`, `data`, `player`, `ui`;
  the `PlaybackEngine`; the WASAPI sink's Kotlin side; `AppComponent` with the
  Android modules.
- **Not started**: FFmpeg decoder, library scanner over folders, OAuth loopback,
  projectM offscreen renderer, installer (`packageMsi`) and the Windows CI
  workflow.
