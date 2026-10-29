#ifndef _WIN32
#define _POSIX_C_SOURCE 200809L
#endif
#include "mini/compiler.h"
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "mini_uv_config.h"
#ifdef _WIN32
#include <process.h>
#include <io.h>
#include <fcntl.h>
#include <share.h>
#include <sys/stat.h>
#else
#include <unistd.h>
#include <sys/wait.h>
#include <spawn.h>
extern char **environ;
#endif

#ifdef _WIN32
/* _spawnvp joins arguments with spaces without quoting them, so an argument containing spaces
 * (e.g. a temp directory) would be split. Quote per the MSVC CRT command-line parsing rules. */
static char *quote_argument(const char *arg) {
    if (*arg && !strpbrk(arg, " \t\n\v\""))
        return _strdup(arg);
    size_t length = strlen(arg);
    char *quoted = malloc(length * 2 + 3);
    if (!quoted)
        return NULL;
    size_t out = 0;
    quoted[out++] = '"';
    for (size_t i = 0;; i++) {
        size_t backslashes = 0;
        while (arg[i] == '\\') {
            backslashes++;
            i++;
        }
        if (!arg[i]) {
            /* Double trailing backslashes so they do not escape the closing quote. */
            for (size_t k = 0; k < backslashes * 2; k++)
                quoted[out++] = '\\';
            break;
        }
        if (arg[i] == '"') {
            for (size_t k = 0; k < backslashes * 2 + 1; k++)
                quoted[out++] = '\\';
        } else {
            for (size_t k = 0; k < backslashes; k++)
                quoted[out++] = '\\';
        }
        quoted[out++] = arg[i];
    }
    quoted[out++] = '"';
    quoted[out] = '\0';
    return quoted;
}
#endif

static int execute(const char *program, char *const *args) {
#ifdef _WIN32
    size_t count = 0;
    while (args[count])
        count++;
    char **quoted = calloc(count + 1, sizeof(*quoted));
    if (!quoted) {
        fprintf(stderr, "cannot execute %s: out of memory\n", program);
        return 1;
    }
    for (size_t i = 0; i < count; i++) {
        quoted[i] = quote_argument(args[i]);
        if (!quoted[i]) {
            for (size_t k = 0; k < i; k++)
                free(quoted[k]);
            free(quoted);
            fprintf(stderr, "cannot execute %s: out of memory\n", program);
            return 1;
        }
    }
    intptr_t code = _spawnvp(_P_WAIT, program, (const char *const *)quoted);
    for (size_t i = 0; i < count; i++)
        free(quoted[i]);
    free(quoted);
    if (code == -1) {
        fprintf(stderr, "cannot execute %s: %s\n", program, strerror(errno));
        return 1;
    }
    return code == 0 ? 0 : 1;
#else
    pid_t pid;
    int error = posix_spawnp(&pid, program, NULL, NULL, args, environ);
    if (error) {
        fprintf(stderr, "cannot execute %s: %s\n", program, strerror(error));
        return 1;
    }
    int status;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) {
            perror("waitpid");
            return 1;
        }
    }
    return WIFEXITED(status) && WEXITSTATUS(status) == 0 ? 0 : 1;
