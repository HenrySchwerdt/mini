import kotlin.test.*
import mini.tensor.*

private fun m23(): Tensor = tensor(doubleArrayOf(1.0, 2.0, 3.0), doubleArrayOf(4.0, 5.0, 6.0))

class OpsTest {
    @Test
    fun scalarOps() {
        val a = m23()
        assertEquals("tensor([[2., 3., 4.],\n        [5., 6., 7.]])", (a + 1.0).toString())
        assertEquals("tensor([[0., 1., 2.],\n        [3., 4., 5.]])", (a - 1).toString())
        assertEquals(12.0, (2.0 * a).at(1, 2))
        assertEquals(0.5, (1.0 / a).at(0, 1))
        assertEquals(-3.0, (-a).at(0, 2))
        assertEquals(1.5, (a / 2.0).at(0, 2) )
    }

    @Test
    fun broadcasting() {
        val a = m23()
        val row = tensor(10.0, 20.0, 30.0)
        val col = tensor(doubleArrayOf(100.0), doubleArrayOf(200.0))
        val r = a + row
        assertEquals(33.0, r.at(0, 2))
        assertEquals(36.0, r.at(1, 2))
        val c = a + col
        assertEquals(101.0, c.at(0, 0))
        assertEquals(206.0, c.at(1, 2))
        // outer sum: [2,1] + [1,3] -> [2,3]; [3] * [2,1,1]
        val o = col + row.view(1, 3)
        assertEquals(2, o.shape[0]); assertEquals(3, o.shape[1])
        assertEquals(230.0, o.at(1, 2))
        val big = tensor(1.0, 2.0) .view(2, 1, 1) * tensor(1.0, 10.0, 100.0)
        assertEquals(2, big.shape[0]); assertEquals(1, big.shape[1]); assertEquals(3, big.shape[2])
        assertEquals(200.0, big.at(1, 0, 2))
        // scalar tensor
        assertEquals(7.0, (a + scalarTensor(1.0)).at(1, 0) + 2.0)
    }

    @Test
    fun broadcastErrors() {
        val e = assertFailsWith<IllegalArgumentException> { m23() + tensor(1.0, 2.0) }
        assertTrue(e.message!!.contains("cannot be broadcast"), e.message)
        assertTrue(e.message!!.contains("[2, 3]") && e.message!!.contains("[2]"), e.message)
    }

    @Test
    fun reductions() {
        val a = m23()
        assertEquals(21.0, a.sum().item())
        assertEquals(3.5, a.mean().item())
        assertEquals("tensor([5., 7., 9.])", a.sum(0).toString())
        assertEquals("tensor([ 6., 15.])", a.sum(1).toString())
        assertEquals("tensor([[ 6.],\n        [15.]])", a.sum(1, true).toString())
        assertEquals("tensor([2.5000, 3.5000, 4.5000])", a.mean(0).toString())
        assertEquals("tensor([2., 5.])", a.mean(-1).toString())
        assertEquals(6.0, a.max().item())
        assertEquals(1.0, a.min().item())
        assertEquals(5, a.argmax().item().toInt())
        val (v, i) = a.max(1)
        assertEquals("tensor([3., 6.])", v.toString())
        assertEquals("tensor([2, 2])", i.toString())
        assertEquals("tensor([0, 0, 0])", a.argmin(0).toString())
        assertEquals(DType.Int64, a.argmax(1).dtype)
        assertEquals(3.0, a.min(0).values.at(2))
    }

    @Test
    fun math() {
        val x = tensor(0.0, 1.0, -1.0)
        assertTrue(x.exp().allClose(tensor(1.0, 2.718281828, 0.367879441), rtol = 1e-5))
        assertTrue(x.tanh().allClose(tensor(0.0, 0.761594156, -0.761594156), rtol = 1e-5))
        assertTrue(x.sigmoid().allClose(tensor(0.5, 0.731058579, 0.268941421), rtol = 1e-5))
        assertEquals("tensor([0., 1., 0.])", x.relu().toString())
        assertTrue(tensor(1.0, 4.0, 9.0).sqrt().allClose(tensor(1.0, 2.0, 3.0)))
        assertTrue(tensor(1.0, 2.718281828).log().allClose(tensor(0.0, 1.0), atol = 1e-6))
        assertEquals(5.0, tensor(3.0, 4.0).norm().item())
        val s = tensor(doubleArrayOf(1.0, 2.0, 3.0), doubleArrayOf(1.0, 1.0, 1.0)).softmax(1)
        assertTrue(kotlin.math.abs(s.sum(1).at(0) - 1.0) < 1e-6)
        assertTrue(kotlin.math.abs(s.at(1, 1) - 0.3333333) < 1e-6)
        assertTrue(s.sum(1).allClose(ones(2)))
        val ls = tensor(1.0, 2.0, 3.0).logSoftmax(0)
        assertTrue(ls.exp().sum().allClose(scalarTensor(1.0)))
    }

