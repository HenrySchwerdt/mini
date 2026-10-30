/* mini.tensor: BLAS through dlopen. Row-major CBLAS, double precision (d*) and single precision (s*, for Float32 tensors).
 * A library is searched at the first call: OpenBLAS / any CBLAS on Linux, Accelerate on macOS. Set MINI_TENSOR_BLAS=0 to disable.
 * The functions are called with borrowed DoubleArray (d*) or FloatArray (s*) objects, the data starts right behind the array header. */
#include "mt_blas.h"
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>

typedef void (*gemm_fn)(int, int, int, int, int, int, double, const double *, int, const double *, int, double, double *, int);
typedef void (*gemv_fn)(int, int, int, int, double, const double *, int, const double *, int, double, double *, int);
typedef double (*dot_fn)(int, const double *, int, const double *, int);

typedef void (*sgemm_fn)(int, int, int, int, int, int, float, const float *, int, const float *, int, float, float *, int);
typedef void (*sgemv_fn)(int, int, int, int, float, const float *, int, const float *, int, float, float *, int);
typedef float (*sdot_fn)(int, const float *, int, const float *, int);

static sgemm_fn p_sgemm; static sgemv_fn p_sgemv; static sdot_fn p_sdot;
static gemm_fn p_gemm; static gemv_fn p_gemv; static dot_fn p_dot;
static int state = -1, kind = 0;

MkInt mt_blas_load(void) {
    if (state >= 0) return state;
    state = 0;
    const char *env = getenv("MINI_TENSOR_BLAS");
    if (env && env[0] == '0') return 0;
    static const char *names[] = {
#ifdef __APPLE__
        "/System/Library/Frameworks/Accelerate.framework/Accelerate",
        "/opt/homebrew/opt/openblas/lib/libopenblas.dylib",
#else
        "libopenblas.so.0", "libopenblas.so", "libblis.so.4", "libcblas.so.3", "libblas.so.3", "libblas.so",
#endif
        0 };
    for (int i = 0; names[i]; i++) {
        void *h = dlopen(names[i], RTLD_NOW | RTLD_LOCAL);
        if (!h) continue;
        gemm_fn g = (gemm_fn)dlsym(h, "cblas_dgemm");
        gemv_fn v = (gemv_fn)dlsym(h, "cblas_dgemv");
        dot_fn d = (dot_fn)dlsym(h, "cblas_ddot");
        if (g && v && d) {
            p_gemm = g; p_gemv = v; p_dot = d; state = 1;
            sgemm_fn sg = (sgemm_fn)dlsym(h, "cblas_sgemm");
            sgemv_fn sv = (sgemv_fn)dlsym(h, "cblas_sgemv");
            sdot_fn sd = (sdot_fn)dlsym(h, "cblas_sdot");
            if (sg && sv && sd) { p_sgemm = sg; p_sgemv = sv; p_sdot = sd; }
            kind = strstr(names[i], "openblas") ? 1 : strstr(names[i], "Accelerate") ? 2 : 3;
            return 1;
        }
        dlclose(h);
    }
    return 0;
}

/* 1 when the single precision routines (sgemm, sgemv, sdot) are available too */
MkInt mt_blas_has_float(void) { return mt_blas_load() && p_sgemm != NULL; }

MkInt mt_blas_name_kind(void) { mt_blas_load(); return kind; }

#define ELEMS(o) MK_ARRAY_ELEMS(double, o)

void mt_gemm(MkObject *a, MkInt aOff, MkObject *b, MkInt bOff, MkObject *c, MkInt cOff,
             MkInt ta, MkInt tb, MkInt m, MkInt n, MkInt k, MkInt accumulate) {
    /* C(m x n) = op(A) op(B) (+ C); A is m x k (k x m when ta), B is k x n (n x k when tb); all row-major */
    p_gemm(101, ta ? 112 : 111, tb ? 112 : 111, m, n, k, 1.0, ELEMS(a) + aOff, ta ? m : k, ELEMS(b) + bOff, tb ? k : n,
           accumulate ? 1.0 : 0.0, ELEMS(c) + cOff, n);
}

void mt_gemv(MkObject *a, MkObject *x, MkObject *y, MkInt m, MkInt n) {
    p_gemv(101, 111, m, n, 1.0, ELEMS(a), n, ELEMS(x), 1, 0.0, ELEMS(y), 1);
}

MkDouble mt_dot(MkObject *x, MkObject *y, MkInt n) { return p_dot(n, ELEMS(x), 1, ELEMS(y), 1); }

#define FELEMS(o) MK_ARRAY_ELEMS(float, o)

void mt_sgemm(MkObject *a, MkInt aOff, MkObject *b, MkInt bOff, MkObject *c, MkInt cOff,
              MkInt ta, MkInt tb, MkInt m, MkInt n, MkInt k, MkInt accumulate) {
    p_sgemm(101, ta ? 112 : 111, tb ? 112 : 111, m, n, k, 1.0f, FELEMS(a) + aOff, ta ? m : k, FELEMS(b) + bOff, tb ? k : n,
            accumulate ? 1.0f : 0.0f, FELEMS(c) + cOff, n);
}

void mt_sgemv(MkObject *a, MkObject *x, MkObject *y, MkInt m, MkInt n) {
    p_sgemv(101, 111, m, n, 1.0f, FELEMS(a), n, FELEMS(x), 1, 0.0f, FELEMS(y), 1);
}

MkFloat mt_sdot(MkObject *x, MkObject *y, MkInt n) { return p_sdot(n, FELEMS(x), 1, FELEMS(y), 1); }
