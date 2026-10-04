cmake_minimum_required(VERSION 3.22)
project(tryptify_native LANGUAGES C CXX)

# ── Tryptify for Windows: the native layer ──────────────────────────────────
#
# The same C++ that ships in the Android app, built for the desktop JVM:
#
#   monochrome_dsp         the mixing console, Oxford stages, scope, waterfall,
#                          EBU R128 meter
#   monochrome_stretch     signalsmith-stretch pitch shifter
#   monochrome_atmos_jni   Dolby Atmos / E-AC-3 JOC object renderer (+libmysofa)
#   monochrome_usb         libusb UAC1/UAC2 bit-perfect driver
#   monochrome_visualizer  projectM 4.1.6 MilkDrop bridge
#   monochrome_wasapi      NEW: WASAPI exclusive/shared output (Windows only)
#
# Platforms: Windows x64 (MSVC, or MinGW-w64 cross-compiled from Linux with
# cmake/toolchain-mingw-w64-x86_64.cmake) and Linux x64 (what the unit tests
# and the headless smoke test run on). The sources under dsp/, stretch/, atmos/,
# usb/ and the projectM bridge are byte-identical to the Android app except for
# the JNI package prefix (tf.monochrome.desktop), so fixes flow between the two
# repositories with a diff. Android-only includes are satisfied from compat/.
#
#   cmake -S native -B build/native -G Ninja -DCMAKE_BUILD_TYPE=Release
#   cmake --build build/native
#   ctest --test-dir build/native --output-on-failure

option(TF_BUILD_VISUALIZER "Build projectM and the visualizer bridge" ON)
option(TF_BUILD_USB "Build libusb and the UAC driver" ON)
option(TF_BUILD_WASAPI "Build the WASAPI output library (Windows only)" ON)
option(TF_BUILD_TESTS "Build and register the host tests with CTest" ON)
option(TF_STATIC_RUNTIME "Link the GCC runtime statically into each DLL (MinGW)" ON)
set(TF_OUTPUT_DIR "" CACHE PATH "Where to put the finished .dll/.so files (default: build tree)")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_C_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

set(TF_THIRD_PARTY ${CMAKE_CURRENT_SOURCE_DIR}/../third_party)
list(PREPEND CMAKE_MODULE_PATH ${CMAKE_CURRENT_SOURCE_DIR}/cmake)

# ── JNI headers ─────────────────────────────────────────────────────────────
# A host build finds the running JDK. A MinGW cross build uses the Linux JDK's
# platform-independent jni.h with our own win32 jni_md.h (the Linux JDK does
# not ship the Windows one; compat/jni_win32 declares the same five things).
if(CMAKE_CROSSCOMPILING AND WIN32)
    if(NOT DEFINED ENV{JAVA_HOME})
        message(FATAL_ERROR "Set JAVA_HOME to a JDK so jni.h can be found for the cross build")
    endif()
    set(TF_JNI_INCLUDE_DIRS $ENV{JAVA_HOME}/include ${CMAKE_CURRENT_SOURCE_DIR}/compat/jni_win32)
else()
    find_package(JNI REQUIRED)
    set(TF_JNI_INCLUDE_DIRS ${JNI_INCLUDE_DIRS})
endif()

# ── Shared compile settings ─────────────────────────────────────────────────
add_library(tf_common INTERFACE)
target_include_directories(tf_common INTERFACE
    ${TF_JNI_INCLUDE_DIRS}
    ${CMAKE_CURRENT_SOURCE_DIR}/compat        # <android/log.h> stand-in
)
# M_PI and friends are hidden behind _USE_MATH_DEFINES on MSVC and on MinGW in
# strict -std=c++17 mode; the DSP headers use them freely, as the NDK allows.
target_compile_definitions(tf_common INTERFACE _USE_MATH_DEFINES)
if(WIN32)
    target_compile_definitions(tf_common INTERFACE WIN32_LEAN_AND_MEAN NOMINMAX)
