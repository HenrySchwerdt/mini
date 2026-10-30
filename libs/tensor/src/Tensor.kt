package mini.tensor

/** Element type. Storage is always a `DoubleArray`; `Float32` tensors round every result to single precision. */
enum class DType { Float32, Float64, Int64 }

/** The dtype of new floating point tensors (PyTorch: float32). */
var defaultDtype: DType = DType.Float32

internal fun fail(msg: String): Nothing = throw IllegalArgumentException(msg)

internal fun numelOf(shape: IntArray): Int {
    var n = 1
    for (s in shape) n *= s
    return n
}

internal fun stridesOf(shape: IntArray): IntArray {
    val st = IntArray(shape.size)
    var acc = 1
    var i = shape.size - 1
    while (i >= 0) {
        st[i] = acc
        acc *= shape[i]
        i--
    }
    return st
}

internal fun IntArray.dup(): IntArray {
    val r = IntArray(size)
    for (i in 0 until size) r[i] = this[i]
    return r
}

internal fun IntArray.sameAs(o: IntArray): Boolean {
    if (size != o.size) return false
    for (i in 0 until size) if (this[i] != o[i]) return false
    return true
}

internal fun normDim(dim: Int, nd: Int): Int {
    val d = if (dim < 0) dim + nd else dim
    if (d < 0 || d >= nd) fail("dimension $dim is out of range for a tensor with $nd dimensions")
    return d
}

internal fun DoubleArray.roundTo(dt: DType): DoubleArray {
    if (dt == DType.Float32) {
        for (i in 0 until size) this[i] = this[i].toFloat().toDouble()
    }
    return this
}

internal fun promote(a: DType, b: DType): DType =
    if (a == DType.Float64 || b == DType.Float64) DType.Float64
    else if (a == DType.Float32 || b == DType.Float32) DType.Float32
    else DType.Int64

/**
 * An n-dimensional array of numbers in row-major order (0-d to N-d), like a PyTorch tensor.
 *
 * `view`, `reshape`, `flatten`, `unsqueeze`, `squeeze` share the storage; every other shape operation copies.
 * With `requiresGrad = true` the operations on the tensor are recorded and `backward()` fills `grad`, see Autograd.kt.
 */
class Tensor internal constructor(val data: DoubleArray, val shape: IntArray, val dtype: DType) {
    val ndim: Int get() = shape.size
    val numel: Int get() = data.size
    val strides: IntArray = stridesOf(shape)

    var requiresGrad: Boolean = false
    var grad: Tensor? = null
    internal var node: Node? = null
    internal var gradAcc: Tensor? = null
    internal var mark: Int = 0

    /** Size of dimension `dim` (negative counts from the end). */
    fun size(dim: Int): Int = shape[normDim(dim, shape.size)]

    /** The value of a one-element tensor. */
    fun item(): Double {
        if (data.size != 1) fail("item() needs a tensor with one element, got shape ${shape.contentToString()}")
        return data[0]
    }

    fun toDoubleArray(): DoubleArray {
        val r = DoubleArray(data.size)
        for (i in 0 until data.size) r[i] = data[i]
        return r
    }

    fun toList(): List<Double> {
        val r = ArrayList<Double>()
        for (i in 0 until data.size) r.add(data[i])
        return r
    }

    private fun offsetOf(idx: IntArray): Int {
        if (idx.size != shape.size) fail("expected ${shape.size} indices for shape ${shape.contentToString()}, got ${idx.size}")
        var off = 0
        for (d in 0 until idx.size) {
            var i = idx[d]
            if (i < 0) i += shape[d]
            if (i < 0 || i >= shape[d]) fail("index ${idx[d]} is out of bounds for dimension $d with size ${shape[d]}")
            off += i * strides[d]
        }
        return off
    }

    /** The element at the full index as a Double (no tensor is allocated). */
    fun at(vararg idx: Int): Double = data[offsetOf(idx)]

    /** Sets the element at the full index. */
    fun setAt(value: Double, vararg idx: Int) { data[offsetOf(idx)] = value }

    /** `t[i]`: the i-th sub-tensor along dimension 0 (a 0-d tensor for a vector), like PyTorch. */
    operator fun get(i: Int): Tensor = select(0, i)
    operator fun get(i: Int, j: Int): Tensor = select(0, i).select(0, j)
    operator fun get(i: Int, j: Int, k: Int): Tensor = select(0, i).select(0, j).select(0, k)

