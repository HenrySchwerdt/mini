/* Portable host operations used by the LLVM API compiler. No LLVM dependency. */
#if !defined(_WIN32)
#define _POSIX_C_SOURCE 200809L
#endif
#include "minirt.h"
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include "windows_command.h"

MkLong mk_platform_nanos(void) {
    LARGE_INTEGER counter, frequency;
    if (!QueryPerformanceCounter(&counter) || !QueryPerformanceFrequency(&frequency) || frequency.QuadPart <= 0)
        mk_panic("cannot read monotonic clock");
    /* Split before multiplication so long uptimes do not overflow. */
    return (MkLong)(counter.QuadPart / frequency.QuadPart) * INT64_C(1000000000) +
        (MkLong)((long double)(counter.QuadPart % frequency.QuadPart) * 1000000000.0L / (long double)frequency.QuadPart);
}

MkInt mk_platform_spawn(const MkString *argv) {
    if (!argv || !argv->length) return -1;
    wchar_t *text = malloc(((size_t)argv->length + 1) * sizeof *text);
    wchar_t **args = malloc(((size_t)argv->length + 1) * sizeof *args);
    if (!text || !args) mk_panic("OutOfMemoryError");
    size_t count = 1;
    args[0] = text;
    for (MkInt i = 0; i < argv->length; i++) {
        if (!mk_string_at(argv, i)) { free(text); free(args); return -1; }
        text[i] = (wchar_t)mk_string_at(argv, i);
        if (text[i] == 1) { text[i] = 0; args[count++] = text+i+1; }
    }
    text[argv->length] = 0;
    if (!*args[0] || wcschr(args[0], L'"')) { free(text); free(args); return -1; }
    for (wchar_t *p=args[0]; *p; p++) if (*p==L'/') *p=L'\\';
    wchar_t *command = mk_windows_command(args, count);
    free(args);
    if (!command) { free(text); return -1; }

    HANDLE streams[3] = {NULL, NULL, NULL};
    const DWORD ids[3] = {STD_INPUT_HANDLE, STD_OUTPUT_HANDLE, STD_ERROR_HANDLE};
    LPPROC_THREAD_ATTRIBUTE_LIST attrs = NULL;
    int initialized = 0;
    PROCESS_INFORMATION process = {0};
    MkInt result = -1;
    DWORD error = 0;
    for (int i = 0; i < 3; i++) {
        HANDLE original = GetStdHandle(ids[i]);
        if (!original || original == INVALID_HANDLE_VALUE) { error = ERROR_INVALID_HANDLE; goto done; }
        if (!DuplicateHandle(GetCurrentProcess(), original, GetCurrentProcess(), &streams[i], 0, TRUE, DUPLICATE_SAME_ACCESS)) {
            error = GetLastError(); goto done;
        }
    }
    SIZE_T bytes = 0;
    (void)InitializeProcThreadAttributeList(NULL, 1, 0, &bytes);
    attrs = malloc(bytes);
    if (!attrs) mk_panic("OutOfMemoryError");
    if (!InitializeProcThreadAttributeList(attrs, 1, 0, &bytes)) { error = GetLastError(); goto done; }
    initialized = 1;
    if (!UpdateProcThreadAttribute(attrs, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, streams, sizeof streams, NULL, NULL)) {
        error = GetLastError(); goto done;
    }
    STARTUPINFOEXW start;
    memset(&start, 0, sizeof start);
    start.StartupInfo.cb = sizeof start;
    start.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
    start.StartupInfo.hStdInput = streams[0];
    start.StartupInfo.hStdOutput = streams[1];
    start.StartupInfo.hStdError = streams[2];
    start.lpAttributeList = attrs;
    fflush(NULL);
    /* argv[0] is always quoted and rejects embedded quotes. Let CreateProcess
     * resolve a bare compiler using the inherited Windows executable search. */
    if (!CreateProcessW(NULL, command, NULL, NULL, TRUE, EXTENDED_STARTUPINFO_PRESENT, NULL, NULL, &start.StartupInfo, &process)) {
        error = GetLastError(); goto done;
    }
    if (WaitForSingleObject(process.hProcess, INFINITE) != WAIT_OBJECT_0) { error = GetLastError(); goto done; }
    DWORD code;
    if (!GetExitCodeProcess(process.hProcess, &code)) { error = GetLastError(); goto done; }
    result = (MkInt)code;
 done:
    if (process.hThread) CloseHandle(process.hThread);
    if (process.hProcess) CloseHandle(process.hProcess);
    if (initialized) DeleteProcThreadAttributeList(attrs);
    free(attrs);
    for (int i = 0; i < 3; i++) if (streams[i]) CloseHandle(streams[i]);
    free(command); free(text);
    if (error) fprintf(stderr, "cannot run linker: Windows error %lu\n", (unsigned long)error);
    return result;
}
#else
#include <errno.h>
#if !defined(__wasi__)
#include <spawn.h>
#include <sys/wait.h>
#endif
#include <time.h>
char *mk_utf16_to_utf8(const MkChar *, size_t, size_t *);

MkLong mk_platform_nanos(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) mk_panic("cannot read monotonic clock");
    return (MkLong)now.tv_sec * INT64_C(1000000000) + (MkLong)now.tv_nsec;
}
MkInt mk_platform_spawn(const MkString *argv) {
#if defined(__wasi__)
    /* WASI has no subprocess facility. The browser compiler does not link
     * native executables, but shares this runtime source list. */
    (void)argv;
    errno = ENOSYS;
    return -1;
#else
    if (!argv || !argv->length) return -1;
    for (MkInt i=0; i<argv->length; i++) if (!mk_string_at(argv, i)) return -1;
    char *text = mk_string_to_utf8(argv, NULL);
    size_t count = 1;
    for (char *p=text; *p; p++) if (*p==1) count++;
    char **args = calloc(count+1, sizeof *args);
    if (!args) mk_panic("OutOfMemoryError");
    size_t at=0; args[at++]=text;
    for (char *p=text; *p; p++) if (*p==1) { *p=0; args[at++]=p+1; }
    if (!*args[0]) { free(args); free(text); return -1; }
    fflush(NULL);
    extern char **environ;
    pid_t child;
    int error=posix_spawnp(&child, args[0], NULL, NULL, args, environ);
    free(args); free(text);
    if (error) { fprintf(stderr, "cannot run linker: %s\n", strerror(error)); return -1; }
    int status=0;
    pid_t waited;
    do { waited=waitpid(child, &status, 0); } while (waited<0 && errno==EINTR);
    if (waited<0) return -1;
    if (WIFEXITED(status)) return (MkInt)WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return (MkInt)(128+WTERMSIG(status));
    return -1;
#endif
}
#endif
