/* libFuzzer driver for the targets written in mini (targets/*.kt). build.sh compiles one target to C with minic-native and includes that C file
 * here (its functions are static), -DFZ_GEN names the generated file and -DFZ_FN the function `Int fuzzX(String, Int, Int)`.
 *
 * Input: byte 0 = a, byte 1 = b, byte 2 selects how the rest becomes the String (0: UTF-8, 1: UTF-16 code units, 2: one byte per character
 * as a Latin-1 string); the three flavours reach the three string representations of the runtime.
 * An exception that escapes the target is a bug: the exceptions of the documented contract are caught in the target itself. */
#include "internal.h"

#include FZ_GEN

#include <stdlib.h>

int LLVMFuzzerInitialize(int *argc, char ***argv) {
    (void)argc;
    (void)argv;
    mk_runtime_init();
    return 0;
}

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    if (size < 3) return 0;
    MkInt a = data[0], b = data[1];
    const uint8_t *p = data + 3;
    size_t n = size - 3;
    MkString *s;
    switch (data[2] % 3) {
    case 0: s = mk_string_from_utf8((const char *)p, n); break;
    case 1:
        s = mk_string_alloc((MkInt)(n / 2));
        for (size_t i = 0; i < n / 2; i++) s->chars[i] = (MkChar)(p[2 * i] | (p[2 * i + 1] << 8));
        break;
    default:
        s = mk_string_alloc8((MkInt)n);
        for (size_t i = 0; i < n; i++) ((uint8_t *)(void *)s->chars)[i] = p[i];
        break;
    }
    (void)FZ_FN(s, a, b);
    if (mk_pending_exception != NULL) {
        fprintf(stderr, "fuzz_kt: uncaught exception from the target\n");
        mk_report_uncaught();
    }
    mk_release(&s->header);
    mk_release_drain();
    return 0;
}
