# Cross-compile the native libraries to Windows x64 from Linux with MinGW-w64.
#
#   cmake -S native -B build/win64 -G Ninja \
#       -DCMAKE_TOOLCHAIN_FILE=native/cmake/toolchain-mingw-w64-x86_64.cmake \
#       -DCMAKE_BUILD_TYPE=Release
#
# The posix thread variant is used so std::thread / std::mutex work, and the
# GCC/libstdc++/winpthread runtimes are linked statically into each DLL so a
# machine without MinGW installed can load them (see TF_DESKTOP_STATIC_RUNTIME).
set(CMAKE_SYSTEM_NAME Windows)
set(CMAKE_SYSTEM_PROCESSOR x86_64)
set(TOOLCHAIN_PREFIX x86_64-w64-mingw32)
find_program(TF_MINGW_GCC NAMES ${TOOLCHAIN_PREFIX}-gcc-posix ${TOOLCHAIN_PREFIX}-gcc)
find_program(TF_MINGW_GXX NAMES ${TOOLCHAIN_PREFIX}-g++-posix ${TOOLCHAIN_PREFIX}-g++)
set(CMAKE_C_COMPILER ${TF_MINGW_GCC})
set(CMAKE_CXX_COMPILER ${TF_MINGW_GXX})
set(CMAKE_RC_COMPILER ${TOOLCHAIN_PREFIX}-windres)
set(CMAKE_FIND_ROOT_PATH /usr/${TOOLCHAIN_PREFIX})
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)
set(TF_MINGW_CROSS ON)
