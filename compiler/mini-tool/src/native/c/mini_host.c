/* The host layer of the native `mini` tool (compiler/mini-tool/src/native/kotlin/mini/tool/native/NativePlatform.kt).
 *
 * `mini` is Kotlin compiled by Mini itself; everything it needs from the operating system (files, directories, environment,
 * processes, terminal) is behind the `Platform` interface and implemented here with plain POSIX calls, so that no structure
 * layout (struct stat, struct dirent) leaks into the Kotlin side and Linux and macOS share one source.
 *
 * Convention of `@CSymbol` (docs/stdlib.md): arguments are borrowed, a reference result is owned (+1), NULL is `null`.
 * Lists travel as one string with U+0001 between the items.
 */
#define _GNU_SOURCE
#include "internal.h"
#include "mini_host.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#ifdef __APPLE__
#include <mach-o/dyld.h>
#endif

#define SEP '\001'

/* The Mini string as a NUL-terminated UTF-8 buffer (malloc'ed); NULL for a null string. */
static char *c_str(const MkString *s) {
    if (s == NULL) return NULL;
    return mk_utf16_to_utf8(s->chars, (size_t)s->length, NULL);
}

static MkString *mk_str(const char *s) { return s == NULL ? NULL : mk_string_from_utf8(s, strlen(s)); }

/* ---- environment -------------------------------------------------------------------------------------------------- */

MkString *mh_env(const MkString *name) {
    char *n = c_str(name);
    const char *v = n == NULL ? NULL : getenv(n);
    free(n);
    return mk_str(v);
}

MkString *mh_cwd(void) {
    char buf[4096];
    return getcwd(buf, sizeof buf) == NULL ? mk_str("/") : mk_str(buf);
}

MkString *mh_exe_path(void) {
    char buf[4096];
#ifdef __APPLE__
    uint32_t n = sizeof buf;
    if (_NSGetExecutablePath(buf, &n) != 0) return NULL;
    char real[4096];
    return mk_str(realpath(buf, real) != NULL ? real : buf);
#else
    ssize_t n = readlink("/proc/self/exe", buf, sizeof buf - 1);
    if (n <= 0) return NULL;
    buf[n] = 0;
    return mk_str(buf);
#endif
}

MkBoolean mh_is_tty(MkInt fd) { return isatty((int)fd) ? 1 : 0; }

MkInt mh_terminal_width(void) {
    struct winsize ws;
    if (ioctl(1, TIOCGWINSZ, &ws) == 0 && ws.ws_col > 0) return (MkInt)ws.ws_col;
    return 0;
}

MkLong mh_now_millis(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (MkLong)ts.tv_sec * 1000 + (MkLong)(ts.tv_nsec / 1000000);
}

/* ---- files -------------------------------------------------------------------------------------------------------- */

/* 0 missing, 1 regular file, 2 directory, 3 other (follows symbolic links). */
MkInt mh_kind(const MkString *path) {
    char *p = c_str(path);
    struct stat st;
    int r = p == NULL ? -1 : stat(p, &st);
    free(p);
    if (r != 0) return 0;
    if (S_ISREG(st.st_mode)) return 1;
    if (S_ISDIR(st.st_mode)) return 2;
    return 3;
}

MkLong mh_size(const MkString *path) {
    char *p = c_str(path);
    struct stat st;
    int r = p == NULL ? -1 : stat(p, &st);
    free(p);
    return r != 0 ? -1 : (MkLong)st.st_size;
}

MkLong mh_mtime_millis(const MkString *path) {
    char *p = c_str(path);
    struct stat st;
    int r = p == NULL ? -1 : stat(p, &st);
    free(p);
    if (r != 0) return -1;
#ifdef __APPLE__
    return (MkLong)st.st_mtimespec.tv_sec * 1000 + (MkLong)(st.st_mtimespec.tv_nsec / 1000000);
#else
    return (MkLong)st.st_mtim.tv_sec * 1000 + (MkLong)(st.st_mtim.tv_nsec / 1000000);
#endif
}

static int cmp_names(const void *a, const void *b) { return strcmp(*(const char *const *)a, *(const char *const *)b); }

