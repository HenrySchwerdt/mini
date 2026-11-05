/* Program entry. Kept separate so tests can link libminirt without `main`. */
#include "internal.h"

#if defined(_WIN32)

#ifndef _CRT_DECLARE_NONSTDC_NAMES
#define _CRT_DECLARE_NONSTDC_NAMES 0
#endif
#include <fcntl.h>
#include <io.h>
#include <wchar.h>

_Static_assert(sizeof(wchar_t) == 2, "Windows wchar_t must be one UTF-16 code unit");
_Static_assert(sizeof(MkChar) == 2, "MkChar must be one UTF-16 code unit");

static const MkChar **owned_argv;
static size_t owned_argc;

static void free_owned_argv(void) {
    for (size_t i = 0; i < owned_argc; i++) free((void *)owned_argv[i]);
    free((void *)owned_argv);
    owned_argv = NULL;
    owned_argc = 0;
}

static void set_binary_stdio(void) {
    (void)_setmode(_fileno(stdin), _O_BINARY);
    (void)_setmode(_fileno(stdout), _O_BINARY);
    (void)_setmode(_fileno(stderr), _O_BINARY);
}

static int copy_wide_argv(int argc, wchar_t **argv) {
    if (argc < 0 || (size_t)argc > SIZE_MAX / sizeof(*owned_argv)) return 0;
    if (argc == 0) return 1;

    owned_argv = malloc((size_t)argc * sizeof(*owned_argv));
    if (owned_argv == NULL) return 0;

    for (int i = 0; i < argc; i++) {
        if (argv[i] == NULL) return 0;
        size_t length = 0;
        while (argv[i][length] != L'\0') {
            if (length >= SIZE_MAX / sizeof(MkChar) - 1) return 0;
            length++;
        }

        MkChar *units = malloc((length + 1) * sizeof(*units));
        if (units == NULL) return 0;
        for (size_t j = 0; j < length; j++) units[j] = (MkChar)argv[i][j];
        units[length] = 0;
        owned_argv[i] = units;
        owned_argc++;
    }
    return 1;
}

int wmain(int argc, wchar_t **argv) {
    (void)_set_abort_behavior(0, _CALL_REPORTFAULT);
    set_binary_stdio();
    if (!copy_wide_argv(argc, argv)) {
        free_owned_argv();
        fputs("mini: cannot copy command-line arguments\n", stderr);
        return 2;
    }
    /* Register before user code so its later atexit callbacks run first and
     * can still access the borrowed B1 argv. */
    if (atexit(free_owned_argv) != 0) {
        free_owned_argv();
        fputs("mini: cannot register command-line cleanup\n", stderr);
        return 2;
    }

    mk_b1_set_args_utf16(argc, owned_argv);
    mk_runtime_init();
    mk_main();
    mk_release_drain();
    mk_cycles_at_exit();
    mk_runtime_flush();
    return 0;
}

#else

int main(int argc, char **argv) {
    mk_b1_set_args(argc, argv);
    mk_runtime_init();
    mk_main();
    mk_release_drain();
    mk_cycles_at_exit();
    mk_runtime_flush();
    return 0;
}

#endif
