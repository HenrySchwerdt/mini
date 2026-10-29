/* B1: file access and program arguments for the self-hosted backend driver
 * (stdlib/mini/internal/B1Intrinsics.kt). Convention: arguments are borrowed,
 * reference results are owned (+1). */
#include "internal.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* The path as a NUL-terminated UTF-8 buffer (malloc'ed). */
static char *path_to_c(const MkString *path) {
    if (path == NULL) mk_panic("NullPointerException");
    return mk_utf16_to_utf8(path->chars, (size_t)path->length, NULL);
}

MkString *mk_b1_read_file(const MkString *path) {
    char *p = path_to_c(path);
    FILE *f = fopen(p, "rb");
    free(p);
    if (f == NULL) return NULL;
    size_t cap = 4096, len = 0;
    char *buf = malloc(cap);
    if (buf == NULL) mk_panic("OutOfMemoryError");
    for (;;) {
        if (len == cap) {
            cap *= 2;
            char *nb = realloc(buf, cap);
            if (nb == NULL) mk_panic("OutOfMemoryError");
            buf = nb;
        }
        size_t n = fread(buf + len, 1, cap - len, f);
        len += n;
        if (n == 0) break;
    }
    int failed = ferror(f);
    fclose(f);
    if (failed) {
        free(buf);
        return NULL;
    }
    MkString *s = mk_string_from_utf8(buf, len);
    free(buf);
    return s;
}

MkBoolean mk_b1_write_file(const MkString *path, const MkString *text) {
    if (text == NULL) mk_panic("NullPointerException");
    char *p = path_to_c(path);
    FILE *f = fopen(p, "wb");
    free(p);
    if (f == NULL) return 0;
    size_t len = 0;
    char *u = mk_utf16_to_utf8(text->chars, (size_t)text->length, &len);
    size_t written = len == 0 ? 0 : fwrite(u, 1, len, f);
    free(u);
    int ok = written == len;
    if (fclose(f) != 0) ok = 0;
    return ok ? 1 : 0;
}

static int b1_argc = 0;
static char **b1_argv = NULL;

void mk_b1_set_args(int argc, char **argv) {
    b1_argc = argc;
    b1_argv = argv;
}

MkObject *mk_b1_args(void) {
    MkInt n = b1_argc > 1 ? (MkInt)(b1_argc - 1) : 0;
    MkObject *array = mk_array_new_obj(n);
    for (MkInt i = 0; i < n; i++) {
        const char *a = b1_argv[i + 1];
        MkString *s = mk_string_from_utf8(a, strlen(a));
        mk_array_set_obj(array, i, &s->header);
        mk_release(&s->header);
    }
    return array;
}