/* The entry names of a directory, sorted (bytewise), joined with U+0001; NULL when it cannot be opened. */
MkString *mh_list(const MkString *path) {
    char *p = c_str(path);
    DIR *d = p == NULL ? NULL : opendir(p);
    free(p);
    if (d == NULL) return NULL;
    size_t cap = 64, n = 0;
    char **names = malloc(cap * sizeof *names);
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        if (strcmp(e->d_name, ".") == 0 || strcmp(e->d_name, "..") == 0) continue;
        if (n == cap) { cap *= 2; names = realloc(names, cap * sizeof *names); }
        names[n++] = strdup(e->d_name);
    }
    closedir(d);
    qsort(names, n, sizeof *names, cmp_names);
    size_t total = 0;
    for (size_t i = 0; i < n; i++) total += strlen(names[i]) + 1;
    char *buf = malloc(total + 1);
    size_t at = 0;
    for (size_t i = 0; i < n; i++) {
        size_t l = strlen(names[i]);
        memcpy(buf + at, names[i], l);
        at += l;
        if (i + 1 < n) buf[at++] = SEP;
        free(names[i]);
    }
    free(names);
    MkString *s = mk_string_from_utf8(buf, at);
    free(buf);
    return s;
}

/* mkdir -p; 1 when the directory exists afterwards. */
MkBoolean mh_mkdirs(const MkString *path) {
    char *p = c_str(path);
    if (p == NULL) return 0;
    size_t len = strlen(p);
    for (size_t i = 1; i <= len; i++) {
        if (p[i] == '/' || p[i] == 0) {
            char saved = p[i];
            p[i] = 0;
            if (mkdir(p, 0777) != 0 && errno != EEXIST) { free(p); return 0; }
            p[i] = saved;
        }
    }
    struct stat st;
    int ok = stat(p, &st) == 0 && S_ISDIR(st.st_mode);
    free(p);
    return ok ? 1 : 0;
}

static int remove_tree(const char *path) {
    struct stat st;
    if (lstat(path, &st) != 0) return 0;
    int n = 0;
    if (S_ISDIR(st.st_mode)) {
        DIR *d = opendir(path);
        if (d != NULL) {
            struct dirent *e;
            while ((e = readdir(d)) != NULL) {
                if (strcmp(e->d_name, ".") == 0 || strcmp(e->d_name, "..") == 0) continue;
                size_t l = strlen(path) + strlen(e->d_name) + 2;
                char *child = malloc(l);
                snprintf(child, l, "%s/%s", path, e->d_name);
                n += remove_tree(child);
                free(child);
            }
            closedir(d);
        }
        rmdir(path);
    } else if (unlink(path) == 0) {
        n = 1;
    }
    return n;
}

/* Deletes a file or a directory tree (symbolic links are removed, not followed); the number of files removed. */
MkInt mh_remove(const MkString *path) {
    char *p = c_str(path);
    int n = p == NULL ? 0 : remove_tree(p);
    free(p);
    return (MkInt)n;
}

void mh_make_executable(const MkString *path) {
    char *p = c_str(path);
    struct stat st;
    if (p != NULL && stat(p, &st) == 0) chmod(p, st.st_mode | S_IXUSR | S_IXGRP | S_IXOTH);
    free(p);
}

MkString *mh_realpath(const MkString *path) {
    char *p = c_str(path);
    char buf[4096];
    char *r = p == NULL ? NULL : realpath(p, buf);
    free(p);
    return r == NULL ? NULL : mk_str(buf);
}

/* Writes the text to a file descriptor (1 or 2); 0 on success, 1 when the reader is gone (EPIPE), 2 on another error. */
MkInt mh_write(MkInt fd, const MkString *text) {
    size_t len = 0;
    char *u = mk_utf16_to_utf8(text->chars, (size_t)text->length, &len);
    size_t at = 0;
    int result = 0;
    while (at < len) {
        ssize_t w = write((int)fd, u + at, len - at);
        if (w < 0) {
            if (errno == EINTR) continue;
            result = errno == EPIPE ? 1 : 2;
            break;
        }
        at += (size_t)w;
    }
    free(u);
    return (MkInt)result;
}

void mh_exit(MkInt code) {
    mk_runtime_flush();
    exit((int)code);
}

/* ---- processes ---------------------------------------------------------------------------------------------------- */

static MkString *g_out = NULL, *g_err = NULL, *g_start_error = NULL;

static char **split_list(const char *s, int *count) {
    int n = 1;
    for (const char *c = s; *c; c++) if (*c == SEP) n++;
    char **v = calloc((size_t)n + 1, sizeof *v);
    int i = 0;
    const char *start = s;
    for (const char *c = s;; c++) {
        if (*c == SEP || *c == 0) {
            v[i++] = strndup(start, (size_t)(c - start));
            start = c + 1;
            if (*c == 0) break;
        }
    }
    *count = i;
    return v;
}

typedef struct { char *data; size_t len, cap; } Buf;

static void buf_add(Buf *b, const char *p, size_t n) {
    if (b->len + n + 1 > b->cap) {
        b->cap = (b->len + n + 1) * 2;
        b->data = realloc(b->data, b->cap);
    }
    memcpy(b->data + b->len, p, n);
    b->len += n;
}

