/* libFuzzer harness: UTF-8 decoding and encoding of the runtime (runtime/src/utf.c, mk_string_from_utf8, mk_string_to_utf8). */
#include "internal.h"

#include <stdlib.h>
#include <string.h>

static void fail(const char *what) {
    fprintf(stderr, "fuzz_utf8: invariant violated: %s\n", what);
    abort();
}

int LLVMFuzzerInitialize(int *argc, char ***argv) {
    (void)argc;
    (void)argv;
    mk_runtime_init();
    return 0;
}

static int same_chars(const MkString *a, const MkString *b) {
    if (a->length != b->length) return 0;
    for (MkInt i = 0; i < a->length; i++)
        if (mk_string_at(a, i) != mk_string_at(b, i)) return 0;
    return 1;
}

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    /* 1. the decoder alone: every step is 1..4 bytes within the input and yields a scalar value or U+FFFD */
    for (const uint8_t *p = data; p < data + size;) {
        uint32_t cp = 0;
        size_t n = mk_utf8_decode(p, data + size, &cp);
        if (n < 1 || n > 4 || n > (size_t)(data + size - p)) fail("decode step out of range");
        if (cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) fail("decode produced a non-scalar value");
        p += n;
    }

    /* 2. bytes -> String -> bytes -> String is stable, and the encoder output is well-formed UTF-8 */
    MkString *s = mk_string_from_utf8((const char *)data, size);
    size_t n8 = 0;
    char *u8 = mk_string_to_utf8(s, &n8);
    if (u8[n8] != '\0') fail("encoder output not NUL-terminated");
    for (const uint8_t *p = (const uint8_t *)u8; p < (const uint8_t *)u8 + n8;) {
        uint32_t cp = 0;
        size_t n = mk_utf8_decode(p, (const uint8_t *)u8 + n8, &cp);
        if (cp == 0xFFFD && !(n == 3 && p[0] == 0xEF && p[1] == 0xBF && p[2] == 0xBD)) fail("encoder output is not well-formed");
        p += n;
    }
    MkString *s2 = mk_string_from_utf8(u8, n8);
    if (!same_chars(s, s2)) fail("round trip changed the string");
    free(u8);
    mk_release(&s2->header);
    mk_release(&s->header);

    /* 3. arbitrary UTF-16 (lone surrogates included) -> UTF-8 -> String: never longer than 3 bytes per unit, stable afterwards */
    size_t units = size / 2;
    MkChar *chars = malloc(units * sizeof(MkChar) + 1);
    for (size_t i = 0; i < units; i++) chars[i] = (MkChar)(data[2 * i] | (data[2 * i + 1] << 8));
    size_t m = 0;
    char *e = mk_utf16_to_utf8(chars, units, &m);
    if (m > units * 3) fail("encoding longer than 3 bytes per unit");
    MkString *t = mk_string_from_utf8(e, m);
    size_t m2 = 0;
    char *e2 = mk_string_to_utf8(t, &m2);
    if (m2 != m || memcmp(e, e2, m) != 0) fail("UTF-16 -> UTF-8 not stable");
    MkString *w = mk_string_alloc((MkInt)units);
    for (size_t i = 0; i < units; i++) w->chars[i] = chars[i];
    size_t m3 = 0;
    char *e3 = mk_string_to_utf8(w, &m3);
    if (m3 != m || memcmp(e, e3, m) != 0) fail("mk_string_to_utf8 and mk_utf16_to_utf8 differ");
    free(e3);
    free(e2);
    free(e);
    free(chars);
    mk_release(&w->header);
    mk_release(&t->header);
    return 0;
}
