#pragma once
// Desktop stand-in for the NDK's <android/log.h>.
//
// The native sources are shared with the Android app and log through
// __android_log_print. On desktop the same calls go to stderr (and, on
// Windows, to the debugger through OutputDebugStringA) so the sources stay
// byte-identical to Android apart from the JNI package prefix.
#include <cstdarg>
#include <cstdio>

#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#endif

#define ANDROID_LOG_UNKNOWN 0
#define ANDROID_LOG_DEFAULT 1
#define ANDROID_LOG_VERBOSE 2
#define ANDROID_LOG_DEBUG 3
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_ERROR 6
#define ANDROID_LOG_FATAL 7

static inline int __android_log_print(int prio, const char* tag, const char* fmt, ...) {
    static const char levels[] = {'?', '?', 'V', 'D', 'I', 'W', 'E', 'F'};
    const char level = (prio >= 0 && prio < 8) ? levels[prio] : '?';
    char line[2048];
    int n = std::snprintf(line, sizeof line, "%c/%s: ", level, tag ? tag : "native");
    if (n < 0) return 0;
    if (n > static_cast<int>(sizeof line) - 2) n = static_cast<int>(sizeof line) - 2;
    std::va_list args;
    va_start(args, fmt);
    std::vsnprintf(line + n, sizeof line - static_cast<size_t>(n) - 1, fmt, args);
    va_end(args);
    std::fputs(line, stderr);
    std::fputc('\n', stderr);
#ifdef _WIN32
    OutputDebugStringA(line);
    OutputDebugStringA("\n");
#endif
    return 1;
}
