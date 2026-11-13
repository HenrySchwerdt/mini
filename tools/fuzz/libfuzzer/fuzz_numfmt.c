/* libFuzzer harness: Double/Float -> String (runtime/src/numfmt.c) and String -> Double (mk_string_is_double / mk_string_parse_double). */
#include "internal.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

static void fail(const char *what, const char *text) {
    fprintf(stderr, "fuzz_numfmt: invariant violated: %s (%s)\n", what, text);
    abort();
}

int LLVMFuzzerInitialize(int *argc, char ***argv) {
    (void)argc;
    (void)argv;
    mk_runtime_init();
    return 0;
}

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    /* the bit pattern of a double / float: the text parses back to the same value */
    if (size >= 8) {
        double v;
        memcpy(&v, data, 8);
        char buf[64];
        size_t n = mk_format_double(v, buf, sizeof buf);
        if (n >= sizeof buf || buf[n] != '\0' || strlen(buf) != n) fail("double text length", buf);
        MkString *s = mk_string_from_double(v);
        MkString *t = mk_string_from_utf8(buf, n);
        if (mk_string_compare(s, t) != 0) fail("mk_string_from_double differs", buf);
        if (!mk_string_is_double(t)) fail("formatted double not accepted by is_double", buf);
        double back = mk_string_parse_double(t);
        if (isnan(v) ? !isnan(back) : (back != v || signbit(back) != signbit(v))) fail("double does not round-trip", buf);
        mk_release(&s->header);
        mk_release(&t->header);
    }
    if (size >= 4) {
        float f;
        memcpy(&f, data, 4);
        char buf[64];
        size_t n = mk_format_float(f, buf, sizeof buf);
        if (n >= sizeof buf || buf[n] != '\0' || strlen(buf) != n) fail("float text length", buf);
        if (!isnan(f) && !isinf(f)) {
            float back = strtof(buf, NULL);
            if (back != f || signbit(back) != signbit(f)) fail("float does not round-trip", buf);
        }
    }
    /* arbitrary text (UTF-8 decoded into a String, and as raw UTF-16 units) goes through the parser */
    MkString *s = mk_string_from_utf8((const char *)data, size);
    if (mk_string_is_double(s)) (void)mk_string_parse_double(s);
    mk_release(&s->header);
    size_t units = size / 2;
    MkString *w = mk_string_alloc((MkInt)units);
    for (size_t i = 0; i < units; i++) w->chars[i] = (MkChar)(data[2 * i] | (data[2 * i + 1] << 8));
    if (mk_string_is_double(w)) (void)mk_string_parse_double(w);
    mk_release(&w->header);
    return 0;
}