#endif
}
int mini_build_output_with_options(const char *source, const char *output, const char *compiler,
                                   MiniBuildMode mode, const MiniBuildOptions *options) {
    if (!source || !output)
        return 1;
    if (mode == MINI_EMIT_C) {
        FILE *f = fopen(output, "wb");
        if (!f) {
            perror(output);
            return 1;
        }
        size_t n = strlen(source);
        int error = fwrite(source, 1, n, f) != n;
        if (fclose(f))
            error = 1;
        if (error)
            perror(output);
        return error;
    }
    /* Exclusive creation prevents accidental overwrite/symlink attacks. The
       intermediate is beside the requested output and removed on every path. */
    size_t n = strlen(output);
    if (n > SIZE_MAX - 20)
        return 1;
    char *temporary = malloc(n + 20);
    if (!temporary)
        return 1;
    snprintf(temporary, n + 20, "%s.mini-XXXXXX", output);
    int fd = -1;
#ifdef _WIN32
    if (_mktemp_s(temporary, n + 20) != 0 ||
        _sopen_s(&fd, temporary, _O_CREAT | _O_EXCL | _O_WRONLY | _O_BINARY, _SH_DENYRW,
                 _S_IREAD | _S_IWRITE) != 0)
        fd = -1;
#else
    fd = mkstemp(temporary);
#endif
    if (fd < 0) {
        perror(temporary);
        free(temporary);
        return 1;
    }
#ifdef _WIN32
    FILE *f = _fdopen(fd, "wb");
#else
    FILE *f = fdopen(fd, "wb");
#endif
    if (!f) {
#ifdef _WIN32
        _close(fd);
#else
        close(fd);
#endif
        remove(temporary);
        free(temporary);
        return 1;
    }
    int error = fwrite(source, 1, strlen(source), f) != strlen(source);
    if (fclose(f))
        error = 1;
    if (!error) {
        const char *cc = compiler && *compiler ? compiler : "clang";
        bool libuv = options ? options->requires_libuv
                             : strstr(source, "\n#define MINI_USES_LIBUV 1\n") != NULL;
        const char *root =
            options && options->libuv_root ? options->libuv_root : getenv("MINI_LIBUV_ROOT");
        size_t flags = 0;
        for (size_t j = 0; mini_uv_compile_flags[j]; j++)
            flags++;
        for (size_t j = 0; mini_uv_link_flags[j]; j++)
            flags++;
        char **args = calloc(flags + 24, sizeof(*args));
        char *include = NULL, *library = NULL;
        if (libuv && root && *root) {
            size_t length = strlen(root);
            if (length > SIZE_MAX - 16)
                error = 1;
            else {
                include = malloc(length + 16);
                library = malloc(length + 16);
                if (!include || !library)
                    error = 1;
                else {
                    snprintf(include, length + 16, "-I%s/include", root);
                    snprintf(library, length + 16, "-L%s/lib", root);
                    char *probe = malloc(length + 16);
                    if (!probe)
                        error = 1;
                    else {
                        snprintf(probe, length + 16, "%s/include/uv.h", root);
                        FILE *header = fopen(probe, "rb");
                        if (!header) {
                            fprintf(stderr, "libuv prefix has no readable include/uv.h: %s\n",
                                    root);
                            error = 1;
                        } else
                            fclose(header);
                        free(probe);
                    }
                }
            }
        }
        if (!args)
            error = 1;
        size_t i = 0;
        if (!error) {
            args[i++] = (char *)cc;
            args[i++] = "-x";
            args[i++] = "c";
            args[i++] = "-std=c17";
            args[i++] = "-O2";
            args[i++] = "-fno-strict-aliasing";
            if (libuv) {
                if (include)
                    args[i++] = include;
                else
                    for (size_t j = 0; mini_uv_compile_flags[j]; j++)
                        args[i++] = (char *)mini_uv_compile_flags[j];
            }
            if (mode == MINI_EMIT_LLVM) {
                args[i++] = "-S";
                args[i++] = "-emit-llvm";
            }
            args[i++] = temporary;
            if (libuv && mode == MINI_BUILD_NATIVE) {
#if defined(_WIN32) && !defined(__MINGW32__)
                /* The xmake libuv package links the dynamic CRT (/MD); clang defaults to the
                 * static one, which leaves libuv's __imp_ CRT imports unresolved. */
                args[i++] = "-fms-runtime-lib=dll";
#endif
                if (library)
                    args[i++] = library;
                for (size_t j = 0; mini_uv_link_flags[j]; j++) {
                    /* An explicit prefix replaces package search directories. */
                    if (library && !strncmp(mini_uv_link_flags[j], "-L", 2))
                        continue;
                    args[i++] = (char *)mini_uv_link_flags[j];
                }
            }
            args[i++] = "-o";
            args[i++] = (char *)output;
            args[i] = NULL;
            error = execute(cc, args);
        }
        free(args);
        free(include);
        free(library);
    }
    remove(temporary);
    free(temporary);
    return error;
}

int mini_build_output(const char *source, const char *output, const char *compiler,
                      MiniBuildMode mode) {
    return mini_build_output_with_options(source, output, compiler, mode, NULL);
}
