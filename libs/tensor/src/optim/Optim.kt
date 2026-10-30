package mini.tensor.optim

import mini.tensor.*

/** Base of the optimizers: `zeroGrad()` before `loss.backward()`, `step()` after. Parameters are updated in place. */
abstract class Optimizer(val params: List<Tensor>) {
    fun zeroGrad() {
        for (p in params) p.grad = null
    }

    abstract fun step()
}

/** Stochastic gradient descent with optional momentum and weight decay. */
class SGD(params: List<Tensor>, val lr: Double = 0.01, val momentum: Double = 0.0, val weightDecay: Double = 0.0) : Optimizer(params) {
    private val velocity: List<DoubleArray>

    init {
        val v = ArrayList<DoubleArray>()
        for (p in params) v.add(DoubleArray(p.data.size))
        velocity = v
    }

    override fun step() {
        for (k in 0 until params.size) {
            val p = params[k]
            val g = p.grad ?: continue
            val v = velocity[k]
            for (i in 0 until p.data.size) {
                var gi = g.data[i] + weightDecay * p.data[i]
                if (momentum != 0.0) {
                    v[i] = momentum * v[i] + gi
                    gi = v[i]
                }
                p.data[i] = p.data[i] - lr * gi
            }
            p.data.roundTo(p.dtype)
        }
    }
}

/** Adam (Kingma and Ba) with the PyTorch defaults. */
class Adam(params: List<Tensor>, val lr: Double = 0.001, val beta1: Double = 0.9, val beta2: Double = 0.999,
           val eps: Double = 1e-8, val weightDecay: Double = 0.0) : Optimizer(params) {
    private val m: List<DoubleArray>
    private val v: List<DoubleArray>
    private var steps: Int = 0

    init {
        val a = ArrayList<DoubleArray>()
        val b = ArrayList<DoubleArray>()
        for (p in params) {
            a.add(DoubleArray(p.data.size))
            b.add(DoubleArray(p.data.size))
        }
        m = a
        v = b
    }

    override fun step() {
        steps++
        val c1 = 1.0 - kotlin.math.exp(steps * kotlin.math.ln(beta1))
        val c2 = 1.0 - kotlin.math.exp(steps * kotlin.math.ln(beta2))
        for (k in 0 until params.size) {
            val p = params[k]
            val g = p.grad ?: continue
            val mk = m[k]
            val vk = v[k]
            for (i in 0 until p.data.size) {
                val gi = g.data[i] + weightDecay * p.data[i]
                mk[i] = beta1 * mk[i] + (1.0 - beta1) * gi
                vk[i] = beta2 * vk[i] + (1.0 - beta2) * gi * gi
                val mh = mk[i] / c1
                val vh = vk[i] / c2
                p.data[i] = p.data[i] - lr * mh / (kotlin.math.sqrt(vh) + eps)
            }
            p.data.roundTo(p.dtype)
        }
    }
}