    /** `t[i] = value` for a vector, `t[i] = row` for a matrix (copies the values, shapes must match). */
    operator fun set(i: Int, value: Double) { data[offsetOf(intArrayOf(i))] = value }
    operator fun set(i: Int, j: Int, value: Double) { data[offsetOf(intArrayOf(i, j))] = value }
    operator fun set(i: Int, j: Int, k: Int, value: Double) { data[offsetOf(intArrayOf(i, j, k))] = value }
    operator fun set(i: Int, value: Tensor) {
        val dst = select(0, i)
        if (!dst.shape.sameAs(value.shape)) fail("cannot assign shape ${value.shape.contentToString()} to ${dst.shape.contentToString()}")
        val base = (if (i < 0) i + shape[0] else i) * (if (shape.size > 1) strides[0] else 1)
        for (p in 0 until value.data.size) data[base + p] = value.data[p]
    }

    /** The transposed matrix (`t.T`), the last two dimensions swapped for N-d. */
    val T: Tensor get() = if (shape.size < 2) this else transpose(-2, -1)

    override fun toString(): String = formatTensor(this)

    companion object {
        fun zeros(vararg shape: Int): Tensor = mini.tensor.zeros(*shape)
        fun ones(vararg shape: Int): Tensor = mini.tensor.ones(*shape)
        fun full(value: Double, vararg shape: Int): Tensor = mini.tensor.full(value, *shape)
        fun eye(n: Int): Tensor = mini.tensor.eye(n)
        fun arange(start: Double, end: Double, step: Double = 1.0): Tensor = mini.tensor.arange(start, end, step)
        fun linspace(start: Double, end: Double, steps: Int): Tensor = mini.tensor.linspace(start, end, steps)
        fun fromList(values: List<Double>): Tensor = mini.tensor.fromList(values)
        fun random(seed: Long, vararg shape: Int): Tensor = mini.tensor.rand(*shape, generator = Generator(seed))
    }
}

// ---------------------------------------------------------------- factories

internal fun build(shape: IntArray, dtype: DType, requiresGrad: Boolean): Tensor {
    for (s in shape) if (s < 0) fail("negative dimension in shape ${shape.contentToString()}")
    val t = Tensor(DoubleArray(numelOf(shape)), shape.dup(), dtype)
    t.requiresGrad = requiresGrad
    return t
}

/** A tensor of the given values (1-d). `tensor(1.0, 2.0, 3.0)` */
fun tensor(vararg values: Double, dtype: DType = defaultDtype, requiresGrad: Boolean = false): Tensor {
    val t = build(intArrayOf(values.size), dtype, requiresGrad)
    for (i in 0 until values.size) t.data[i] = values[i]
    t.data.roundTo(dtype)
    return t
}

/** A matrix from rows: `tensor(doubleArrayOf(1.0, 2.0), doubleArrayOf(3.0, 4.0))`. */
fun tensor(vararg rows: DoubleArray, dtype: DType = defaultDtype, requiresGrad: Boolean = false): Tensor {
    val n = rows.size
    val m = if (n == 0) 0 else rows[0].size
    val t = build(intArrayOf(n, m), dtype, requiresGrad)
    for (i in 0 until n) {
        if (rows[i].size != m) fail("rows have different lengths: ${rows[0].size} and ${rows[i].size}")
        for (j in 0 until m) t.data[i * m + j] = rows[i][j]
    }
    t.data.roundTo(dtype)
    return t
}

/** A tensor with the given data (row-major) and shape. */
fun tensorOf(data: DoubleArray, vararg shape: Int, dtype: DType = defaultDtype, requiresGrad: Boolean = false): Tensor {
    val sh = if (shape.size == 0) intArrayOf(data.size) else shape
    if (numelOf(sh) != data.size) fail("shape ${sh.contentToString()} needs ${numelOf(sh)} values, got ${data.size}")
    val t = build(sh, dtype, requiresGrad)
    for (i in 0 until data.size) t.data[i] = data[i]
    t.data.roundTo(dtype)
    return t
}

fun fromList(values: List<Double>, dtype: DType = defaultDtype, requiresGrad: Boolean = false): Tensor {
    val t = build(intArrayOf(values.size), dtype, requiresGrad)
    for (i in 0 until values.size) t.data[i] = values[i]
    t.data.roundTo(dtype)
    return t
}