    @Test
    fun shapes() {
        val a = arange(6, ).to(DType.Float32)
        val m = a.view(2, 3)
        assertEquals(5.0, m.at(1, 2))
        assertEquals(3, m.reshape(3, -1).size(0))
        assertEquals(2, m.reshape(3, -1).size(1))
        val t = m.T
        assertEquals(3, t.shape[0])
        assertEquals(m.at(1, 2), t.at(2, 1))
        assertEquals(1, m.unsqueeze(0).shape[0])
        assertEquals(4, m.unsqueeze(-1).ndim + 1)
        assertEquals(2, m.unsqueeze(0).squeeze().ndim)
        assertEquals(6, m.flatten().shape[0])
        val p = arange(24).to(DType.Float64).view(2, 3, 4).permute(2, 0, 1)
        assertEquals(4, p.shape[0]); assertEquals(2, p.shape[1]); assertEquals(3, p.shape[2])
        assertEquals(23.0, p.at(3, 1, 2))
        assertEquals(13.0, p.at(1, 1, 0))
        // slicing
        assertEquals("tensor([3., 4., 5.])", m.row(1).toString())
        assertEquals("tensor([1., 4.])", m.col(1).toString())
        assertEquals("tensor([[1., 2.],\n        [4., 5.]])", m.slice(1, 1).toString())
        assertEquals("tensor([0., 2.])", a.slice(0, 0, 4, 2).toString())
        assertEquals("tensor([[3., 4., 5.]])", m.narrow(0, 1, 1).toString())
        assertEquals(4.0, m[1, 1].item())
        assertEquals(5.0, m[1][2].item())
        m[0, 0] = 9.0
        assertEquals(9.0, a.at(0))
        // cat / stack / indexSelect
        val c = cat(m, m, dim = 0)
        assertEquals(4, c.shape[0])
        assertEquals(6, cat(m, m, dim = 1).shape[1])
        val s = stack(a, a)
        assertEquals(2, s.shape[0]); assertEquals(6, s.shape[1])
        assertEquals(3, stack(a, a, dim = 1).shape[0] - 3)
        val picked = m.indexSelect(0, tensor(1.0, 0.0).to(DType.Int64))
        assertEquals(3.0, picked.at(0, 0))
        assertEquals(9.0, picked.at(1, 0))
    }

    @Test
    fun dtypes() {
        val f = tensor(0.1, dtype = DType.Float32)
        val d = tensor(0.1, dtype = DType.Float64)
        assertTrue(f.item() != 0.1)
        assertEquals(0.1, d.item())
        assertEquals(DType.Float64, (f + d).dtype)
        assertEquals(DType.Float32, (f + arange(1)).dtype)
        assertEquals(DType.Float32, (arange(3) / arange(3)).dtype)
        assertEquals(DType.Int64, (arange(3) + arange(3)).dtype)
        assertEquals(2.0, f.to(DType.Float64).long().double().item() + 2.0)
    }

    @Test
    fun randomIsDeterministic() {
        val a = rand(3, generator = Generator(42))
        val b = rand(3, generator = Generator(42))
        assertTrue(a.allClose(b, 0.0, 0.0))
        val n = randn(1000, generator = Generator(1), dtype = DType.Float64)
        assertTrue(kotlin.math.abs(n.mean().item()) < 0.15)
        assertTrue(kotlin.math.abs(n.std().item() - 1.0) < 0.1)
        val u = rand(100, generator = Generator(7))
        assertTrue(u.min().item() >= 0.0 && u.max().item() < 1.0)
        manualSeed(5)
        val x = rand(2)
        manualSeed(5)
        assertTrue(x.allClose(rand(2), 0.0, 0.0))
        assertEquals(6, randperm(6, Generator(3)).sum().item().toInt() - 9)
    }

    @Test
    fun printingLarge() {
        val t = arange(0.0, 2000.0, 1.0, dtype = DType.Float64)
        val s = t.toString()
        assertTrue(s.contains("..."), s)
        assertTrue(s.startsWith("tensor([   0.,    1.,    2., ..., 1997., 1998., 1999.]"), s)
        assertEquals("tensor(2.5000)", scalarTensor(2.5).toString())
        assertEquals("tensor([], dtype=float64)", zeros(0, dtype = DType.Float64).toString())
        val f = tensor(0.5, 123.25)
        assertEquals("tensor([  0.5000, 123.2500])", f.toString())
        assertEquals("tensor([nan, inf])", tensor(Double.NaN, Double.POSITIVE_INFINITY).toString())
        val three = ones(2, 2, 2).toString()
        assertEquals("tensor([[[1., 1.],\n         [1., 1.]],\n\n        [[1., 1.],\n         [1., 1.]]])", three)
    }
}
