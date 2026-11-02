#if defined(_WIN32)

#ifndef _CRT_DECLARE_NONSTDC_NAMES
#define _CRT_DECLARE_NONSTDC_NAMES 0
#endif
#include <io.h>
#define WIN32_LEAN_AND_MEAN
#include <windows.h>

/* The generated C backend calls the POSIX spelling `isatty`; keep that symbol
 * available without asking UCRT to declare its nonstandard alias. */
int isatty(int fd) { return _isatty(fd); }

/* The compiler's ORC-version bridge only inspects its own linked image.
 * These spellings keep that bridge ABI identical to POSIX without emulating
 * general-purpose POSIX library loading on Windows. */
void *dlopen(const char *filename, int flags) {
    (void)flags;
    if (filename != NULL) { SetLastError(ERROR_NOT_SUPPORTED); return NULL; }
    return (void *)GetModuleHandleW(NULL);
}
void *dlsym(void *handle, const char *name) {
    return (void *)GetProcAddress((HMODULE)handle, name);
}

#endif
