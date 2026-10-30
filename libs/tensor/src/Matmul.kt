package mini.tensor

// Matrix products: `a @ b` is `a.matmul(b)`. The heavy loop is BLAS (dgemm) when a CBLAS library can be loaded at run
// time, else a blocked pure Kotlin kernel.

/** The linear algebra backend. */
object Blas {
    /** Was a CBLAS library found at run time? (OpenBLAS, Accelerate, ...) */
    val available: Boolean = mtBlasLoad() == 1

    /** Switch BLAS off (or back on) at run time, e.g. to compare with the Kotlin kernels. Ignored when not `available`. */
    var enabled: Boolean = true

    /** Products with fewer than this many multiply-adds use the Kotlin kernel (the call overhead is not worth it). */
    var threshold: Int = 4096

    val name: String
        get() {
            if (!available) return "kotlin"
            val k = mtBlasKind()
            return if (k == 1) "openblas" else if (k == 2) "accelerate" else "cblas"
        }

    val active: Boolean get() = available && enabled
}

/** Pure Kotlin row-major kernel: C (m x n) = A (m x k) * B (k x n), loop order i-k-j with the k loop blocked for the cache. */
internal fun gemmKotlin(a: DoubleArray, aOff: Int, b: DoubleArray, bOff: Int, c: DoubleArray, cOff: Int, m: Int, n: Int, k: Int, acc: Boolean) {
    if (!acc) {
        for (i in 0 until m * n) c[cOff + i] = 0.0
    }
    val kb = 128
    var p0 = 0
    while (p0 < k) {
        val p1 = if (p0 + kb < k) p0 + kb else k
        for (i in 0 until m) {
            val ci = cOff + i * n
            val ai = aOff + i * k
            var p = p0
            // four rows of B per pass over the row of C: one load/store of C per four multiply-adds
            while (p + 3 < p1) {
                val a0 = a[ai + p]
                val a1 = a[ai + p + 1]
                val a2 = a[ai + p + 2]
                val a3 = a[ai + p + 3]
                val b0 = bOff + p * n
                val b1 = b0 + n
                val b2 = b1 + n
                val b3 = b2 + n
                for (j in 0 until n) c[ci + j] += a0 * b[b0 + j] + a1 * b[b1 + j] + a2 * b[b2 + j] + a3 * b[b3 + j]
                p += 4
            }
            while (p < p1) {
                val av = a[ai + p]
                val bi = bOff + p * n
                for (j in 0 until n) c[ci + j] += av * b[bi + j]
                p++
            }
        }
        p0 = p1
    }
}

/** Reference kernel: the textbook i-j-k loop (used by the benchmark). */
internal fun gemmNaive(a: DoubleArray, b: DoubleArray, c: DoubleArray, m: Int, n: Int, k: Int) {
    for (i in 0 until m) {
        for (j in 0 until n) {
            var s = 0.0
            for (p in 0 until k) s += a[i * k + p] * b[p * n + j]
            c[i * n + j] = s
        }
    }
}

private fun transposeCopy(src: DoubleArray, off: Int, rows: Int, cols: Int): DoubleArray {
    val r = DoubleArray(rows * cols)
    for (i in 0 until rows) {
        for (j in 0 until cols) r[j * rows + i] = src[off + i * cols + j]
    }
    return r
}

/** C = op(A) op(B) for one matrix pair stored at the offsets; A is m x k (k x m when ta), B is k x n (n x k when tb). */
internal fun gemm(a: DoubleArray, aOff: Int, b: DoubleArray, bOff: Int, c: DoubleArray, cOff: Int,
                  ta: Boolean, tb: Boolean, m: Int, n: Int, k: Int, acc: Boolean, useBlas: Boolean) {
    if (m == 0 || n == 0) return
    if (k == 0) {
        if (!acc) for (i in 0 until m * n) c[cOff + i] = 0.0
        return
    }
    if (useBlas && Blas.active && m.toLong() * n * k >= Blas.threshold) {
        if (n == 1 && !ta && !acc && aOff == 0 && bOff == 0 && cOff == 0) mtGemv(a, b, c, m, k)
        else mtGemm(a, aOff, b, bOff, c, cOff, if (ta) 1 else 0, if (tb) 1 else 0, m, n, k, if (acc) 1 else 0)
        return
    }
    var aa = a
    var ao = aOff
    var bb = b
    var bo = bOff
    if (ta) { aa = transposeCopy(a, aOff, k, m); ao = 0 }
    if (tb) { bb = transposeCopy(b, bOff, n, k); bo = 0 }
    gemmKotlin(aa, ao, bb, bo, c, cOff, m, n, k, acc)
}

