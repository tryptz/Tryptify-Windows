/* Machine-dependent JNI definitions for Windows x64, used when cross-compiling
 * the JNI libraries from Linux with MinGW-w64 (the Linux JDK only ships the
 * linux/ variant). Mirrors what every Windows JDK's include/win32/jni_md.h
 * declares; it is the ABI, not an implementation. */
#ifndef _JAVASOFT_JNI_MD_H_
#define _JAVASOFT_JNI_MD_H_

#ifndef JNIEXPORT
#define JNIEXPORT __declspec(dllexport)
#endif
#ifndef JNIIMPORT
#define JNIIMPORT __declspec(dllimport)
#endif
#define JNICALL __stdcall

typedef int jint;
typedef long long jlong;
typedef signed char jbyte;

#endif
