// FloatArray and DoubleArray passed to C functions (@CSymbol): the callee gets the array object, 32-bit floats resp. 64-bit doubles.
@file:CInclude("cinterop_lib.h")

import kotlinx.cinterop.CInclude
import mini.internal.CSymbol

@CSymbol("farray_sum") external fun farraySum(a: FloatArray, n: Int): Float
@CSymbol("farray_scale") external fun farrayScale(a: FloatArray, n: Int, k: Float)
@CSymbol("darray_sum") external fun darraySum(a: DoubleArray, n: Int): Double

fun main() {
    val f = floatArrayOf(0.1f, 0.2f, 0.3f)
    println(farraySum(f, 3))
    farrayScale(f, 3, 2.5f)
    println(f.contentToString())
    val big = FloatArray(1000) { i -> i.toFloat() }
    println(farraySum(big, 1000))
    println(darraySum(doubleArrayOf(0.5, 1.25, 2.0), 3))
}
