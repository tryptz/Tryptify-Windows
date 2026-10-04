/* libusb configuration for the Windows backend, built with MinGW-w64 or MSVC
 * through our own CMake instead of libusb's autotools / Visual Studio files.
 * Mirrors msvc/config.h without the MSVC-only pragmas. */
#pragma once
#define PLATFORM_WINDOWS 1
#define ENABLE_LOGGING 1
#define DEFAULT_VISIBILITY /**/
#define PRINTF_FORMAT(a, b) /**/
/* MSVC's time.h needs to be told libusb's own timespec is not wanted; MinGW's
 * time.h defines struct timespec itself and must not be suppressed. */
#ifdef _MSC_VER
#define _TIMESPEC_DEFINED 1
#endif
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0A00
#endif
#ifndef WINVER
#define WINVER 0x0A00
#endif
