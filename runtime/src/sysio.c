/* Standard input and clocks for the standard library (stdlib/mini/internal/SysIntrinsics.kt).
 * Convention: arguments are borrowed, reference results are owned (+1). */
#if !defined(_WIN32)
#define _POSIX_C_SOURCE 200809L
#endif
#include "internal.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#endif

/* One line of standard input without its line terminator (`\n` or `\r\n`), or NULL at end of input. */
MkString *mk_sys_read_line(void) {
    mk_runtime_flush();
    size_t cap = 128, len = 0;
    char *buf = malloc(cap);
    if (buf == NULL) mk_panic("OutOfMemoryError");
    int c;
    int any = 0;
    while ((c = getc(stdin)) != EOF) {
        any = 1;
        if (c == '\n') break;
        if (len == cap) {
            cap *= 2;
            char *nb = realloc(buf, cap);
            if (nb == NULL) mk_panic("OutOfMemoryError");
            buf = nb;
        }
        buf[len++] = (char)c;
    }
    if (!any) {
        free(buf);
        return NULL;
    }
    if (len > 0 && buf[len - 1] == '\r') len--;
    MkString *s = mk_string_from_utf8(buf, len);
    free(buf);
    return s;
}

/* Monotonic clock in nanoseconds (measureTime, TimeSource.Monotonic). */
MkLong mk_sys_nano_time(void) {
#if defined(_WIN32)
    LARGE_INTEGER f, c;
    QueryPerformanceFrequency(&f);
    QueryPerformanceCounter(&c);
    return (MkLong)((double)c.QuadPart * 1e9 / (double)f.QuadPart);
#else
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (MkLong)ts.tv_sec * 1000000000LL + (MkLong)ts.tv_nsec;
#endif
}

/* Wall clock in milliseconds since the Unix epoch (default seed of Random). */
MkLong mk_sys_epoch_millis(void) {
#if defined(_WIN32)
    FILETIME ft;
    GetSystemTimeAsFileTime(&ft);
    unsigned long long t = ((unsigned long long)ft.dwHighDateTime << 32) | ft.dwLowDateTime;
    return (MkLong)((t - 116444736000000000ULL) / 10000ULL);
#else
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return (MkLong)ts.tv_sec * 1000LL + (MkLong)(ts.tv_nsec / 1000000L);
#endif
}

MkLong mk_sys_double_bits(MkDouble v) {
    uint64_t u;
    memcpy(&u, &v, sizeof u);
    return (MkLong)u;
}

MkInt mk_sys_float_bits(MkFloat v) {
    uint32_t u;
    memcpy(&u, &v, sizeof u);
    return (MkInt)u;
}