/* Runs `argv` (items separated by U+0001) in `cwd` (may be null) with the extra environment `env` (`KEY=VALUE` items). With `capture`
 * stdin is /dev/null and stdout and stderr are collected (see mh_exec_stdout / mh_exec_stderr); without it the child inherits all three.
 * Returns the exit status (128 + signal for a killed child), or -1 when the process could not be started (mh_exec_error says why). */
MkInt mh_exec(const MkString *argvList, const MkString *cwd, const MkString *envList, MkBoolean capture) {
    if (g_out != NULL) mk_release(&g_out->header);
    if (g_err != NULL) mk_release(&g_err->header);
    if (g_start_error != NULL) mk_release(&g_start_error->header);
    g_out = g_err = g_start_error = NULL;

    char *argvText = c_str(argvList);
    int argc = 0;
    char **argv = split_list(argvText, &argc);
    free(argvText);
    char *dir = c_str(cwd);
    char *envText = c_str(envList);
    int envc = 0;
    char **envv = envText != NULL && envText[0] ? split_list(envText, &envc) : NULL;
    free(envText);

    int outp[2] = {-1, -1}, errp[2] = {-1, -1}, execp[2];
    if (pipe(execp) != 0) { g_start_error = mk_str("pipe failed"); return -1; }
    fcntl(execp[1], F_SETFD, FD_CLOEXEC);
    if (capture && (pipe(outp) != 0 || pipe(errp) != 0)) { g_start_error = mk_str("pipe failed"); return -1; }

    fflush(NULL);
    pid_t pid = fork();
    if (pid < 0) { g_start_error = mk_str(strerror(errno)); return -1; }
    if (pid == 0) {
        if (capture) {
            int nul = open("/dev/null", O_RDONLY);
            if (nul >= 0) { dup2(nul, 0); close(nul); }
            dup2(outp[1], 1);
            dup2(errp[1], 2);
            close(outp[0]); close(outp[1]); close(errp[0]); close(errp[1]);
        }
        close(execp[0]);
        if (dir != NULL && chdir(dir) != 0) { int e = errno; (void)!write(execp[1], &e, sizeof e); _exit(127); }
        for (int i = 0; i < envc; i++) putenv(envv[i]);
        execvp(argv[0], argv);
        int e = errno;
        (void)!write(execp[1], &e, sizeof e);
        _exit(127);
    }
    close(execp[1]);
    Buf out = {0}, err = {0};
    if (capture) {
        close(outp[1]); close(errp[1]);
        struct pollfd fds[2] = {{outp[0], POLLIN, 0}, {errp[0], POLLIN, 0}};
        int open_fds = 2;
        char chunk[8192];
        while (open_fds > 0) {
            if (poll(fds, 2, -1) < 0) { if (errno == EINTR) continue; break; }
            for (int i = 0; i < 2; i++) {
                if (fds[i].fd < 0 || !(fds[i].revents & (POLLIN | POLLHUP | POLLERR))) continue;
                ssize_t n = read(fds[i].fd, chunk, sizeof chunk);
                if (n > 0) buf_add(i == 0 ? &out : &err, chunk, (size_t)n);
                else if (n == 0 || errno != EINTR) { close(fds[i].fd); fds[i].fd = -1; open_fds--; }
            }
        }
    }
    int status = 0;
    while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {}
    int childErrno = 0;
    ssize_t got = read(execp[0], &childErrno, sizeof childErrno);
    close(execp[0]);
    for (int i = 0; i < argc; i++) free(argv[i]);
    free(argv);
    for (int i = 0; i < envc; i++) free(envv[i]);
    free(envv);
    free(dir);
    if (got == (ssize_t)sizeof childErrno) {
        char msg[300];
        snprintf(msg, sizeof msg, "%s", strerror(childErrno));
        g_start_error = mk_str(msg);
        free(out.data); free(err.data);
        return -1;
    }
    if (capture) {
        g_out = mk_string_from_utf8(out.data == NULL ? "" : out.data, out.len);
        g_err = mk_string_from_utf8(err.data == NULL ? "" : err.data, err.len);
        free(out.data); free(err.data);
    }
    if (WIFEXITED(status)) return (MkInt)WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return (MkInt)(128 + WTERMSIG(status));
    return -1;
}

MkString *mh_exec_stdout(void) { if (g_out != NULL) mk_retain(&g_out->header); return g_out; }
MkString *mh_exec_stderr(void) { if (g_err != NULL) mk_retain(&g_err->header); return g_err; }
MkString *mh_exec_error(void) { if (g_start_error != NULL) mk_retain(&g_start_error->header); return g_start_error; }
