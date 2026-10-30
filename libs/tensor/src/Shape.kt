package mini.tensor

// Shape operations: view/reshape, permute/transpose, squeeze/unsqueeze, slicing, indexing, cat/stack.

private fun inferShape(numel: Int, shape: IntArray): IntArray {
    val r = shape.dup()
    var unknown = -1
    var known = 1
    for (i in 0 until r.size) {
        if (r[i] == -1) {
            if (unknown >= 0) fail("only one dimension can be inferred (-1) in shape ${shape.contentToString()}")
            unknown = i
        } else {
            if (r[i] < 0) fail("invalid dimension ${r[i]} in shape ${shape.contentToString()}")
            known *= r[i]
        }
    }
    if (unknown >= 0) {
        if (known == 0 || numel % known != 0) fail("shape ${shape.contentToString()} is invalid for input with $numel elements")
        r[unknown] = numel / known
    } else if (known != numel) {
        fail("shape ${shape.contentToString()} is invalid for input with $numel elements")
    }
    return r
}

/** Same data, new shape (shares the storage). One dimension may be -1. */
fun Tensor.view(vararg shape: Int): Tensor {
    val sh = inferShape(data.size, shape)
    val out = Tensor(data, sh, dtype)
    val a = this
    if (gradOn(a)) out.node = Node(arrayOf(a)) { g -> arrayOf(g.view(*a.shape)) }
    return out
}

fun Tensor.reshape(vararg shape: Int): Tensor = view(*shape)

fun Tensor.flatten(): Tensor = view(-1)

fun Tensor.unsqueeze(dim: Int): Tensor {
    val d = normDim(dim, shape.size + 1)
    val sh = IntArray(shape.size + 1)
    var j = 0
    for (i in 0 until sh.size) {
        if (i == d) sh[i] = 1 else { sh[i] = shape[j]; j++ }
    }
    return view(*sh)
}

/** Removes every dimension of size 1. */
fun Tensor.squeeze(): Tensor {
    var n = 0
    for (s in shape) if (s != 1) n++
    val sh = IntArray(n)
    var j = 0
    for (s in shape) if (s != 1) { sh[j] = s; j++ }
    return view(*sh)
}

/** Removes dimension `dim` when it has size 1. */
fun Tensor.squeeze(dim: Int): Tensor {
    val d = normDim(dim, shape.size)
    if (shape[d] != 1) return this
    val sh = IntArray(shape.size - 1)
    var j = 0
    for (i in 0 until shape.size) if (i != d) { sh[j] = shape[i]; j++ }
    return view(*sh)
}

fun Tensor.permute(vararg dims: Int): Tensor {
    val nd = shape.size
    if (dims.size != nd) fail("permute: expected $nd dimensions, got ${dims.size}")
    val perm = IntArray(nd)
    val seen = BooleanArray(nd)
    var identity = true
    for (i in 0 until nd) {
        val d = normDim(dims[i], nd)
        if (seen[d]) fail("permute: repeated dimension $d")
        seen[d] = true
        perm[i] = d
        if (d != i) identity = false
    }
    if (identity) return this
    val osh = IntArray(nd)
    val ost = IntArray(nd)
    for (i in 0 until nd) {
        osh[i] = shape[perm[i]]
        ost[i] = strides[perm[i]]
    }
    val n = data.size
    val r = DoubleArray(n)
    val src = data
    if (nd == 2) {
        val rows = osh[0]
        val cols = osh[1]
        val s0 = ost[0]
        val s1 = ost[1]
        for (i in 0 until rows) {
            val base = i * s0
            val ro = i * cols
            for (j in 0 until cols) r[ro + j] = src[base + j * s1]
        }
    } else {
        val idx = IntArray(nd)
        var off = 0
        for (p in 0 until n) {
            r[p] = src[off]
            var d = nd - 1
            while (d >= 0) {
                idx[d] += 1
                off += ost[d]
                if (idx[d] < osh[d]) break
                off -= ost[d] * osh[d]
                idx[d] = 0
                d--
            }
        }
    }
    val out = Tensor(r, osh, dtype)
    val a = this
    if (gradOn(a)) {
        val inv = IntArray(nd)
        for (i in 0 until nd) inv[perm[i]] = i
        out.node = Node(arrayOf(a)) { g -> arrayOf(g.permute(*inv)) }
    }
    return out
}