/** Batched product without autograd; a and b have at least two dimensions, their batch dimensions are equal or one side has none. */
internal fun bmmRaw(a: Tensor, b: Tensor, ta: Boolean, tb: Boolean): Tensor {
    val an = a.shape.size
    val bn = b.shape.size
    val ar = a.shape[an - 2]
    val ac = a.shape[an - 1]
    val br = b.shape[bn - 2]
    val bc = b.shape[bn - 1]
    val m = if (ta) ac else ar
    val k = if (ta) ar else ac
    val k2 = if (tb) bc else br
    val n = if (tb) br else bc
    if (k != k2) {
        fail("matmul: shape mismatch ${a.shape.contentToString()} @ ${b.shape.contentToString()}: inner dimensions $k and $k2 differ")
    }
    var batchShape: IntArray
    var aStep = m * k
    var bStep = k * n
    if (an == bn) {
        batchShape = IntArray(an - 2)
        for (i in 0 until an - 2) {
            if (a.shape[i] != b.shape[i]) fail("matmul: batch dimensions of ${a.shape.contentToString()} and ${b.shape.contentToString()} differ")
            batchShape[i] = a.shape[i]
        }
    } else if (bn == 2) {
        batchShape = IntArray(an - 2)
        for (i in 0 until an - 2) batchShape[i] = a.shape[i]
        bStep = 0
    } else if (an == 2) {
        batchShape = IntArray(bn - 2)
        for (i in 0 until bn - 2) batchShape[i] = b.shape[i]
        aStep = 0
    } else {
        fail("matmul: cannot batch ${a.shape.contentToString()} @ ${b.shape.contentToString()} (batch dimensions must be equal, or one operand a plain matrix)")
    }
    val batch = numelOf(batchShape)
    val osh = IntArray(batchShape.size + 2)
    for (i in 0 until batchShape.size) osh[i] = batchShape[i]
    osh[batchShape.size] = m
    osh[batchShape.size + 1] = n
    val dt = promote(a.dtype, b.dtype)
    val r = DoubleArray(batch * m * n)
    if (bStep == 0 && !ta && batch > 1) {
        // B is one matrix: all batches are one big product
        gemm(a.data, 0, b.data, 0, r, 0, false, tb, batch * m, n, k, false, true)
    } else {
        for (q in 0 until batch) {
            gemm(a.data, q * aStep, b.data, q * bStep, r, q * m * n, ta, tb, m, n, k, false, true)
        }
    }
    return finish(r, osh, dt)
}

private fun bmmDiff(a: Tensor, b: Tensor): Tensor {
    val out = bmmRaw(a, b, false, false)
    if (gradOn(a, b)) {
        out.node = Node(arrayOf(a, b)) { g ->
            arrayOf(bmmRaw(g, b, false, true).sumTo(a.shape), bmmRaw(a, g, true, false).sumTo(b.shape))
        }
    }
    return out
}

/**
 * Matrix product (`a @ b`): matrix x matrix, matrix x vector, vector x matrix, vector x vector (a 0-d result) and
 * batched products (`[B, m, k] @ [B, k, n]`, or one side a plain matrix).
 */
operator fun Tensor.matmul(o: Tensor): Tensor {
    if (shape.size == 0 || o.shape.size == 0) fail("matmul: both arguments need at least one dimension, got shapes ${shape.contentToString()} and ${o.shape.contentToString()}")
    if (shape.size == 1 && o.shape.size == 1) {
        if (shape[0] != o.shape[0]) fail("matmul: shape mismatch ${shape.contentToString()} @ ${o.shape.contentToString()}: lengths ${shape[0]} and ${o.shape[0]} differ")
        return bmmDiff(view(1, -1), o.view(-1, 1)).view()
    }
    if (shape.size == 1) return bmmDiff(unsqueeze(0), o).squeeze(-2)
    if (o.shape.size == 1) return bmmDiff(this, o.unsqueeze(1)).squeeze(-1)
    return bmmDiff(this, o)
}

/** Matrix product of two matrices (`torch.mm`). */
fun Tensor.mm(o: Tensor): Tensor {
    if (shape.size != 2 || o.shape.size != 2) fail("mm: expected two matrices, got shapes ${shape.contentToString()} and ${o.shape.contentToString()}")
    return bmmDiff(this, o)
}

