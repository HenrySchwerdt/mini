/* C reference for the matmul benchmark: naive i-j-k, i-k-j, and OpenBLAS dgemm.
 * build: clang -O2 ref.c -o ref -lopenblas        (add -DNO_BLAS to skip OpenBLAS) */
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#ifndef NO_BLAS
#include <cblas.h>
#endif

static double now(void) { struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts); return ts.tv_sec + ts.tv_nsec * 1e-9; }

static void naive(const double *a, const double *b, double *c, int n) {
    for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) { double s = 0; for (int p = 0; p < n; p++) s += a[i*n+p] * b[p*n+j]; c[i*n+j] = s; }
}
static void ikj(const double *a, const double *b, double *c, int n) {
    for (int i = 0; i < n*n; i++) c[i] = 0;
    for (int i = 0; i < n; i++) for (int p = 0; p < n; p++) { double av = a[i*n+p]; for (int j = 0; j < n; j++) c[i*n+j] += av * b[p*n+j]; }
}

int main(void) {
    int sizes[3] = {256, 512, 1024};
    for (int s = 0; s < 3; s++) {
        int n = sizes[s];
        double *a = malloc(sizeof(double)*n*n), *b = malloc(sizeof(double)*n*n), *c = malloc(sizeof(double)*n*n);
        for (int i = 0; i < n*n; i++) { a[i] = (i % 17) * 0.1; b[i] = (i % 13) * 0.2; }
        double t = now(); naive(a, b, c, n); t = now() - t;
        printf("n=%d C naive i-j-k  %.2f GFLOP/s\n", n, 2.0*n*n*n/t/1e9);
        int reps = n <= 256 ? 10 : n <= 512 ? 3 : 1;
        t = now(); for (int r = 0; r < reps; r++) ikj(a, b, c, n); t = now() - t;
        printf("n=%d C i-k-j        %.2f GFLOP/s\n", n, 2.0*n*n*n*reps/t/1e9);
#ifndef NO_BLAS
        cblas_dgemm(CblasRowMajor, CblasNoTrans, CblasNoTrans, n, n, n, 1.0, a, n, b, n, 0.0, c, n);
        reps *= 5;
        t = now(); for (int r = 0; r < reps; r++) cblas_dgemm(CblasRowMajor, CblasNoTrans, CblasNoTrans, n, n, n, 1.0, a, n, b, n, 0.0, c, n); t = now() - t;
        printf("n=%d C OpenBLAS     %.2f GFLOP/s\n", n, 2.0*n*n*n*reps/t/1e9);
#endif
        free(a); free(b); free(c);
    }
    return 0;
}
