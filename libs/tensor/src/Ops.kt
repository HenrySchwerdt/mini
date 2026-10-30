package mini.tensor

import kotlin.math.pow

// Elementwise operations with NumPy broadcasting, scalar operators, unary math functions.

internal fun broadcastShape(a: IntArray, b: IntArray): IntArray {
    val n = if (a.size > b.size) a.size else b.size
    val r = IntArray(n)
    for (i in 0 until n) {
        val da = if (i < n - a.size) 1 else a[i - (n - a.size)]
        val db = if (i < n - b.size) 1 else b[i - (n - b.size)]
        if (da == db) r[i] = da
        else if (da == 1) r[i] = db
        else if (db == 1) r[i] = da
        else fail("shapes ${a.contentToString()} and ${b.contentToString()} cannot be broadcast together (dimension $i: $da vs $db)")
    }
    return r
}

/** Strides (in elements) of an array of `shape` read as if it had the shape `out`: 0 along broadcast dimensions. */
internal fun broadcastStrides(shape: IntArray, out: IntArray): IntArray {
    val st = stridesOf(shape)
    val r = IntArray(out.size)
    val off = out.size - shape.size
    for (i in 0 until shape.size) r[i + off] = if (shape[i] == 1 && out[i + off] != 1) 0 else st[i]
    return r
}

internal fun finish(r: DoubleArray, shape: IntArray, dt: DType): Tensor {
    return Tensor(r, shape.dup(), dt)
}

internal inline fun zipB(a: Tensor, b: Tensor, dt: DType, f: (Double, Double) -> Double): Tensor {
    val ad = a.data
    val bd = b.data
    if (a.shape.sameAs(b.shape)) {
        val r = DoubleArray(ad.size)
        for (i in 0 until r.size) r[i] = f(ad[i], bd[i])
        return finish(r, a.shape, dt)
    }
    if (bd.size == 1 && b.shape.size <= a.shape.size) {
        val y = bd[0]
        val r = DoubleArray(ad.size)
        for (i in 0 until r.size) r[i] = f(ad[i], y)
        return finish(r, a.shape, dt)
    }
    if (ad.size == 1 && a.shape.size <= b.shape.size) {
        val x = ad[0]
        val r = DoubleArray(bd.size)
        for (i in 0 until r.size) r[i] = f(x, bd[i])
        return finish(r, b.shape, dt)
    }
    val out = broadcastShape(a.shape, b.shape)
    val sa = broadcastStrides(a.shape, out)
    val sb = broadcastStrides(b.shape, out)
    val n = numelOf(out)
    val nd = out.size
    val r = DoubleArray(n)
    val idx = IntArray(nd)
    var ia = 0
    var ib = 0
    for (p in 0 until n) {
        r[p] = f(ad[ia], bd[ib])
        var d = nd - 1
        while (d >= 0) {
            idx[d] += 1
            ia += sa[d]
            ib += sb[d]
            if (idx[d] < out[d]) break
            ia -= sa[d] * out[d]
            ib -= sb[d] * out[d]
            idx[d] = 0
            d--
        }
    }
    return finish(r, out, dt)
}

internal fun floatDtype(dt: DType): DType = if (dt == DType.Int64) defaultDtype else dt

internal inline fun unaryF(a: Tensor, f: (Double) -> Double): Tensor {
    val dt = floatDtype(a.dtype)
    val ad = a.data
    val r = DoubleArray(ad.size)
    for (i in 0 until r.size) r[i] = f(ad[i])
    return finish(r, a.shape, dt)
}

/** Applies `f` to every element (no gradient is recorded). */
inline fun Tensor.map(f: (Double) -> Double): Tensor {
    val ad = data
    val r = DoubleArray(ad.size)
    for (i in 0 until r.size) r[i] = f(ad[i])
    return finish(r, shape, dtype)
}

/** Sums `g` down to `target` (the inverse of broadcasting). */
internal fun Tensor.sumTo(target: IntArray): Tensor {
    if (shape.sameAs(target)) return this
    var t = this
    val lead = shape.size - target.size
    for (i in 0 until lead) t = t.sum(0)
    for (i in 0 until target.size) {
        if (target[i] == 1 && t.shape[i] != 1) t = t.sum(i, true)
    }
    return t
}

/** Broadcasts to `shape` (a copy; differentiable). */
fun Tensor.expand(vararg shape: Int): Tensor {
    val sh = shape.dup()
    val off = sh.size - this.shape.size
    if (off < 0) fail("expand: cannot expand shape ${this.shape.contentToString()} to ${sh.contentToString()}")
    for (i in 0 until sh.size) if (sh[i] == -1 && i >= off) sh[i] = this.shape[i - off]
    val src = this
    val out = zipB(Tensor(DoubleArray(numelOf(sh)), sh, dtype), this, dtype) { _, y -> y }
    if (!out.shape.sameAs(sh)) fail("expand: cannot expand shape ${this.shape.contentToString()} to ${sh.contentToString()}")
    if (gradOn(src)) out.node = Node(arrayOf(src)) { g -> arrayOf(g.sumTo(src.shape)) }
    return out
}

