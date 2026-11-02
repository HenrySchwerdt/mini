#if defined(_WIN32)

#ifndef _CRT_DECLARE_NONSTDC_NAMES
#define _CRT_DECLARE_NONSTDC_NAMES 0
#endif
#include <io.h>

/* The generated C backend calls the POSIX spelling `isatty`; keep that symbol
 * available without asking UCRT to declare its nonstandard alias. */
int isatty(int fd) { return _isatty(fd); }

#endif
