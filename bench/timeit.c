// timeit <result-file> <cmd> [args...]: runs cmd (stdout/stderr inherited), then writes "<wall seconds> <peak RSS kB> <exit code>"
// to <result-file>. A tiny stand-in for `/usr/bin/time -v` (not installed everywhere). The peak RSS comes from wait4()'s rusage of
// the child; the launcher itself is small so it does not inflate the number (a Python parent would: ru_maxrss survives exec).
#include <stdio.h>
#include <stdlib.h>
#include <sys/resource.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc < 3) { fprintf(stderr, "usage: timeit <result-file> cmd args...\n"); return 2; }
    struct timespec t0, t1;
    clock_gettime(CLOCK_MONOTONIC, &t0);
    pid_t pid = fork();
    if (pid == 0) { execvp(argv[2], argv + 2); perror("exec"); _exit(127); }
    int status = 0;
    struct rusage ru;
    if (wait4(pid, &status, 0, &ru) < 0) { perror("wait4"); return 2; }
    clock_gettime(CLOCK_MONOTONIC, &t1);
    double wall = (double)(t1.tv_sec - t0.tv_sec) + (double)(t1.tv_nsec - t0.tv_nsec) / 1e9;
    int code = WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
    FILE *f = fopen(argv[1], "w");
    if (!f) { perror("result file"); return 2; }
    fprintf(f, "%.6f %ld %d\n", wall, ru.ru_maxrss, code);
    fclose(f);
    return 0;
}