internal fun Tensor.broadcastRaw(shape: IntArray): Tensor =
    zipB(Tensor(DoubleArray(numelOf(shape)), shape.dup(), dtype), this, dtype) { _, y -> y }

// ---------------------------------------------------------------- binary operators

operator fun Tensor.plus(o: Tensor): Tensor {
    val out = zipB(this, o, promote(dtype, o.dtype)) { x, y -> x + y }
    val a = this
    if (gradOn(a, o)) out.node = Node(arrayOf(a, o)) { g -> arrayOf(g.sumTo(a.shape), g.sumTo(o.shape)) }
    return out
}

operator fun Tensor.minus(o: Tensor): Tensor {
    val out = zipB(this, o, promote(dtype, o.dtype)) { x, y -> x - y }
    val a = this
    if (gradOn(a, o)) out.node = Node(arrayOf(a, o)) { g -> arrayOf(g.sumTo(a.shape), (-g).sumTo(o.shape)) }
    return out
}

operator fun Tensor.times(o: Tensor): Tensor {
    val out = zipB(this, o, promote(dtype, o.dtype)) { x, y -> x * y }
    val a = this
    if (gradOn(a, o)) out.node = Node(arrayOf(a, o)) { g -> arrayOf((g * o).sumTo(a.shape), (g * a).sumTo(o.shape)) }
    return out
}

/** True division (Int64 / Int64 gives the default float dtype). */
operator fun Tensor.div(o: Tensor): Tensor {
    val out = zipB(this, o, floatDtype(promote(dtype, o.dtype))) { x, y -> x / y }
    val a = this
    if (gradOn(a, o)) out.node = Node(arrayOf(a, o)) { g -> arrayOf((g / o).sumTo(a.shape), (-(g * a) / (o * o)).sumTo(o.shape)) }
    return out
}

operator fun Tensor.unaryMinus(): Tensor {
    val out = map { -it }
    val a = this
    if (gradOn(a)) out.node = Node(arrayOf(a)) { g -> arrayOf(-g) }
    return out
}

internal fun scalarLike(c: Double, t: Tensor): Tensor = scalarTensor(c, if (t.dtype == DType.Int64) defaultDtype else t.dtype)
internal fun scalarLike(c: Int, t: Tensor): Tensor = scalarTensor(c.toDouble(), t.dtype)

operator fun Tensor.plus(c: Double): Tensor = this + scalarLike(c, this)
operator fun Tensor.minus(c: Double): Tensor = this - scalarLike(c, this)
operator fun Tensor.times(c: Double): Tensor = this * scalarLike(c, this)
operator fun Tensor.div(c: Double): Tensor = this / scalarLike(c, this)
operator fun Double.plus(t: Tensor): Tensor = scalarLike(this, t) + t
operator fun Double.minus(t: Tensor): Tensor = scalarLike(this, t) - t
operator fun Double.times(t: Tensor): Tensor = scalarLike(this, t) * t
operator fun Double.div(t: Tensor): Tensor = scalarLike(this, t) / t

operator fun Tensor.plus(c: Int): Tensor = this + scalarLike(c, this)
operator fun Tensor.minus(c: Int): Tensor = this - scalarLike(c, this)
operator fun Tensor.times(c: Int): Tensor = this * scalarLike(c, this)
operator fun Tensor.div(c: Int): Tensor = this / scalarLike(c, this)
operator fun Int.plus(t: Tensor): Tensor = scalarLike(this, t) + t
operator fun Int.minus(t: Tensor): Tensor = scalarLike(this, t) - t
operator fun Int.times(t: Tensor): Tensor = scalarLike(this, t) * t
operator fun Int.div(t: Tensor): Tensor = scalarLike(this, t) / t

// ---------------------------------------------------------------- comparisons (0/1 tensors of the promoted dtype)

fun Tensor.eq(o: Tensor): Tensor = zipB(this, o, promote(dtype, o.dtype)) { x, y -> if (x == y) 1.0 else 0.0 }
fun Tensor.gt(o: Tensor): Tensor = zipB(this, o, promote(dtype, o.dtype)) { x, y -> if (x > y) 1.0 else 0.0 }
fun Tensor.lt(o: Tensor): Tensor = zipB(this, o, promote(dtype, o.dtype)) { x, y -> if (x < y) 1.0 else 0.0 }
fun Tensor.ge(o: Tensor): Tensor = zipB(this, o, promote(dtype, o.dtype)) { x, y -> if (x >= y) 1.0 else 0.0 }
fun Tensor.le(o: Tensor): Tensor = zipB(this, o, promote(dtype, o.dtype)) { x, y -> if (x <= y) 1.0 else 0.0 }
fun Tensor.gt(c: Double): Tensor = gt(scalarLike(c, this))
fun Tensor.lt(c: Double): Tensor = lt(scalarLike(c, this))

