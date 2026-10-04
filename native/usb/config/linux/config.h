/* libusb configuration for the Linux (usbfs + netlink) backend used by the
 * desktop Linux build we test on. Same shape as libusb's android/config.h. */
#pragma once
#define DEFAULT_VISIBILITY __attribute__ ((visibility ("default")))
#define ENABLE_LOGGING 1
#define HAVE_ASM_TYPES_H 1
#define HAVE_CLOCK_GETTIME 1
#define HAVE_NFDS_T 1
#define HAVE_PIPE2 1
#define HAVE_SYS_TIME_H 1
#define HAVE_EVENTFD 1
#define HAVE_TIMERFD 1
#define PLATFORM_POSIX 1
#define PRINTF_FORMAT(a, b) __attribute__ ((__format__ (__printf__, a, b)))
#define _GNU_SOURCE 1
