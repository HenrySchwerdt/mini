#ifndef MT_BLAS_H
#define MT_BLAS_H
#include <minirt.h>
/* mini.tensor: optional BLAS acceleration, loaded at run time with dlopen (no build-time dependency). */
MkInt mt_blas_load(void);                 /* 1 when a CBLAS library was found, else 0 */
MkInt mt_blas_name_kind(void);            /* 0 none, 1 OpenBLAS, 2 Accelerate, 3 other CBLAS */
void mt_gemm(MkObject *a, MkInt aOff, MkObject *b, MkInt bOff, MkObject *c, MkInt cOff,
             MkInt ta, MkInt tb, MkInt m, MkInt n, MkInt k, MkInt accumulate);
void mt_gemv(MkObject *a, MkObject *x, MkObject *y, MkInt m, MkInt n);
MkDouble mt_dot(MkObject *x, MkObject *y, MkInt n);
#endif