/** True when both tensors have the same shape and all elements differ by at most `atol + rtol * |other|`. */
fun Tensor.allClose(o: Tensor, rtol: Double = 1e-5, atol: Double = 1e-8): Boolean {
    if (!shape.sameAs(o.shape)) return false
    for (i in 0 until data.size) {
        val d = kotlin.math.abs(data[i] - o.data[i])
        if (!(d <= atol + rtol * kotlin.math.abs(o.data[i]))) return false
    }
    return true
}

// ---------------------------------------------------------------- unary math

fun Tensor.exp(): Tensor {
    val out = unaryF(this) { kotlin.math.exp(it) }
    val a = this
    if (gradOn(a)) {
        val o = out.detach()
        out.node = Node(arrayOf(a)) { g -> arrayOf(g * o) }
    }
    return out
}

fun Tensor.log(): Tensor {
    val out = unaryF(this) { kotlin.math.ln(it) }
    val a = this
    if (gradOn(a)) out.node = Node(arrayOf(a)) { g -> arrayOf(g / a) }
    return out
}

fun Tensor.sqrt(): Tensor {
    val out = unaryF(this) { kotlin.math.sqrt(it) }
    val a = this
    if (gradOn(a)) {
        val o = out.detach()
        out.node = Node(arrayOf(a)) { g -> arrayOf(g / (o * 2.0)) }
    }
    return out
}

fun Tensor.tanh(): Tensor {
    val out = unaryF(this) { kotlin.math.tanh(it) }
    val a = this
    if (gradOn(a)) {
        val o = out.detach()
        out.node = Node(arrayOf(a)) { g -> arrayOf(g * (1.0 - o * o)) }
    }
    return out
}

fun Tensor.sigmoid(): Tensor {
    val out = unaryF(this) { if (it >= 0.0) 1.0 / (1.0 + kotlin.math.exp(-it)) else { val e = kotlin.math.exp(it); e / (1.0 + e) } }
    val a = this
    if (gradOn(a)) {
        val o = out.detach()
        out.node = Node(arrayOf(a)) { g -> arrayOf(g * o * (1.0 - o)) }
    }
    return out
}

fun Tensor.relu(): Tensor {
    val out = map { if (it > 0.0) it else 0.0 }
    val a = this
    if (gradOn(a)) out.node = Node(arrayOf(a)) { g -> arrayOf(g * a.map { if (it > 0.0) 1.0 else 0.0 }) }
    return out
}

fun Tensor.abs(): Tensor {
    val out = map { kotlin.math.abs(it) }
    val a = this
    if (gradOn(a)) out.node = Node(arrayOf(a)) { g -> arrayOf(g * a.map { if (it > 0.0) 1.0 else if (it < 0.0) -1.0 else 0.0 }) }
    return out
}

fun Tensor.sin(): Tensor {
    val out = unaryF(this) { kotlin.math.sin(it) }
    val a = this
    if (gradOn(a)) out.node = Node(arrayOf(a)) { g -> arrayOf(g * a.cos()) }
    return out
}

fun Tensor.cos(): Tensor {
    val out = unaryF(this) { kotlin.math.cos(it) }
    val a = this
    if (gradOn(a)) out.node = Node(arrayOf(a)) { g -> arrayOf(-(g * a.sin())) }
    return out
}

/** Elementwise power with a scalar exponent. */
fun Tensor.pow(e: Double): Tensor {
    val out = unaryF(this) { it.pow(e) }
    val a = this
    if (gradOn(a)) out.node = Node(arrayOf(a)) { g -> arrayOf(g * (a.pow(e - 1.0) * e)) }
    return out
}

fun Tensor.square(): Tensor = this * this

fun Tensor.clamp(min: Double, max: Double): Tensor {
    val out = map { if (it < min) min else if (it > max) max else it }
    val a = this
    if (gradOn(a)) out.node = Node(arrayOf(a)) { g -> arrayOf(g * a.map { if (it < min || it > max) 0.0 else 1.0 }) }
    return out
}

fun Tensor.leakyRelu(slope: Double = 0.01): Tensor {
    val out = map { if (it > 0.0) it else it * slope }
    val a = this
    if (gradOn(a)) out.node = Node(arrayOf(a)) { g -> arrayOf(g * a.map { if (it > 0.0) 1.0 else slope }) }
    return out
}
