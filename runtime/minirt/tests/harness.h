/* Tiny assert harness: no dependencies. */
#ifndef MINIRT_TEST_HARNESS_H
#define MINIRT_TEST_HARNESS_H

#include <stdio.h>
#include <string.h>

#include "minirt.h"
#include "../src/internal.h"

static int t_failures = 0;
static int t_checks = 0;

#define CHECK(cond)                                                                     \
    do {                                                                                \
        t_checks++;                                                                     \
        if (!(cond)) {                                                                  \
            t_failures++;                                                               \
            fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #cond);    \
        }                                                                               \
    } while (0)

#define CHECK_EQ_INT(a, b) CHECK((long long)(a) == (long long)(b))

/* Compares a MkString with UTF-8 text; releases `s` if `own`. */
static inline int t_str_is(MkString *s, const char *expected, int own) {
    size_t len;
    char *u = mk_utf16_to_utf8(s->chars, (size_t)s->length, &len);
    int ok = strcmp(u, expected) == 0;
    if (!ok) fprintf(stderr, "  got \"%s\", expected \"%s\"\n", u, expected);
    free(u);
    if (own) mk_release(&s->header);
    return ok;
}
#define CHECK_STR(s, expected) CHECK(t_str_is((s), (expected), 1))

#define TEST_DONE()                                                                     \
    do {                                                                                \
        fprintf(stderr, "%s: %d checks, %d failures\n", __FILE__, t_checks, t_failures);\
        return t_failures ? 1 : 0;                                                      \
    } while (0)

#endif
