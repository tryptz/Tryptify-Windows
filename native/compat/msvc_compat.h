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
