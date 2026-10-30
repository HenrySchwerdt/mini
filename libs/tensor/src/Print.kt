package mini.tensor

// toString like PyTorch: aligned columns, 4 decimals, "..." for large tensors.

private const val THRESHOLD = 1000
private const val EDGE = 3

private fun isIntegral(x: Double): Boolean = x == kotlin.math.floor(x) && kotlin.math.abs(x) < 1e15

private fun fixed4(x: Double): String {
    val neg = x < 0.0
    val v = if (neg) -x else x
    val scaled = kotlin.math.round(v * 10000.0).toLong()
    val ip = scaled / 10000L
    val fp = (scaled % 10000L).toInt().toString().padStart(4, '0')
    val s = ip.toString() + "." + fp
    return if (neg && scaled != 0L) "-" + s else s
}

private fun formatElement(x: Double, dt: DType, allIntegral: Boolean): String {
    if (x != x) return "nan"
    if (x == Double.POSITIVE_INFINITY) return "inf"
    if (x == Double.NEGATIVE_INFINITY) return "-inf"
    if (dt == DType.Int64) return x.toLong().toString()
    if (allIntegral) return x.toLong().toString() + "."
    if (kotlin.math.abs(x) >= 1e9) return x.toString()
    return fixed4(x)
}

private class Printer(val t: Tensor, val strs: Array<String>, val width: Int) {
    val summarize: Boolean = t.data.size > THRESHOLD

    fun pad(s: String): String = s.padStart(width)

    fun render(dim: Int, offset: Int, indent: Int, sb: StringBuilder) {
        val nd = t.shape.size
        val n = t.shape[dim]
        val stride = t.strides[dim]
        sb.append('[')
        val trunc = summarize && n > 2 * EDGE
        var k = 0
        var printed = 0
        while (k < n) {
            if (trunc && k == EDGE) {
                k = n - EDGE
                if (dim == nd - 1) sb.append("..., ") else {
                    sb.append("...,\n")
                    for (q in 0 until (nd - dim - 1)) sb.append('\n')
                    for (q in 0 until indent + 1) sb.append(' ')
                }
                continue
            }
            if (dim == nd - 1) {
                sb.append(pad(strs[offset + k]))
                if (k < n - 1) sb.append(", ")
            } else {
                render(dim + 1, offset + k * stride, indent + 1, sb)
                if (k < n - 1) {
                    sb.append(",\n")
                    for (q in 0 until (nd - dim - 2)) sb.append('\n')
                    for (q in 0 until indent + 1) sb.append(' ')
                }
            }
            k++
            printed++
        }
        sb.append(']')
    }
}

internal fun formatTensor(t: Tensor): String {
    val sb = StringBuilder()
    sb.append("tensor(")
    val n = t.data.size
    if (n == 0) {
        sb.append("[]")
    } else {
        var allInt = true
        for (i in 0 until n) if (!isIntegral(t.data[i]) && t.data[i] == t.data[i] && kotlin.math.abs(t.data[i]) != Double.POSITIVE_INFINITY) allInt = false
        val strs = Array<String>(n) { formatElement(t.data[it], t.dtype, allInt) }
        if (t.shape.size == 0) {
            sb.append(strs[0])
        } else {
            var w = 0
            for (s in strs) if (s.length > w) w = s.length
            Printer(t, strs, w).render(0, 0, 7, sb)
        }
    }
    if (t.dtype == DType.Float64) sb.append(", dtype=float64")
    if (t.requiresGrad && t.node == null) sb.append(", requires_grad=True")
    if (t.node != null) sb.append(", grad_fn=<Backward>")
    sb.append(')')
    return sb.toString()
}
