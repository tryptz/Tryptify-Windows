# Tryptify for Windows: notes for agents

This repository is the Windows build of the Android app at
[tryptz/Tryptify](https://github.com/tryptz/Tryptify). Most of its Kotlin is
that app's code, renamed from `tf.monochrome.android` to
`tf.monochrome.desktop`. Read [`docs/porting-plan.md`](docs/porting-plan.md)
first. It explains why the Android imports still compile: shims live under
the Android package names (`app/src/main/kotlin/android`, `androidx`,
`dagger/hilt`).

## Read before changing the UI

[`docs/ui-invariants.md`](docs/ui-invariants.md) is shared with the Android
app. Every rule in it is a bug that was found and fixed, and none of them is
obvious from the code: the glass material, the search bars, the one pager, row
heights, themes, the globe, the visualizer's single owner. The tests that hold
them (`LightSchemesTest`, `CustomSchemeTest`, `GlobeLandClipTest`,
`SettingsSearchIndexTest`, `ListRowHeightTest`, `WaterfallMeshTest`,
`GlassSpectrumTest`) are the guarantee. If one fails, fix the code; never
loosen a threshold.

[`docs/porting-ui.md`](docs/porting-ui.md) lists what keeps working unchanged
on the desktop and what has to change, with the replacements.

## Keep the diff against Android small

A fix made here should be portable back, and the reverse. So:

- keep Android imports that a shim covers;
- keep names, signatures and comments;
- where a feature has no desktop meaning, replace it with a one-line
  `// Desktop: <why>` comment, never a stub that pretends.

Shaders (the AGSL strings) stay byte-identical: the `android.graphics` shim runs
them on Skia, which is what Android ran them on.

## Build and test

```
git submodule update --init --recursive --depth 1
cmake -S native -B build/native -G Ninja -DCMAKE_BUILD_TYPE=Release -DTF_OUTPUT_DIR=$PWD/app/resources/linux-x64
cmake --build build/native && ctest --test-dir build/native --output-on-failure
./gradlew :app:compileKotlin
./gradlew :app:test
TRYPTIFY_SMOKE=1 xvfb-run ./gradlew :app:run     # one frame, then exit
```

Cross-compile the Windows DLLs from Linux with
`-DCMAKE_TOOLCHAIN_FILE=native/cmake/toolchain-mingw-w64-x86_64.cmake`. The
DLLs link the GCC runtime statically, so they import only system DLLs. Check
with `x86_64-w64-mingw32-objdump -p x.dll | grep "DLL Name"`. `monochrome_smtc`
(media keys) needs MSVC for C++/WinRT and is built only by the Windows CI job.

## Checking Windows code without Windows

Wine 9 (`apt install wine64`; the binary is `/usr/lib/wine/wine64`) runs a
Windows JRE (Temurin zip from api.adoptium.net) with the MinGW-built DLLs. It
needs an ALSA null device as the default PCM, so WASAPI has an endpoint:

```
pcm.!default { type plug; slave.pcm "nullsink"; hint { show on description "Null output" } }
pcm.nullsink { type null }
```

That is enough to exercise JNI names, COM setup, device enumeration and
shared-mode WASAPI. Wine does not implement exclusive mode with event
callbacks, so exclusive output is verified only on real hardware.

## Native layer

`native/` is the Android app's `app/src/main/cpp` with the JNI prefix renamed,
plus `wasapi/` and `smtc/`. projectM is patched exactly as on Android (see the
Android repo's AGENTS.md); `KnownCrashPresets.kt` comes from its scan. The
preset pack is committed at `app/src/main/projectm-assets/presets` and is packed
into one zip at build time; never put presets loose under resources.

## Commits

Author as `tryptz`. No co-author trailers and no tool attribution in commit
messages, PR bodies or code comments.
