import kotlin.test.*
import mini.tensor.*
import mini.tensor.nn.*
import mini.tensor.optim.*

/** Numerical gradient of a scalar function at x (central differences, float64). */
private fun numGrad(x: Tensor, f: (Tensor) -> Tensor): Tensor {
    val g = zerosLike(x)
    val h = 1e-6
    for (i in 0 until x.numel) {
        val orig = x.data[i]
        x.data[i] = orig + h
        val up = f(x).item()
        x.data[i] = orig - h
        val dn = f(x).item()
        x.data[i] = orig
        g.data[i] = (up - dn) / (2.0 * h)
    }
    return g
}

private fun check(x: Tensor, f: (Tensor) -> Tensor) {
    x.requiresGrad = true
    x.grad = null
    f(x).backward()
    val analytic = x.grad!!
    val numeric = noGrad { numGrad(x, f) }
    assertTrue(analytic.allClose(numeric, 1e-4, 1e-6), "analytic $analytic numeric $numeric")
}

class AutogradTest {
    @Test
    fun simpleChain() {
        val x = tensor(3.0, requiresGrad = true)
        val y = x * x + x * 2.0
        y.backward()
        assertEquals(8.0, x.grad!!.item())
        y.backward()  // accumulates
        assertEquals(16.0, x.grad!!.item())
        x.zeroGrad()
        assertNull(x.grad)
    }

    @Test
    fun broadcastAndReduce() {
        val w = tensor(1.0, 2.0, 3.0, requiresGrad = true)
        val x = tensor(doubleArrayOf(1.0, 1.0, 1.0), doubleArrayOf(2.0, 2.0, 2.0))
        val loss = (x * w).sum()
        loss.backward()
        assertEquals("tensor([3., 3., 3.], requires_grad=True)".length > 0, true)
        assertEquals("tensor([3., 3., 3.])", w.grad.toString())
    }

    @Test
    fun noGradBlockAndDetach() {
        val x = tensor(1.0, 2.0, requiresGrad = true)
        val y = noGrad { x * 2.0 }
        assertFalse(y.requiresGrad)
        assertTrue(y.isLeaf)
        assertFailsWith<IllegalArgumentException> { y.sum().backward() }
        val z = x.detach() * 2.0
        assertTrue(z.isLeaf)
        assertTrue(isGradEnabled())
        assertFailsWith<IllegalArgumentException> { (x * 2.0).backward() }  // not a scalar
    }

    @Test
    fun gradientChecks() {
        val g = Generator(3)
        check(randn(2, 3, generator = g, dtype = DType.Float64)) { x -> (x.exp() * x).sum() }
        check(randn(2, 3, generator = g, dtype = DType.Float64)) { x -> (x.tanh() + x.sigmoid() * x).mean() }
        check(randn(3, generator = g, dtype = DType.Float64)) { x -> (x * x + 1.0).log().sum() }
        check(rand(4, generator = g, dtype = DType.Float64) + 0.5) { x -> (x.sqrt() / (x + 1.0)).sum() }
        check(randn(2, 3, generator = g, dtype = DType.Float64)) { x -> x.pow(3.0).sum() }
        val w = randn(3, 2, generator = g, dtype = DType.Float64)
        check(randn(4, 3, generator = g, dtype = DType.Float64)) { x -> (x @ w).tanh().sum() }
        val a = randn(4, 3, generator = g, dtype = DType.Float64)
        check(randn(3, 2, generator = g, dtype = DType.Float64)) { x -> ((a @ x) * (a @ x)).sum() }
        check(randn(3, generator = g, dtype = DType.Float64)) { x -> (a @ x).sum() }
        check(randn(4, generator = g, dtype = DType.Float64)) { x -> (x @ a).pow(2.0).sum() }
        check(randn(2, 3, generator = g, dtype = DType.Float64)) { x -> x.softmax(1).pow(2.0).sum() }
        check(randn(2, 3, generator = g, dtype = DType.Float64)) { x -> x.logSoftmax(0).tanh().sum() }
        check(randn(2, 3, generator = g, dtype = DType.Float64)) { x -> x.sum(0).pow(2.0).sum() + x.mean(1, true).pow(2.0).sum() }
        check(randn(2, 3, generator = g, dtype = DType.Float64)) { x -> x.max(1).values.pow(2.0).sum() }
        check(randn(2, 3, generator = g, dtype = DType.Float64)) { x -> (x.T @ x).pow(2.0).sum() }
        check(randn(2, 3, 4, generator = g, dtype = DType.Float64)) { x -> x.permute(2, 0, 1).slice(0, 1, 3).pow(2.0).sum() }
        check(randn(2, 3, generator = g, dtype = DType.Float64)) { x -> cat(x, x * 2.0, dim = 1).pow(2.0).sum() }
        check(randn(2, 3, generator = g, dtype = DType.Float64)) { x -> stack(x[0], x[1] * x[1]).pow(2.0).sum() }
        check(randn(3, 3, generator = g, dtype = DType.Float64)) { x -> x.indexSelect(0, tensor(2.0, 2.0, 0.0).to(DType.Int64)).pow(2.0).sum() }
        val tgt = tensor(0.0, 2.0).to(DType.Int64)
        check(randn(2, 3, generator = g, dtype = DType.Float64)) { x -> crossEntropy(x, tgt) }
        check(randn(2, 3, generator = g, dtype = DType.Float64)) { x -> mseLoss(x, ones(2, 3, dtype = DType.Float64)) }
        check(randn(3, generator = g, dtype = DType.Float64)) { x -> x.norm() }
        check(randn(2, 2, generator = g, dtype = DType.Float64)) { x -> x.expand(3, 2, 2).pow(2.0).sum() }
    }

    @Test
    fun linearAndSequential() {
        manualSeed(1)
        val model = Sequential(Linear(3, 4), ReLU(), Linear(4, 2))
        assertEquals(4, model.parameters().size)
        val x = randn(5, 3)
        val y = model(x)
        assertEquals(5, y.shape[0]); assertEquals(2, y.shape[1])
        val loss = MSELoss()(y, zeros(5, 2))
        loss.backward()
        for (p in model.parameters()) assertNotNull(p.grad)
        model.zeroGrad()
        assertNull(model.parameters()[0].grad)
    }

    @Test
    fun optimizersReduceLoss() {
        for (useAdam in listOf(false, true)) {
            manualSeed(2)
            val w = tensor(5.0, -3.0, requiresGrad = true)
            val opt: Optimizer = if (useAdam) Adam(listOf(w), lr = 0.2) else SGD(listOf(w), lr = 0.1, momentum = 0.5)
            for (i in 0 until 200) {
                opt.zeroGrad()
                val loss = (w * w).sum()
                loss.backward()
                opt.step()
            }
            assertTrue(w.abs().max().item() < 0.05, "w = $w")
        }
    }
}