endif()
if(MSVC)
    target_compile_options(tf_common INTERFACE /W3 /utf-8)
else()
    target_compile_options(tf_common INTERFACE -Wall -Wno-unused-parameter)
endif()

set(TF_IS_MINGW OFF)
if(WIN32 AND CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang" AND NOT MSVC)
    set(TF_IS_MINGW ON)
endif()

# Realtime DSP flags: the NDK builds these with -O3 -ffast-math -fno-exceptions
# -fno-rtti and the code is written for it (see dsp/util/finite.h: finiteness
# is tested by reading bits, because -ffast-math may fold std::isfinite).
function(tf_dsp_flags target)
    if(MSVC)
        # /EHs-c- + _HAS_EXCEPTIONS=0 is MSVC's -fno-exceptions; /GR- is -fno-rtti.
        target_compile_options(${target} PRIVATE /O2 /fp:fast /GR- /EHs-c- /D_HAS_EXCEPTIONS=0)
    else()
        target_compile_options(${target} PRIVATE -O3 -ffast-math -fno-exceptions -fno-rtti)
    endif()
endfunction()

# Every JNI library: no "lib" prefix on Windows (System.loadLibrary("x") opens
# x.dll there and libx.so on Linux), no debug postfix, the runtime linked in on
# MinGW so the DLL does not need libstdc++-6.dll / libwinpthread-1.dll beside it.
function(tf_jni_library target)
    add_library(${target} SHARED ${ARGN})
    target_link_libraries(${target} PRIVATE tf_common)
    set_target_properties(${target} PROPERTIES DEBUG_POSTFIX "" CXX_VISIBILITY_PRESET hidden C_VISIBILITY_PRESET hidden)
    if(WIN32)
        set_target_properties(${target} PROPERTIES PREFIX "")
    endif()
    if(TF_IS_MINGW AND TF_STATIC_RUNTIME)
        # -static makes ld prefer every archive, winpthread included; the DLL
        # itself is still produced by -shared. Without it the posix-thread
        # toolchain leaves an import of libwinpthread-1.dll behind.
        target_link_options(${target} PRIVATE -static -static-libgcc -static-libstdc++)
    endif()
    if(TF_OUTPUT_DIR)
        set_target_properties(${target} PROPERTIES
            LIBRARY_OUTPUT_DIRECTORY ${TF_OUTPUT_DIR}
            RUNTIME_OUTPUT_DIRECTORY ${TF_OUTPUT_DIR})
    endif()
endfunction()

# ── DSP engine ──────────────────────────────────────────────────────────────
tf_jni_library(monochrome_dsp
    dsp/dsp_engine.cpp
    dsp/dsp_jni.cpp
    dsp/oxford/oxford_jni.cpp
    dsp/scope/wave_scope.cpp
    dsp/scope/spectrum_waterfall_jni.cpp
    dsp/meter/loudness_jni.cpp
)
target_include_directories(monochrome_dsp PRIVATE dsp dsp/util dsp/snapins dsp/oxford)
tf_dsp_flags(monochrome_dsp)

# ── Pitch shifter (signalsmith-stretch, header-only) ───────────────────────
tf_jni_library(monochrome_stretch stretch/stretch_jni.cpp)
target_include_directories(monochrome_stretch PRIVATE ${TF_THIRD_PARTY}/signalsmith-stretch)
tf_dsp_flags(monochrome_stretch)

# ── zlib (for libmysofa's gzip reader) ──────────────────────────────────────
# Compiled from the vendored sources directly rather than through zlib's own
# CMakeLists, which installs headers and renames targets in ways that fight a
# superbuild. Fifteen files; nothing to configure.
set(TF_ZLIB_DIR ${TF_THIRD_PARTY}/zlib)
add_library(tf_zlib STATIC
    ${TF_ZLIB_DIR}/adler32.c ${TF_ZLIB_DIR}/compress.c ${TF_ZLIB_DIR}/crc32.c
    ${TF_ZLIB_DIR}/deflate.c ${TF_ZLIB_DIR}/gzclose.c ${TF_ZLIB_DIR}/gzlib.c
    ${TF_ZLIB_DIR}/gzread.c ${TF_ZLIB_DIR}/gzwrite.c ${TF_ZLIB_DIR}/infback.c
    ${TF_ZLIB_DIR}/inffast.c ${TF_ZLIB_DIR}/inflate.c ${TF_ZLIB_DIR}/inftrees.c
    ${TF_ZLIB_DIR}/trees.c ${TF_ZLIB_DIR}/uncompr.c ${TF_ZLIB_DIR}/zutil.c)
target_include_directories(tf_zlib PUBLIC ${TF_ZLIB_DIR})
if(MSVC)
    target_compile_definitions(tf_zlib PRIVATE _CRT_SECURE_NO_WARNINGS _CRT_NONSTDC_NO_DEPRECATE)
else()
    target_compile_options(tf_zlib PRIVATE -w)
endif()

# ── libmysofa (runtime SOFA HRTF loading) ───────────────────────────────────
set(MYSOFA_DIR ${TF_THIRD_PARTY}/libmysofa)
file(GLOB MYSOFA_SRC ${MYSOFA_DIR}/hrtf/*.c ${MYSOFA_DIR}/hdf/*.c ${MYSOFA_DIR}/resampler/speex_resampler.c)
add_library(mysofa STATIC ${MYSOFA_SRC})
target_include_directories(mysofa PUBLIC ${MYSOFA_DIR}/hrtf ${MYSOFA_DIR}/hdf ${MYSOFA_DIR}/resampler)
target_compile_definitions(mysofa PRIVATE OUTSIDE_SPEEX RANDOM_PREFIX=mysofa _USE_MATH_DEFINES)
if(MSVC)
    target_compile_definitions(mysofa PRIVATE _CRT_SECURE_NO_WARNINGS)
else()
    target_compile_options(mysofa PRIVATE -O2 -std=gnu11 -Wno-unused-parameter -Wno-unused-but-set-variable -Wno-unused-function)
endif()
target_link_libraries(mysofa PUBLIC tf_zlib)

# ── Atmos renderer ──────────────────────────────────────────────────────────
add_library(monochrome_atmos STATIC atmos/atmos.cpp)
target_include_directories(monochrome_atmos PRIVATE atmos)
target_link_libraries(monochrome_atmos PRIVATE tf_common)
tf_dsp_flags(monochrome_atmos)

tf_jni_library(monochrome_atmos_jni
    atmos/atmos_jni.cpp
    atmos/placer_jni.cpp
    atmos/render/sofa_loader.cpp
)
target_include_directories(monochrome_atmos_jni PRIVATE atmos ${MYSOFA_DIR}/hrtf)
# The renderer DSP is header-only and compiled into this target; keep it
# optimised whatever the build type, as the Android build does.
if(MSVC)
    target_compile_options(monochrome_atmos_jni PRIVATE /O2 /fp:fast)
else()
    target_compile_options(monochrome_atmos_jni PRIVATE -O3 -ffast-math)
endif()
target_link_libraries(monochrome_atmos_jni PRIVATE monochrome_atmos mysofa)

# ── libusb + UAC driver ─────────────────────────────────────────────────────
if(TF_BUILD_USB)
    set(LIBUSB_DIR ${TF_THIRD_PARTY}/libusb)
    if(NOT EXISTS ${LIBUSB_DIR}/libusb/core.c)
        message(FATAL_ERROR "third_party/libusb is empty: run `git submodule update --init --recursive`")
    endif()
    set(LIBUSB_COMMON
        ${LIBUSB_DIR}/libusb/core.c ${LIBUSB_DIR}/libusb/descriptor.c
        ${LIBUSB_DIR}/libusb/hotplug.c ${LIBUSB_DIR}/libusb/io.c
        ${LIBUSB_DIR}/libusb/sync.c ${LIBUSB_DIR}/libusb/strerror.c)
    if(WIN32)
        # The WinUSB/libusbK/UsbDk backend; WinUSB is what Zadig installs for a
        # DAC we want to drive past usbaudio2.sys. Hotplug via the newer
        # windows_hotplug.c is optional upstream and left out here.
        set(LIBUSB_OS
            ${LIBUSB_DIR}/libusb/os/events_windows.c ${LIBUSB_DIR}/libusb/os/threads_windows.c
            ${LIBUSB_DIR}/libusb/os/windows_common.c ${LIBUSB_DIR}/libusb/os/windows_winusb.c
            ${LIBUSB_DIR}/libusb/os/windows_usbdk.c)
        set(LIBUSB_CONFIG_DIR ${CMAKE_CURRENT_SOURCE_DIR}/usb/config/windows)
    else()
        set(LIBUSB_OS
            ${LIBUSB_DIR}/libusb/os/events_posix.c ${LIBUSB_DIR}/libusb/os/threads_posix.c
            ${LIBUSB_DIR}/libusb/os/linux_usbfs.c ${LIBUSB_DIR}/libusb/os/linux_netlink.c)
        set(LIBUSB_CONFIG_DIR ${CMAKE_CURRENT_SOURCE_DIR}/usb/config/linux)
    endif()
    add_library(usb1.0 STATIC ${LIBUSB_COMMON} ${LIBUSB_OS})
    target_include_directories(usb1.0 PUBLIC ${LIBUSB_DIR}/libusb PRIVATE ${LIBUSB_DIR}/libusb/os ${LIBUSB_CONFIG_DIR})
    if(MSVC)
        target_compile_definitions(usb1.0 PRIVATE _CRT_SECURE_NO_WARNINGS)
    else()
        target_compile_options(usb1.0 PRIVATE -std=gnu11 -fvisibility=hidden -Wno-unused-parameter -Wno-unused-but-set-variable)
    endif()
    if(WIN32)
        target_link_libraries(usb1.0 PUBLIC setupapi cfgmgr32 ole32 advapi32)
    else()
        find_package(Threads REQUIRED)
        target_link_libraries(usb1.0 PUBLIC Threads::Threads)
    endif()

    tf_jni_library(monochrome_usb usb/usb_jni.cpp usb/libusb_uac_driver.cpp)
    target_include_directories(monochrome_usb PRIVATE usb)
    if(MSVC)
        target_compile_options(monochrome_usb PRIVATE /O2 /GR- /EHs-c- /D_HAS_EXCEPTIONS=0)
    else()
        target_compile_options(monochrome_usb PRIVATE -O3 -fno-exceptions -fno-rtti)
    endif()
    target_link_libraries(monochrome_usb PRIVATE usb1.0)
endif()

# ── WASAPI output (Windows) ─────────────────────────────────────────────────
if(TF_BUILD_WASAPI AND WIN32)
    tf_jni_library(monochrome_wasapi wasapi/wasapi_jni.cpp)
    target_compile_options(monochrome_wasapi PRIVATE $<IF:$<BOOL:${MSVC}>,/O2,-O2>)
    target_link_libraries(monochrome_wasapi PRIVATE ole32 oleaut32 uuid avrt ksuser mmdevapi)
endif()

# ── projectM visualizer ─────────────────────────────────────────────────────
if(TF_BUILD_VISUALIZER)
    set(PROJECTM_DIR ${TF_THIRD_PARTY}/projectm)
    if(NOT EXISTS ${PROJECTM_DIR}/CMakeLists.txt OR NOT EXISTS ${PROJECTM_DIR}/vendor/projectm-eval/CMakeLists.txt)
        message(FATAL_ERROR "third_party/projectm (or its nested vendor/projectm-eval) is empty: run `git submodule update --init --recursive`")
    endif()

    # GLEW, statically, from the vendored glew-cmake. projectM's
    # find_package(GLEW) is answered by cmake/FindGLEW.cmake with this target.
    set(glew-cmake_BUILD_SHARED OFF CACHE BOOL "" FORCE)
    set(glew-cmake_BUILD_STATIC ON CACHE BOOL "" FORCE)
    set(ONLY_LIBS ON CACHE BOOL "" FORCE)
    add_subdirectory(${TF_THIRD_PARTY}/glew ${CMAKE_CURRENT_BINARY_DIR}/glew EXCLUDE_FROM_ALL)
    if(NOT MSVC)
        target_compile_options(libglew_static PRIVATE -w)
    endif()
    # projectM asks for GLEW::glew / GLEW::glew_s only when building for
    # Windows (cmake/FindGLEW.cmake answers that call); on Linux it uses the
    # system GL directly. Make the alias available everywhere so the bridge can
    # link it unconditionally.
    if(NOT TARGET GLEW::glew_s)
        add_library(GLEW::glew_s ALIAS libglew_static)
    endif()

    # projectM's version.h is generated from CMAKE_PROJECT_VERSION_*, which
    # CMake takes from the top-level project() — this one — so pin them to the
    # vendored version, as the Android build does.
    set(CMAKE_PROJECT_VERSION 4.1.6)
    set(CMAKE_PROJECT_VERSION_MAJOR 4)
    set(CMAKE_PROJECT_VERSION_MINOR 1)
    set(CMAKE_PROJECT_VERSION_PATCH 6)
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)      # one DLL: projectM is linked into monochrome_visualizer
    set(ENABLE_PLAYLIST ON CACHE BOOL "" FORCE)
    set(ENABLE_SYSTEM_GLM OFF CACHE BOOL "" FORCE)
    set(ENABLE_SYSTEM_PROJECTM_EVAL OFF CACHE BOOL "" FORCE)
    set(ENABLE_DEBUG_POSTFIX OFF CACHE BOOL "" FORCE)
    set(ENABLE_SDL_UI OFF CACHE BOOL "" FORCE)
    set(ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
    set(ENABLE_GLES OFF CACHE BOOL "" FORCE)             # desktop OpenGL core profile
    set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
    set(BUILD_DOCS OFF CACHE BOOL "" FORCE)
    add_subdirectory(${PROJECTM_DIR} ${CMAKE_CURRENT_BINARY_DIR}/projectm EXCLUDE_FROM_ALL)

    # Crash fixes to projectM's own sources, swapped in without touching the
    # submodule. Same patch set as the Android app.
    include(${CMAKE_CURRENT_SOURCE_DIR}/projectm_patches/projectm_patches.cmake)

    # CMake's generate_export_header only tells the library itself that it is
    # static; every consumer of a static projectM on Windows has to say so too,
    # or its calls are compiled as __imp_ DLL imports and the link fails. The
    # playlist library (its projectM_playlist_main object target) is one, and so
    # is our bridge.
    target_compile_definitions(projectM_playlist_main PRIVATE PROJECTM_STATIC_DEFINE)

    tf_jni_library(monochrome_visualizer
        projectm_bridge.cpp
        projectm_jni.cpp
        audio_ring_buffer.cpp
    )
    target_include_directories(monochrome_visualizer PRIVATE
        ${PROJECTM_DIR}/src/api/include
        ${PROJECTM_DIR}/src/playlist/api
        ${CMAKE_CURRENT_BINARY_DIR}/projectm/src/api/include
        ${CMAKE_CURRENT_BINARY_DIR}/projectm/src/playlist/include
    )
    target_compile_definitions(monochrome_visualizer PRIVATE PROJECTM_STATIC_DEFINE PROJECTM_PLAYLIST_STATIC_DEFINE)
    target_compile_options(monochrome_visualizer PRIVATE $<IF:$<BOOL:${MSVC}>,/O2,-O2>)
    find_package(OpenGL REQUIRED)
    target_link_libraries(monochrome_visualizer PRIVATE projectM_playlist projectM OpenGL::GL)
    if(WIN32)
        target_link_libraries(monochrome_visualizer PRIVATE GLEW::glew_s)
    endif()
endif()

# ── Host tests (CTest) ──────────────────────────────────────────────────────
# The DSP suite that run_host_tests.sh builds by hand, plus the Atmos and
# stretch tests that the Android tree only shipped as source files. The
# sanitizer runs stay in run_host_tests.sh; CTest covers the plain builds on
# every platform, MSVC included.
if(TF_BUILD_TESTS AND NOT CMAKE_CROSSCOMPILING)
    enable_testing()
    set(TF_TEST_RES ${CMAKE_CURRENT_SOURCE_DIR}/../app/src/test/resources)
    function(tf_host_test name)
        cmake_parse_arguments(T "" "" "SOURCES;ARGS;INCLUDES;OPTIONS" ${ARGN})
        add_executable(${name} ${T_SOURCES})
        target_link_libraries(${name} PRIVATE tf_common)
        target_include_directories(${name} PRIVATE dsp dsp/util dsp/snapins dsp/oxford dsp/tests/host_include ${T_INCLUDES})
        target_compile_options(${name} PRIVATE $<IF:$<BOOL:${MSVC}>,/O2,-O2> ${T_OPTIONS})
        if(NOT MSVC)
            find_package(Threads REQUIRED)
            target_link_libraries(${name} PRIVATE Threads::Threads)
        endif()
        add_test(NAME ${name} COMMAND ${name} ${T_ARGS})
    endfunction()
    set(D dsp/tests)
    set(FAST $<IF:$<BOOL:${MSVC}>,/fp:fast,-ffast-math>)
    tf_host_test(wsola_pitch_test SOURCES ${D}/wsola_pitch_test.cpp)
    tf_host_test(multilane_engine_test SOURCES ${D}/multilane_engine_test.cpp dsp/dsp_engine.cpp)
    tf_host_test(oxford_multichannel_test SOURCES ${D}/oxford_multichannel_test.cpp)
    tf_host_test(snapin_fixes_test SOURCES ${D}/snapin_fixes_test.cpp)
    tf_host_test(snapin_defaults_test SOURCES ${D}/snapin_defaults_test.cpp dsp/dsp_engine.cpp ARGS ${TF_TEST_RES}/snapin_defaults.csv)
    tf_host_test(dsp_routing_test SOURCES ${D}/dsp_routing_test.cpp dsp/dsp_engine.cpp)
    tf_host_test(state_fixture_test SOURCES ${D}/state_fixture_test.cpp dsp/dsp_engine.cpp ARGS ${TF_TEST_RES}/mixer_state_fixture.json)
    tf_host_test(oversampler_test SOURCES ${D}/oversampler_test.cpp)
    tf_host_test(loudness_meter_test SOURCES ${D}/loudness_meter_test.cpp OPTIONS ${FAST})
    tf_host_test(spectrum_waterfall_test SOURCES ${D}/spectrum_waterfall_test.cpp OPTIONS ${FAST})
    tf_host_test(engine_stress_test SOURCES ${D}/engine_stress_test.cpp dsp/dsp_engine.cpp ARGS ${TF_TEST_RES}/snapin_ranges.csv 8 OPTIONS ${FAST})
    file(GLOB ATMOS_TESTS ${CMAKE_CURRENT_SOURCE_DIR}/atmos/tests/*.cpp)
    foreach(src ${ATMOS_TESTS})
        get_filename_component(name ${src} NAME_WE)
        tf_host_test(atmos_${name} SOURCES ${src} INCLUDES atmos)
    endforeach()
    tf_host_test(stretch_precision_test SOURCES stretch/tests/stretch_precision_test.cpp INCLUDES ${TF_THIRD_PARTY}/signalsmith-stretch)
endif()