fun Tensor.transpose(dim0: Int, dim1: Int): Tensor {
    val nd = shape.size
    val a = normDim(dim0, nd)
    val b = normDim(dim1, nd)
    val perm = IntArray(nd)
    for (i in 0 until nd) perm[i] = i
    perm[a] = b
    perm[b] = a
    return permute(*perm)
}

/** Transposes a matrix (vectors and scalars are returned unchanged), like `torch.t()`. */
fun Tensor.t(): Tensor {
    if (shape.size > 2) fail("t() expects a tensor with at most 2 dimensions, got ${shape.size}")
    return if (shape.size < 2) this else transpose(0, 1)
}

private fun outerInner(shape: IntArray, d: Int): IntArray {
    var outer = 1
    for (i in 0 until d) outer *= shape[i]
    var inner = 1
    for (i in d + 1 until shape.size) inner *= shape[i]
    return intArrayOf(outer, shape[d], inner)
}

/** Elements start, start+step, ... below `end` along `dim` (end defaults to the size; negative values count from the end). */
fun Tensor.slice(dim: Int, start: Int = 0, end: Int = Int.MAX_VALUE, step: Int = 1): Tensor {
    val d = normDim(dim, shape.size)
    if (step <= 0) fail("slice: step must be positive, got $step")
    val size = shape[d]
    var s = if (start < 0) start + size else start
    var e = if (end < 0) end + size else end
    if (s < 0) s = 0
    if (s > size) s = size
    if (e > size) e = size
    if (e < s) e = s
    val cnt = (e - s + step - 1) / step
    val oi = outerInner(shape, d)
    val outer = oi[0]
    val inner = oi[2]
    val osh = shape.dup()
    osh[d] = cnt
    val r = DoubleArray(outer * cnt * inner)
    for (o in 0 until outer) {
        for (k in 0 until cnt) {
            val srcBase = (o * size + s + k * step) * inner
            val dstBase = (o * cnt + k) * inner
            for (i in 0 until inner) r[dstBase + i] = data[srcBase + i]
        }
    }
    val out = Tensor(r, osh, dtype)
    val a = this
    if (gradOn(a)) {
        val st = s
        out.node = Node(arrayOf(a)) { g ->
            val z = DoubleArray(a.data.size)
            for (o in 0 until outer) {
                for (k in 0 until cnt) {
                    val dstBase = (o * size + st + k * step) * inner
                    val srcBase = (o * cnt + k) * inner
                    for (i in 0 until inner) z[dstBase + i] += g.data[srcBase + i]
                }
            }
            arrayOf(Tensor(z, a.shape.dup(), a.dtype))
        }
    }
    return out
}

fun Tensor.narrow(dim: Int, start: Int, length: Int): Tensor = slice(dim, start, start + length)

/** The sub-tensor at `index` along `dim` (that dimension disappears). */
fun Tensor.select(dim: Int, index: Int): Tensor {
    val d = normDim(dim, shape.size)
    val i = if (index < 0) index + shape[d] else index
    if (i < 0 || i >= shape[d]) fail("index $index is out of bounds for dimension $d with size ${shape[d]}")
    val sh = IntArray(shape.size - 1)
    var j = 0
    for (k in 0 until shape.size) if (k != d) { sh[j] = shape[k]; j++ }
    return slice(d, i, i + 1).view(*sh)
}

fun Tensor.row(i: Int): Tensor = select(0, i)
fun Tensor.col(j: Int): Tensor = select(1, j)

