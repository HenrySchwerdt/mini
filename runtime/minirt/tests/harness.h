/* Tiny assert harness: no dependencies. */
#ifndef MINIRT_TEST_HARNESS_H
#define MINIRT_TEST_HARNESS_H

/* fork/pipe/waitpid for CHECK_ABORTS need POSIX declarations under strict -std=c17. */
#if !defined(_WIN32) && !defined(_POSIX_C_SOURCE) && !defined(_GNU_SOURCE)
#define _POSIX_C_SOURCE 200809L
#endif

#include <stdio.h>
#include <string.h>
#ifndef _WIN32
#include <signal.h>
#include <sys/wait.h>
#include <unistd.h>
#endif

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

#ifndef _WIN32
/* Runs fn(arg) in a forked child and expects it to abort (mk_panic) with a stderr
 * message containing `needle`. Returns 1 on success. */
static inline int t_aborts_with(void (*fn)(void *), void *arg, const char *needle) {
    fflush(NULL);
    int fds[2];
    if (pipe(fds) != 0) return 0;
    pid_t pid = fork();
    if (pid < 0) return 0;
    if (pid == 0) {
        close(fds[0]);
        dup2(fds[1], 2);
        fn(arg);
        _exit(0); /* returned instead of aborting */
    }
    close(fds[1]);
    char buf[2048];
    size_t len = 0;
    ssize_t n;
    while (len < sizeof buf - 1 && (n = read(fds[0], buf + len, sizeof buf - 1 - len)) > 0)
        len += (size_t)n;
    buf[len] = '\0';
    close(fds[0]);
    int status = 0;
    waitpid(pid, &status, 0);
    int ok = WIFSIGNALED(status) && WTERMSIG(status) == SIGABRT && strstr(buf, needle) != NULL;
    if (!ok) fprintf(stderr, "  expected abort containing \"%s\", status %d, stderr: %s\n", needle, status, buf);
    return ok;
}
#define CHECK_ABORTS(fn, arg, needle) CHECK(t_aborts_with((fn), (arg), (needle)))
#else
#define CHECK_ABORTS(fn, arg, needle) ((void)0) /* no fork on Windows */
#endif

#define TEST_DONE()                                                                     \
    do {                                                                                \
        fprintf(stderr, "%s: %d checks, %d failures\n", __FILE__, t_checks, t_failures);\
        return t_failures ? 1 : 0;                                                      \
    } while (0)

#endif
