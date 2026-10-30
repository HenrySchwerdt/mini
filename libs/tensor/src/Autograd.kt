package mini.tensor

/**
 * One recorded operation: the tensors it read and a function that maps the gradient of its output to the gradients of
 * the inputs (null for an input that gets none). The closure must not capture the output tensor itself (reference
 * cycle); capture `out.detach()` instead.
 */
class Node internal constructor(val inputs: Array<Tensor>, val back: (Tensor) -> Array<Tensor?>)

internal var gradEnabled: Boolean = true
private var markCounter: Int = 0

/** Is gradient recording on right now? */
fun isGradEnabled(): Boolean = gradEnabled

/** Runs `block` without recording operations (PyTorch: `with torch.no_grad():`). */
fun <R> noGrad(block: () -> R): R {
    val prev = gradEnabled
    gradEnabled = false
    try {
        return block()
    } finally {
        gradEnabled = prev
    }
}

internal fun gradOn(a: Tensor): Boolean = gradEnabled && (a.requiresGrad || a.node != null)
internal fun gradOn(a: Tensor, b: Tensor): Boolean = gradEnabled && (a.requiresGrad || a.node != null || b.requiresGrad || b.node != null)

/** True for a tensor produced by a recorded operation (it has a `gradFn`). */
val Tensor.isLeaf: Boolean get() = node == null

/** Sets `requiresGrad` and returns the tensor (`Tensor.requiresGrad()` like PyTorch's `requires_grad_()`). */
fun Tensor.requiresGrad(flag: Boolean = true): Tensor {
    requiresGrad = flag
    return this
}

/**
 * Backpropagation from this tensor: fills `grad` of every leaf (a tensor with `requiresGrad = true`) it depends on.
 * A scalar needs no argument; otherwise pass the gradient of this tensor. Gradients accumulate over calls
 * (use `zeroGrad()` / `optimizer.zeroGrad()`).
 */
fun Tensor.backward(gradient: Tensor? = null) {
    if (!requiresGrad && node == null) fail("backward(): the tensor does not require grad and has no grad_fn")
    val seed: Tensor
    if (gradient == null) {
        if (data.size != 1) fail("backward(): grad can be implicitly created only for scalar outputs, got shape ${shape.contentToString()}")
        seed = Tensor(DoubleArray(1), shape.dup(), dtype)
        seed.data[0] = 1.0
    } else {
        if (!gradient.shape.sameAs(shape)) fail("backward(): gradient shape ${gradient.shape.contentToString()} differs from ${shape.contentToString()}")
        seed = gradient
    }
    markCounter++
    val m = markCounter
    // iterative post-order DFS: inputs come before the tensors computed from them
    val order = ArrayList<Tensor>()
    val stack = ArrayList<Tensor>()
    val next = ArrayList<Int>()
    mark = m
    stack.add(this)
    next.add(0)
    while (stack.size > 0) {
        val top = stack[stack.size - 1]
        val i = next[next.size - 1]
        val nd = top.node
        if (nd != null && i < nd.inputs.size) {
            next[next.size - 1] = i + 1
            val child = nd.inputs[i]
            if (child.mark != m && (child.requiresGrad || child.node != null)) {
                child.mark = m
                stack.add(child)
                next.add(0)
            }
        } else {
            order.add(top)
            stack.removeAt(stack.size - 1)
            next.removeAt(next.size - 1)
        }
    }
    val prev = gradEnabled
    gradEnabled = false
    try {
        gradAcc = seed
        var k = order.size - 1
        while (k >= 0) {
            val t = order[k]
            k--
            val g = t.gradAcc ?: continue
            t.gradAcc = null
            val nd = t.node
            if (nd != null) {
                val gs = nd.back(g)
                for (j in 0 until nd.inputs.size) {
                    val gi = gs[j] ?: continue
                    val inp = nd.inputs[j]
                    if (!(inp.requiresGrad || inp.node != null)) continue
                    val old = inp.gradAcc
                    inp.gradAcc = if (old == null) gi else old + gi
                }
            } else if (t.requiresGrad) {
                val old = t.grad
                t.grad = if (old == null) g.clone() else old + g
            }
        }
    } finally {
        gradEnabled = prev
    }
}

/** Sets `grad` to null. */
fun Tensor.zeroGrad() { grad = null }
