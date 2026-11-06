/* Windows CI links this callback with runtime/src/entry.c and launches the
 * resulting wmain executable with a BMP argument and an emoji argument. The
 * POSIX runtime suite uses the same checks with synthetic UTF-8 argv. */
#include "../src/internal.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const MkChar expected_bmp[] = {'c', 'a', 'f', 0x00e9};
static const MkChar expected_emoji[] = {'e', 'm', 'o', 'j', 'i', 0xd83d, 0xde00};

static int units_equal_n(const MkString *s, const MkChar *e, size_t n) {
    if ((size_t)s->length != n) return 0;
    for (size_t i = 0; i < n; i++) if (mk_string_at(s, (MkInt)i) != e[i]) return 0;
    return 1;
}
#define units_equal(s, e) units_equal_n((s), (e), sizeof(e) / sizeof((e)[0]))

static void fail(const char *phase, const char *reason) {
    fprintf(stderr, "test_entry_args: %s: %s\n", phase, reason);
    abort();
}

static void check_args(const char *phase) {
    MkObject *array = mk_b1_args();
    if (array == NULL) fail(phase, "mk_b1_args returned NULL");
    if (mk_array_length(array) != 2) fail(phase, "expected two command-line arguments");

    MkObject *bmp_object = mk_array_get_obj(array, 0);
    MkObject *emoji_object = mk_array_get_obj(array, 1);
    if (bmp_object == NULL || emoji_object == NULL) fail(phase, "argument was NULL");

    MkString *bmp = (MkString *)bmp_object;
    MkString *emoji = (MkString *)emoji_object;
    if (bmp->length != (MkInt)(sizeof(expected_bmp) / sizeof(expected_bmp[0])) ||
        !units_equal(bmp, expected_bmp)) {
        fail(phase, "BMP argument did not preserve its UTF-16 code units");
    }
    if (emoji->length != (MkInt)(sizeof(expected_emoji) / sizeof(expected_emoji[0])) ||
        !units_equal(emoji, expected_emoji)) {
        fail(phase, "emoji argument did not preserve its surrogate pair");
    }

    mk_release(bmp_object);
    mk_release(emoji_object);
    mk_release(array);
}

static void check_args_at_exit(void) { check_args("atexit"); }

void mk_main(void) {
    check_args("mk_main");
    if (atexit(check_args_at_exit) != 0) fail("mk_main", "atexit registration failed");
}

#if !defined(_WIN32)
int main(void) {
    static char bmp[] = "caf\xc3\xa9";
    static char emoji[] = "emoji\xf0\x9f\x98\x80";
    static char *argv[] = {"test_entry_args", bmp, emoji, NULL};
    mk_b1_set_args((int)(sizeof(argv) / sizeof(argv[0])) - 1, argv);
    mk_runtime_init();
    mk_main();
    mk_runtime_flush();
    return 0;
}
#endif
