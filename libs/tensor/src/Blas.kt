@file:CInclude("mt_blas.h")
@file:CLink("dl")

package mini.tensor

import kotlinx.cinterop.CInclude
import kotlinx.cinterop.CLink
import mini.internal.CSymbol

// All C calls of the library live in this file. On a target without C sources (the browser) replace it by alt/Blas.kt.

@CSymbol("mt_blas_load") internal external fun mtBlasLoad(): Int
@CSymbol("mt_blas_name_kind") internal external fun mtBlasKind(): Int
@CSymbol("mt_gemm") internal external fun mtGemm(a: DoubleArray, aOff: Int, b: DoubleArray, bOff: Int, c: DoubleArray, cOff: Int,
                                                  ta: Int, tb: Int, m: Int, n: Int, k: Int, acc: Int)
@CSymbol("mt_gemv") internal external fun mtGemv(a: DoubleArray, x: DoubleArray, y: DoubleArray, m: Int, n: Int)
@CSymbol("mt_dot") internal external fun mtDot(x: DoubleArray, y: DoubleArray, n: Int): Double
@CSymbol("mt_blas_has_float") internal external fun mtBlasHasFloat(): Int
@CSymbol("mt_sgemm") internal external fun mtSgemm(a: FloatArray, aOff: Int, b: FloatArray, bOff: Int, c: FloatArray, cOff: Int,
                                                    ta: Int, tb: Int, m: Int, n: Int, k: Int, acc: Int)
@CSymbol("mt_sgemv") internal external fun mtSgemv(a: FloatArray, x: FloatArray, y: FloatArray, m: Int, n: Int)
@CSymbol("mt_sdot") internal external fun mtSdot(x: FloatArray, y: FloatArray, n: Int): Float
