package mini.tensor.nn

import mini.tensor.*

/** Base class of layers (torch.nn.Module). A module is called like a function: `model(x)`. */
abstract class Module {
    abstract fun forward(x: Tensor): Tensor

    /** The trainable tensors of this module and its children. */
    open fun parameters(): List<Tensor> = ArrayList<Tensor>()

    operator fun invoke(x: Tensor): Tensor = forward(x)

    fun zeroGrad() {
        for (p in parameters()) p.grad = null
    }
}

/** y = x W^T + b with `weight` of shape [out, in]; weights are uniform in +-1/sqrt(in) like PyTorch. */
class Linear(val inFeatures: Int, val outFeatures: Int, withBias: Boolean = true, generator: Generator = defaultGenerator, dtype: DType = defaultDtype) : Module() {
    val weight: Tensor
    val bias: Tensor?

    init {
        val bound = 1.0 / kotlin.math.sqrt(inFeatures.toDouble())
        weight = ((rand(outFeatures, inFeatures, generator = generator, dtype = dtype) * 2.0 - 1.0) * bound).requiresGrad()
        bias = if (withBias) ((rand(outFeatures, generator = generator, dtype = dtype) * 2.0 - 1.0) * bound).requiresGrad() else null
    }

    override fun forward(x: Tensor): Tensor {
        val y = x.matmul(weight.t())
        val b = bias
        return if (b == null) y else y + b
    }

    override fun parameters(): List<Tensor> {
        val l = ArrayList<Tensor>()
        l.add(weight)
        val b = bias
        if (b != null) l.add(b)
        return l
    }
}

class ReLU : Module() {
    override fun forward(x: Tensor): Tensor = x.relu()
}

class Tanh : Module() {
    override fun forward(x: Tensor): Tensor = x.tanh()
}

class Sigmoid : Module() {
    override fun forward(x: Tensor): Tensor = x.sigmoid()
}

class Softmax(val dim: Int = -1) : Module() {
    override fun forward(x: Tensor): Tensor = x.softmax(dim)
}

/** Flattens all dimensions but the first. */
class Flatten : Module() {
    override fun forward(x: Tensor): Tensor = if (x.ndim <= 1) x else x.view(x.shape[0], -1)
}

/** Applies the layers one after the other. */
class Sequential(vararg layers: Module) : Module() {
    val layers: List<Module>

    init {
        val l = ArrayList<Module>()
        for (m in layers) l.add(m)
        this.layers = l
    }

    override fun forward(x: Tensor): Tensor {
        var y = x
        for (m in layers) y = m.forward(y)
        return y
    }

    override fun parameters(): List<Tensor> {
        val l = ArrayList<Tensor>()
        for (m in layers) for (p in m.parameters()) l.add(p)
        return l
    }

    operator fun get(i: Int): Module = layers[i]
}

// ---------------------------------------------------------------- losses

/** Mean squared error over all elements. */
fun mseLoss(pred: Tensor, target: Tensor): Tensor {
    val d = pred - target
    return (d * d).mean()
}

/** Mean absolute error. */
fun l1Loss(pred: Tensor, target: Tensor): Tensor = (pred - target).abs().mean()

/** Negative log likelihood of log-probabilities [N, C] for the class indices `target` (Int64, [N]), averaged. */
fun nllLoss(logProbs: Tensor, target: Tensor): Tensor {
    if (logProbs.ndim != 2) fail("nllLoss: expected log-probabilities of shape [N, C], got ${logProbs.shape.contentToString()}")
    val n = logProbs.shape[0]
    val c = logProbs.shape[1]
    if (target.ndim != 1 || target.shape[0] != n) fail("nllLoss: expected ${n} class indices, got a tensor of shape ${target.shape.contentToString()}")
    var s = 0.0
    val ix = IntArray(n)
    for (i in 0 until n) {
        val t = target.data[i].toInt()
        if (t < 0 || t >= c) fail("nllLoss: class index $t is out of range for $c classes")
        ix[i] = t
        s -= logProbs.data[i * c + t]
    }
    val out = scalarTensor(s / n, logProbs.dtype)
    if (gradOn(logProbs)) {
        out.node = Node(arrayOf(logProbs)) { g ->
            val z = DoubleArray(n * c)
            val v = -g.data[0] / n
            for (i in 0 until n) z[i * c + ix[i]] = v
            arrayOf(Tensor(z, logProbs.shape.dup(), logProbs.dtype))
        }
    }
    return out
}

/** Softmax cross entropy of raw scores [N, C] against class indices [N] (torch.nn.functional.cross_entropy). */
fun crossEntropy(logits: Tensor, target: Tensor): Tensor = nllLoss(logits.logSoftmax(1), target)

class MSELoss : Module() {
    override fun forward(x: Tensor): Tensor = fail("MSELoss takes two arguments: use loss(pred, target)")
    operator fun invoke(pred: Tensor, target: Tensor): Tensor = mseLoss(pred, target)
}

class CrossEntropyLoss : Module() {
    override fun forward(x: Tensor): Tensor = fail("CrossEntropyLoss takes two arguments: use loss(logits, target)")
    operator fun invoke(logits: Tensor, target: Tensor): Tensor = crossEntropy(logits, target)
}