fun fromRows(rows: List<List<Double>>, dtype: DType = defaultDtype, requiresGrad: Boolean = false): Tensor {
    val n = rows.size
    val m = if (n == 0) 0 else rows[0].size
    val t = build(intArrayOf(n, m), dtype, requiresGrad)
    for (i in 0 until n) {
        if (rows[i].size != m) fail("rows have different lengths: $m and ${rows[i].size}")
        for (j in 0 until m) t.data[i * m + j] = rows[i][j]
    }
    t.data.roundTo(dtype)
    return t
}

/** A 0-d tensor. */
fun scalarTensor(value: Double, dtype: DType = defaultDtype): Tensor {
    val t = build(IntArray(0), dtype, false)
    t.data[0] = value
    t.data.roundTo(dtype)
    return t
}

fun zeros(vararg shape: Int, dtype: DType = defaultDtype, requiresGrad: Boolean = false): Tensor = build(shape, dtype, requiresGrad)

fun ones(vararg shape: Int, dtype: DType = defaultDtype, requiresGrad: Boolean = false): Tensor =
    full(1.0, *shape, dtype = dtype, requiresGrad = requiresGrad)

fun full(value: Double, vararg shape: Int, dtype: DType = defaultDtype, requiresGrad: Boolean = false): Tensor {
    val t = build(shape, dtype, requiresGrad)
    val v = if (dtype == DType.Float32) value.toFloat().toDouble() else value
    for (i in 0 until t.data.size) t.data[i] = v
    return t
}

fun zerosLike(t: Tensor): Tensor = build(t.shape, t.dtype, false)
fun onesLike(t: Tensor): Tensor = full(1.0, *t.shape, dtype = t.dtype)

/** `arange(5)` is the Int64 tensor 0..4. */
fun arange(end: Int): Tensor = arange(0, end)

fun arange(start: Int, end: Int, step: Int = 1): Tensor {
    if (step == 0) fail("step must not be zero")
    val n = if (step > 0) (end - start + step - 1) / step else (start - end - step - 1) / (-step)
    val t = build(intArrayOf(if (n < 0) 0 else n), DType.Int64, false)
    for (i in 0 until t.data.size) t.data[i] = (start + i * step).toDouble()
    return t
}

/** `arange(0.0, 1.0, 0.25)`: start, start + step, ... below end (default dtype). */
fun arange(start: Double, end: Double, step: Double = 1.0, dtype: DType = defaultDtype): Tensor {
    if (step == 0.0) fail("step must not be zero")
    var n = kotlin.math.ceil((end - start) / step).toInt()
    if (n < 0) n = 0
    val t = build(intArrayOf(n), dtype, false)
    for (i in 0 until n) t.data[i] = start + i * step
    t.data.roundTo(dtype)
    return t
}

fun linspace(start: Double, end: Double, steps: Int, dtype: DType = defaultDtype): Tensor {
    val t = build(intArrayOf(steps), dtype, false)
    for (i in 0 until steps) t.data[i] = if (steps == 1) start else start + (end - start) * i / (steps - 1)
    t.data.roundTo(dtype)
    return t
}

fun eye(n: Int, m: Int = n, dtype: DType = defaultDtype): Tensor {
    val t = build(intArrayOf(n, m), dtype, false)
    for (i in 0 until (if (n < m) n else m)) t.data[i * m + i] = 1.0
    return t
}

// ---------------------------------------------------------------- dtype conversion

fun Tensor.to(dtype: DType): Tensor {
    if (dtype == this.dtype) return this
    val r = Tensor(toDoubleArray(), shape.dup(), dtype)
    if (dtype == DType.Int64) { for (i in 0 until r.data.size) r.data[i] = kotlin.math.truncate(r.data[i]) }
    else r.data.roundTo(dtype)
    if (requiresGrad || node != null) {
        if (dtype != DType.Int64 && this.dtype != DType.Int64) {
            val src = this
            if (gradOn(src)) r.node = Node(arrayOf(src)) { g -> arrayOf(g.to(src.dtype)) }
        }
    }
    return r
}

fun Tensor.float(): Tensor = to(DType.Float32)
fun Tensor.double(): Tensor = to(DType.Float64)
fun Tensor.long(): Tensor = to(DType.Int64)

/** A copy of the data without history. */
fun Tensor.clone(): Tensor = Tensor(toDoubleArray(), shape.dup(), dtype)

/** The same storage without history (`requiresGrad` is false). */
fun Tensor.detach(): Tensor = Tensor(data, shape, dtype)
