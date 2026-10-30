package mini.tensor

// Reductions (all elements or along one dimension), softmax.

/** The result of `max(dim)` / `min(dim)`: `val (values, indices) = x.max(1)`. */
data class ValuesIndices(val values: Tensor, val indices: Tensor)

private fun keptShape(shape: IntArray, d: Int, keepdim: Boolean): IntArray {
    if (keepdim) {
        val r = shape.dup()
        r[d] = 1
        return r
    }
    val r = IntArray(shape.size - 1)
    var j = 0
    for (i in 0 until shape.size) if (i != d) { r[j] = shape[i]; j++ }
    return r
}

private fun dims3(shape: IntArray, d: Int): IntArray {
    var outer = 1
    for (i in 0 until d) outer *= shape[i]
    var inner = 1
    for (i in d + 1 until shape.size) inner *= shape[i]
    return intArrayOf(outer, shape[d], inner)
}

private fun sumDtype(dt: DType): DType = dt

/** Sum of all elements (a 0-d tensor). */
fun Tensor.sum(): Tensor {
    var s = 0.0
    for (i in 0 until data.size) s += data[i]
    val out = scalarTensor(s, dtype)
    val a = this
    if (gradOn(a)) out.node = Node(arrayOf(a)) { g -> arrayOf(g.broadcastRaw(a.shape)) }
    return out
}

/** Sum along a dimension. */
fun Tensor.sum(dim: Int, keepdim: Boolean = false): Tensor {
    val d = normDim(dim, shape.size)
    val x = dims3(shape, d)
    val outer = x[0]
    val n = x[1]
    val inner = x[2]
    val r = DoubleArray(outer * inner)
    for (o in 0 until outer) {
        for (k in 0 until n) {
            val base = (o * n + k) * inner
            val ob = o * inner
            for (i in 0 until inner) r[ob + i] += data[base + i]
        }
    }
    val out = finish(r, keptShape(shape, d, keepdim), sumDtype(dtype))
    val a = this
    if (gradOn(a)) {
        val ks = keptShape(shape, d, true)
        out.node = Node(arrayOf(a)) { g -> arrayOf(g.view(*ks).broadcastRaw(a.shape)) }
    }
    return out
}

fun Tensor.mean(): Tensor {
    if (data.size == 0) return scalarTensor(Double.NaN, floatDtype(dtype))
    return floatOf(this).sum() / data.size.toDouble()
}

fun Tensor.mean(dim: Int, keepdim: Boolean = false): Tensor {
    val d = normDim(dim, shape.size)
    return floatOf(this).sum(d, keepdim) / shape[d].toDouble()
}

private fun floatOf(t: Tensor): Tensor = if (t.dtype == DType.Int64) t.to(defaultDtype) else t

/** Variance (biased unless `unbiased`), all elements. */
fun Tensor.variance(unbiased: Boolean = false): Tensor {
    val m = mean()
    val d = this - m
    val n = data.size
    return (d * d).sum() / (if (unbiased) (n - 1).toDouble() else n.toDouble())
}

fun Tensor.std(unbiased: Boolean = false): Tensor = variance(unbiased).sqrt()

/** Product of all elements. */
fun Tensor.prod(): Tensor {
    var s = 1.0
    for (i in 0 until data.size) s *= data[i]
    return scalarTensor(s, dtype)
}

private fun extremum(t: Tensor, dim: Int, keepdim: Boolean, wantMax: Boolean): ValuesIndices {
    val d = normDim(dim, t.shape.size)
    val x = dims3(t.shape, d)
    val outer = x[0]
    val n = x[1]
    val inner = x[2]
    if (n == 0) fail("max/min: cannot reduce over an empty dimension")
    val r = DoubleArray(outer * inner)
    val ix = DoubleArray(outer * inner)
    for (o in 0 until outer) {
        for (i in 0 until inner) {
            var best = t.data[o * n * inner + i]
            var bi = 0
            for (k in 1 until n) {
                val v = t.data[(o * n + k) * inner + i]
                if (best == best && (v != v || (if (wantMax) v > best else v < best))) { best = v; bi = k }
            }
            r[o * inner + i] = best
            ix[o * inner + i] = bi.toDouble()
        }
    }
    val ks = keptShape(t.shape, d, keepdim)
    val values = Tensor(r, ks, t.dtype)
    val indices = Tensor(ix, ks.dup(), DType.Int64)
    if (gradOn(t)) {
        values.node = Node(arrayOf(t)) { g ->
            val z = DoubleArray(t.data.size)
            for (o in 0 until outer) {
                for (i in 0 until inner) {
                    z[(o * n + ix[o * inner + i].toInt()) * inner + i] += g.data[o * inner + i]
                }
            }
            arrayOf(Tensor(z, t.shape.dup(), t.dtype))
        }
    }
    return ValuesIndices(values, indices)
}

