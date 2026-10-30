/* B1: file access and program arguments for the self-hosted backend driver
 * (stdlib/mini/internal/B1Intrinsics.kt). Convention: arguments are borrowed,
 * reference results are owned (+1). */
#define _POSIX_C_SOURCE 200809L
#include "internal.h"

#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <sys/stat.h>

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

MkInt mk_b1_file_kind(const MkString *path) {
    char *p = path_to_c(path);
    struct stat st;
    int rc = stat(p, &st);
    free(p);
    if (rc != 0) return 0;
    return S_ISDIR(st.st_mode) ? 2 : 1;
}

static int b1_cmp_names(const void *a, const void *b) { return strcmp(*(char *const *)a, *(char *const *)b); }

MkString *mk_b1_list_dir(const MkString *path) {
    char *p = path_to_c(path);
    DIR *d = opendir(p);
    free(p);
    if (d == NULL) return NULL;
    size_t cap = 64, n = 0, total = 0;
    char **names = malloc(cap * sizeof(char *));
    if (names == NULL) mk_panic("OutOfMemoryError");
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        if (strcmp(e->d_name, ".") == 0 || strcmp(e->d_name, "..") == 0) continue;
        if (n == cap) {
            cap *= 2;
            char **nn = realloc(names, cap * sizeof(char *));
            if (nn == NULL) mk_panic("OutOfMemoryError");
            names = nn;
        }
        size_t l = strlen(e->d_name);
        names[n] = malloc(l + 1);
        if (names[n] == NULL) mk_panic("OutOfMemoryError");
        memcpy(names[n], e->d_name, l + 1);
        total += l + 1;
        n++;
    }
    closedir(d);
    qsort(names, n, sizeof(char *), b1_cmp_names);
    char *buf = malloc(total + 1);
    if (buf == NULL) mk_panic("OutOfMemoryError");
    size_t len = 0;
    for (size_t i = 0; i < n; i++) {
        size_t l = strlen(names[i]);
        memcpy(buf + len, names[i], l);
        len += l;
        if (i + 1 < n) buf[len++] = '\n';
        free(names[i]);
    }
    free(names);
    MkString *s = mk_string_from_utf8(buf, len);
    free(buf);
    return s;
}

MkBoolean mk_b1_mkdirs(const MkString *path) {
    char *p = path_to_c(path);
    size_t n = strlen(p);
    for (size_t i = 1; i <= n; i++) {
        if (p[i] != '/' && p[i] != '\0') continue;
        char saved = p[i];
        p[i] = '\0';
        if (mkdir(p, 0777) != 0 && errno != EEXIST) { free(p); return 0; }
        p[i] = saved;
    }
    struct stat st;
    int ok = stat(p, &st) == 0 && S_ISDIR(st.st_mode);
    free(p);
    return ok ? 1 : 0;
}

MkBoolean mk_b1_delete(const MkString *path) {
    char *p = path_to_c(path);
    int rc = remove(p);
    free(p);
    return rc == 0 ? 1 : 0;
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

/* Writes `text` (UTF-8) to standard error, after flushing the buffered standard output so the two streams stay in order. */
void mk_b1_eprint(const MkString *text) {
    if (text == NULL) mk_panic("NullPointerException");
    mk_runtime_flush();
    char *u = mk_utf16_to_utf8(text->chars, (size_t)text->length, NULL);
    fputs(u, stderr);
    free(u);
    fflush(stderr);
}

/* Ends the process with `status` (standard output flushed first). */
void mk_b1_exit(MkInt status) {
    mk_runtime_flush();
    fflush(stderr);
    exit((int)status);
}
