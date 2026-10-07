# Tryptify for Windows

The Windows desktop build of [Tryptify](https://github.com/tryptz/Tryptify), the
hi-fi music player. It is the Android app's own code, compiled for the desktop
JVM with Compose Multiplatform, on a native audio stack written for Windows:

- **Playback** decodes with FFmpeg and runs the app's DSP chain: the mixer,
  AutoEQ, the parametric EQ, the crossfeed and Oxford stages, Atmos rendering,
  speed and pitch. The output goes through **WASAPI**:
  - *shared* mode goes through the Windows mixer;
  - *exclusive* mode is bit-perfect: the stream reaches the DAC at its own rate
    and depth;
  - a USB DAC can also be driven directly over libusb.
- **The same screens** as on Android: the glass player, lyrics, the visualizer
  (projectM / MilkDrop), Discover, world radio, the library, downloads and
  settings.
- **Windows integration**:
  - media keys and the "now playing" card in the volume flyout (System Media
    Transport Controls);
  - a title bar that follows the theme;
  - native file dialogs;
  - tray notifications.

## Install

Download the `.msi` from the [releases](../../releases) and run it. Releases
are built by GitHub Actions from tagged commits (`.github/workflows/windows.yml`).

### "Failed to launch JVM"

That dialog comes from jpackage's launcher, and it means only that the app
exited with an error before its window opened; it says nothing about which
error. Since the fix in `Startup.kt`, the app shows its own dialog with the
real cause instead and writes it to
`%LOCALAPPDATA%\Tryptify\data\logs\startup-crash.log`. If you still get
the launcher's dialog, the JVM itself did not start: an install path or
Windows user name with characters outside ASCII (or an `&`) is the usual
reason; the Java Access Bridge being enabled is another.

The cause the log most often names is a DLL that would not load
(`UnsatisfiedLinkError`). The binaries are not code-signed, so Windows
security may refuse them:

- **Smart App Control** (Windows 11) blocks unsigned DLLs outright. Event
  Viewer, under *Applications and Services Logs > Microsoft > Windows >
  CodeIntegrity > Operational*, logs the blocked file as event 3076 or 3077.
  Smart App Control cannot be turned off per app; it has to be switched off
  in Windows Security > App & browser control.
- **Defender** may quarantine a DLL as a false positive; restore it from
  *Protection history* and add exclusions for the install folder and
  `%LOCALAPPDATA%\Tryptify`.
- **Exploit protection** rules such as "Code integrity guard" or "Arbitrary
  code guard" set for `Tryptify.exe` have the same effect; remove them.

The DLLs the app itself ships are in the install folder. FFmpeg and LWJGL
unpack theirs at first run into `%LOCALAPPDATA%\Tryptify\cache\natives`
(not `%USERPROFILE%\.javacpp` or `%TEMP%`, where such writes look like an
attack), so one exclusion covers everything.

### Exclusive USB output

WASAPI exclusive mode needs nothing extra. Driving a USB DAC directly
(Settings → Audio → Exclusive USB DAC) needs the DAC's audio interface bound
to the WinUSB driver, which [Zadig](https://zadig.akeo.ie/) does. While it is
bound to WinUSB, other apps cannot use the DAC. Rebind it to "USB Audio Class
2.0" (`usbaudio2.sys`) to undo this.

## Build

Requirements:

- JDK 21;
- CMake 3.22+ and Ninja;
- on Windows, Visual Studio 2022 (MSVC);
- the submodules, fetched recursively:

```
git submodule update --init --recursive --depth 1
```

Native libraries (from a "x64 Native Tools" prompt on Windows):

```
cmake -S native -B build/native -G Ninja -DCMAKE_BUILD_TYPE=Release -DTF_OUTPUT_DIR=%CD%\app\resources\windows-x64
cmake --build build/native
ctest --test-dir build/native --output-on-failure
```

Then the app:

```
gradlew :app:run                 # run from source
gradlew :app:test                # unit tests
gradlew :app:packageReleaseMsi   # the installer
```

On Linux, the same commands build the `.so` files into
`app/resources/linux-x64`, and `TRYPTIFY_SMOKE=1 gradlew :app:run` renders one
frame and exits. CI runs that check under Xvfb.

To cross-compile the Windows DLLs from Linux with MinGW-w64, add
`-DCMAKE_TOOLCHAIN_FILE=native/cmake/toolchain-mingw-w64-x86_64.cmake`. That
build leaves out the media-key library, which needs MSVC for C++/WinRT.

## How it is put together

- [`docs/porting-plan.md`](docs/porting-plan.md) explains the approach. The
  Kotlin sources are the Android app's, renamed to `tf.monochrome.desktop`.
  Small shims under the Android package names let them compile unchanged, and
  the platform parts are rewritten.
- [`docs/porting-ui.md`](docs/porting-ui.md) describes how a screen moves over.
- [`docs/ui-invariants.md`](docs/ui-invariants.md) describes the accepted look,
  shared with the Android app. Read it before changing the interface.

Code that has not been ported yet waits under `port/pending`. Code with no
desktop meaning (home-screen widgets, Android Auto) is in `port/dropped`, with
the reason.
