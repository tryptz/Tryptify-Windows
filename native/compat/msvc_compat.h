// Force-included into every MSVC compile (native/CMakeLists.txt).
//
// MSVC's <math.h> defines the SVID matherr codes DOMAIN, SING, OVERFLOW,
// UNDERFLOW, TLOSS and PLOSS as object-like macros. The DSP code uses those
// names as enum values (the shaper's OVERFLOW parameter), which GCC and Clang
// never see. Including <math.h> here first and undefining them keeps the
// shared sources identical to Android's: the header's include guard stops
// a later #include <math.h> from defining them again.
#pragma once
#ifndef _USE_MATH_DEFINES
#define _USE_MATH_DEFINES
#endif
#include <math.h>
#undef DOMAIN
#undef SING
#undef OVERFLOW
#undef UNDERFLOW
#undef TLOSS
#undef PLOSS

// clock_gettime(CLOCK_MONOTONIC, ...) for the scope's frame clock: MinGW gets
// it from winpthreads, MSVC has no equivalent, so steady_clock stands in.
#ifdef __cplusplus
#include <chrono>
#include <ctime>
#ifndef CLOCK_MONOTONIC
#define CLOCK_MONOTONIC 1
static inline int clock_gettime(int, struct timespec* ts) {
    const auto ns = std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
    ts->tv_sec = static_cast<time_t>(ns / 1000000000);
    ts->tv_nsec = static_cast<long>(ns % 1000000000);
    return 0;
}
#endif
#endif