/** Gathers the entries `idx` (an Int64 vector) along `dim`: `x.indexSelect(0, perm)` shuffles rows. */
fun Tensor.indexSelect(dim: Int, idx: Tensor): Tensor {
    if (idx.shape.size != 1) fail("indexSelect: the index must be a vector, got shape ${idx.shape.contentToString()}")
    val d = normDim(dim, shape.size)
    val size = shape[d]
    val oi = outerInner(shape, d)
    val outer = oi[0]
    val inner = oi[2]
    val cnt = idx.data.size
    val osh = shape.dup()
    osh[d] = cnt
    val ix = IntArray(cnt)
    for (k in 0 until cnt) {
        val v = idx.data[k].toInt()
        if (v < 0 || v >= size) fail("indexSelect: index $v is out of bounds for dimension $d with size $size")
        ix[k] = v
    }
    val r = DoubleArray(outer * cnt * inner)
    for (o in 0 until outer) {
        for (k in 0 until cnt) {
            val srcBase = (o * size + ix[k]) * inner
            val dstBase = (o * cnt + k) * inner
            for (i in 0 until inner) r[dstBase + i] = data[srcBase + i]
        }
    }
    val out = Tensor(r, osh, dtype)
    val a = this
    if (gradOn(a)) {
        out.node = Node(arrayOf(a)) { g ->
            val z = DoubleArray(a.data.size)
            for (o in 0 until outer) {
                for (k in 0 until cnt) {
                    val dstBase = (o * size + ix[k]) * inner
                    val srcBase = (o * cnt + k) * inner
                    for (i in 0 until inner) z[dstBase + i] += g.data[srcBase + i]
                }
            }
            arrayOf(Tensor(z, a.shape.dup(), a.dtype))
        }
    }
    return out
}

/** Concatenates along an existing dimension. */
fun cat(tensors: List<Tensor>, dim: Int = 0): Tensor {
    if (tensors.size == 0) fail("cat: needs at least one tensor")
    val first = tensors[0]
    val nd = first.shape.size
    if (nd == 0) fail("cat: zero-dimensional tensors cannot be concatenated")
    val d = normDim(dim, nd)
    var total = 0
    var dt = first.dtype
    for (t in tensors) {
        if (t.shape.size != nd) fail("cat: tensors must have the same number of dimensions, got ${first.shape.contentToString()} and ${t.shape.contentToString()}")
        for (i in 0 until nd) {
            if (i != d && t.shape[i] != first.shape[i]) fail("cat: shapes ${first.shape.contentToString()} and ${t.shape.contentToString()} differ in dimension $i")
        }
        total += t.shape[d]
        dt = promote(dt, t.dtype)
    }
    val osh = first.shape.dup()
    osh[d] = total
    val oi = outerInner(osh, d)
    val outer = oi[0]
    val inner = oi[2]
    val r = DoubleArray(numelOf(osh))
    var pos = 0
    for (t in tensors) {
        val n = t.shape[d]
        for (o in 0 until outer) {
            val srcBase = o * n * inner
            val dstBase = (o * total + pos) * inner
            for (i in 0 until n * inner) r[dstBase + i] = t.data[srcBase + i]
        }
        pos += n
    }
    val out = finish(r, osh, dt)
    var any = false
    for (t in tensors) if (gradOn(t)) any = true
    if (any) {
        val ins = Array<Tensor>(tensors.size) { tensors[it] }
        out.node = Node(ins) { g ->
            val res = arrayOfNulls<Tensor>(ins.size)
            var p = 0
            for (k in 0 until ins.size) {
                res[k] = g.slice(d, p, p + ins[k].shape[d])
                p += ins[k].shape[d]
            }
            res
        }
    }
    return out
}

fun cat(vararg tensors: Tensor, dim: Int = 0): Tensor {
    val l = ArrayList<Tensor>()
    for (t in tensors) l.add(t)
    return cat(l, dim)
}

/** Joins tensors of equal shape along a new dimension. */
fun stack(tensors: List<Tensor>, dim: Int = 0): Tensor {
    if (tensors.size == 0) fail("stack: needs at least one tensor")
    val nd = tensors[0].shape.size
    val d = normDim(dim, nd + 1)
    val l = ArrayList<Tensor>()
    for (t in tensors) {
        if (!t.shape.sameAs(tensors[0].shape)) fail("stack: all tensors must have the same shape, got ${tensors[0].shape.contentToString()} and ${t.shape.contentToString()}")
        l.add(t.unsqueeze(d))
    }
    return cat(l, d)
}

fun stack(vararg tensors: Tensor, dim: Int = 0): Tensor {
    val l = ArrayList<Tensor>()
    for (t in tensors) l.add(t)
    return stack(l, dim)
}