/** Maximum along `dim`: values and the indices where they occur (the first one on ties). */
fun Tensor.max(dim: Int, keepdim: Boolean = false): ValuesIndices = extremum(this, dim, keepdim, true)
fun Tensor.min(dim: Int, keepdim: Boolean = false): ValuesIndices = extremum(this, dim, keepdim, false)

/** The largest element (a 0-d tensor). */
fun Tensor.max(): Tensor {
    if (data.size == 0) fail("max(): the tensor is empty")
    return view(-1).max(0).values
}

fun Tensor.min(): Tensor {
    if (data.size == 0) fail("min(): the tensor is empty")
    return view(-1).min(0).values
}

/** Index of the largest element (of the flattened tensor), a 0-d Int64 tensor. */
fun Tensor.argmax(): Tensor = view(-1).max(0).indices.view()

fun Tensor.argmin(): Tensor = view(-1).min(0).indices.view()

fun Tensor.argmax(dim: Int, keepdim: Boolean = false): Tensor = max(dim, keepdim).indices
fun Tensor.argmin(dim: Int, keepdim: Boolean = false): Tensor = min(dim, keepdim).indices

/** Euclidean norm of all elements. */
fun Tensor.norm(): Tensor = (this * this).sum().sqrt()

fun Tensor.softmax(dim: Int): Tensor {
    val src = floatOf(this)
    val d = normDim(dim, shape.size)
    val x = dims3(shape, d)
    val outer = x[0]
    val n = x[1]
    val inner = x[2]
    val r = DoubleArray(src.data.size)
    for (o in 0 until outer) {
        for (i in 0 until inner) {
            var mx = Double.NEGATIVE_INFINITY
            for (k in 0 until n) {
                val v = src.data[(o * n + k) * inner + i]
                if (v > mx) mx = v
            }
            var s = 0.0
            for (k in 0 until n) {
                val e = kotlin.math.exp(src.data[(o * n + k) * inner + i] - mx)
                r[(o * n + k) * inner + i] = e
                s += e
            }
            for (k in 0 until n) r[(o * n + k) * inner + i] /= s
        }
    }
    val out = finish(r, shape, src.dtype)
    if (gradOn(src)) {
        val o2 = out.detach()
        out.node = Node(arrayOf(src)) { g ->
            val gy = g * o2
            arrayOf(gy - o2 * gy.sum(d, true))
        }
    }
    return out
}

fun Tensor.logSoftmax(dim: Int): Tensor {
    val src = floatOf(this)
    val d = normDim(dim, shape.size)
    val x = dims3(shape, d)
    val outer = x[0]
    val n = x[1]
    val inner = x[2]
    val r = DoubleArray(src.data.size)
    for (o in 0 until outer) {
        for (i in 0 until inner) {
            var mx = Double.NEGATIVE_INFINITY
            for (k in 0 until n) {
                val v = src.data[(o * n + k) * inner + i]
                if (v > mx) mx = v
            }
            var s = 0.0
            for (k in 0 until n) s += kotlin.math.exp(src.data[(o * n + k) * inner + i] - mx)
            val lse = mx + kotlin.math.ln(s)
            for (k in 0 until n) r[(o * n + k) * inner + i] = src.data[(o * n + k) * inner + i] - lse
        }
    }
    val out = finish(r, shape, src.dtype)
    if (gradOn(src)) {
        val o2 = out.detach()
        out.node = Node(arrayOf(src)) { g -> arrayOf(g - o2.exp() * g.sum(d, true)) }
    }
    return out
}