/** Matrix times vector (`torch.mv`). */
fun Tensor.mv(v: Tensor): Tensor {
    if (shape.size != 2 || v.shape.size != 1) fail("mv: expected a matrix and a vector, got shapes ${shape.contentToString()} and ${v.shape.contentToString()}")
    return matmul(v)
}

/** Batched matrix product of two 3-d tensors (`torch.bmm`). */
fun Tensor.bmm(o: Tensor): Tensor {
    if (shape.size != 3 || o.shape.size != 3) fail("bmm: expected two 3-d tensors, got shapes ${shape.contentToString()} and ${o.shape.contentToString()}")
    return bmmDiff(this, o)
}

/** Inner product of two vectors (a 0-d tensor). */
fun Tensor.dot(o: Tensor): Tensor {
    if (shape.size != 1 || o.shape.size != 1) fail("dot: expected two vectors, got shapes ${shape.contentToString()} and ${o.shape.contentToString()}")
    return matmul(o)
}

/** Outer product of two vectors. */
fun Tensor.outer(o: Tensor): Tensor {
    if (shape.size != 1 || o.shape.size != 1) fail("outer: expected two vectors, got shapes ${shape.contentToString()} and ${o.shape.contentToString()}")
    return unsqueeze(1) * o.unsqueeze(0)
}

/** `a.matrixPower(n)` for a square matrix by repeated squaring (n >= 0). */
fun Tensor.matrixPower(n: Int): Tensor {
    if (shape.size != 2 || shape[0] != shape[1]) fail("matrixPower: expected a square matrix, got shape ${shape.contentToString()}")
    if (n < 0) fail("matrixPower: negative exponents are not supported")
    var result = eye(shape[0], dtype = dtype)
    var base = this
    var e = n
    while (e > 0) {
        if (e % 2 == 1) result = result.matmul(base)
        e /= 2
        if (e > 0) base = base.matmul(base)
    }
    return result
}

fun Tensor.trace(): Tensor {
    if (shape.size != 2) fail("trace: expected a matrix, got shape ${shape.contentToString()}")
    var s = 0.0
    val n = if (shape[0] < shape[1]) shape[0] else shape[1]
    for (i in 0 until n) s += data[i * shape[1] + i]
    return scalarTensor(s, dtype)
}

/** Solves `A x = b` for a square matrix A (Gaussian elimination with partial pivoting); b is a vector or a matrix. No gradient. */
fun solve(a: Tensor, b: Tensor): Tensor {
    if (a.shape.size != 2 || a.shape[0] != a.shape[1]) fail("solve: A must be a square matrix, got shape ${a.shape.contentToString()}")
    val n = a.shape[0]
    val vec = b.shape.size == 1
    if (b.shape.size == 0 || b.shape[0] != n) fail("solve: shape mismatch A ${a.shape.contentToString()}, b ${b.shape.contentToString()}")
    val cols = if (vec) 1 else b.shape[1]
    val m = DoubleArray(n * n)
    for (i in 0 until n * n) m[i] = a.data[i]
    val x = DoubleArray(n * cols)
    for (i in 0 until n * cols) x[i] = b.data[i]
    for (c in 0 until n) {
        var piv = c
        for (r in c + 1 until n) if (kotlin.math.abs(m[r * n + c]) > kotlin.math.abs(m[piv * n + c])) piv = r
        if (m[piv * n + c] == 0.0) fail("solve: the matrix is singular")
        if (piv != c) {
            for (j in 0 until n) { val t = m[c * n + j]; m[c * n + j] = m[piv * n + j]; m[piv * n + j] = t }
            for (j in 0 until cols) { val t = x[c * cols + j]; x[c * cols + j] = x[piv * cols + j]; x[piv * cols + j] = t }
        }
        for (r in c + 1 until n) {
            val f = m[r * n + c] / m[c * n + c]
            if (f == 0.0) continue
            for (j in c until n) m[r * n + j] -= f * m[c * n + j]
            for (j in 0 until cols) x[r * cols + j] -= f * x[c * cols + j]
        }
    }
    var r = n - 1
    while (r >= 0) {
        for (j in 0 until cols) {
            var s = x[r * cols + j]
            for (q in r + 1 until n) s -= m[r * n + q] * x[q * cols + j]
            x[r * cols + j] = s / m[r * n + r]
        }
        r--
    }
    return finish(x, b.shape, promote(a.dtype, b.dtype).let { if (it == DType.Int64) defaultDtype else it })
}
