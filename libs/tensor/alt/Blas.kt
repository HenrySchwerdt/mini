package mini.tensor

// Replacement of src/Blas.kt (and drop `c-sources` from mini.toml) for targets without C interop, e.g. the browser:
// the library then runs on the pure Kotlin kernels.

internal fun mtBlasLoad(): Int = 0
internal fun mtBlasKind(): Int = 0
internal fun mtGemm(a: DoubleArray, aOff: Int, b: DoubleArray, bOff: Int, c: DoubleArray, cOff: Int, ta: Int, tb: Int, m: Int, n: Int, k: Int, acc: Int) {}
internal fun mtGemv(a: DoubleArray, x: DoubleArray, y: DoubleArray, m: Int, n: Int) {}
internal fun mtDot(x: DoubleArray, y: DoubleArray, n: Int): Double = 0.0
