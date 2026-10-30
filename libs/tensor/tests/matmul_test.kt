import kotlin.test.*
import mini.tensor.*

class MatmulTest {
    @Test
    fun matrixMatrix() {
        val a = tensor(doubleArrayOf(1.0, 2.0, 3.0), doubleArrayOf(4.0, 5.0, 6.0))
        val b = tensor(doubleArrayOf(7.0, 8.0), doubleArrayOf(9.0, 10.0), doubleArrayOf(11.0, 12.0))
        val c = a @ b
        assertEquals(2, c.shape[0]); assertEquals(2, c.shape[1])
        assertEquals("tensor([[ 58.,  64.],\n        [139., 154.]])", c.toString())
        assertEquals(c.toString(), a.mm(b).toString())
        assertEquals(c.toString(), a.matmul(b).toString())
    }

    @Test
    fun vectors() {
        val a = tensor(doubleArrayOf(1.0, 2.0), doubleArrayOf(3.0, 4.0))
        val v = tensor(5.0, 6.0)
        assertEquals("tensor([17., 39.])", (a @ v).toString())
        assertEquals("tensor([23., 34.])", (v @ a).toString())
        val d = v @ v
        assertEquals(0, d.ndim)
        assertEquals(61.0, d.item())
        assertEquals(61.0, v.dot(v).item())
        assertEquals("tensor([17., 39.])", a.mv(v).toString())
        assertEquals("tensor([[ 5.,  6.],\n        [10., 12.]])", tensor(1.0, 2.0).outer(v).toString())
    }

    @Test
    fun batched() {
        val a = arange(12).to(DType.Float64).view(2, 2, 3)
        val b = arange(12).to(DType.Float64).view(2, 3, 2)
        val c = a @ b
        assertEquals(3, c.ndim)
        assertEquals(2, c.shape[0]); assertEquals(2, c.shape[1]); assertEquals(2, c.shape[2])
        assertEquals(10.0, c.at(0, 0, 0))   // 0*0 + 1*2 + 2*4
        assertEquals(274.0, c.at(1, 1, 1))  // 9*7 + 10*9 + 11*11
        val m = tensor(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0))
        val r = a @ eye(3, dtype = DType.Float64)
        assertTrue(r.allClose(a))
        val bm = a @ b.view(6, 2).slice(0, 0, 3)
        assertEquals(2, bm.shape[0]); assertEquals(2, bm.shape[1]); assertEquals(2, bm.shape[2])
        val l = m @ a.permute(0, 2, 1)
        assertEquals(3, l.ndim)
        assertEquals(2, l.shape[0]); assertEquals(2, l.shape[1]); assertEquals(2, l.shape[2])
        assertEquals(a.at(1, 1, 0), l.at(1, 0, 1))
        assertTrue(a.bmm(b).allClose(c))
    }

    @Test
    fun matrixPowerAndSolve() {
        val f = tensor(doubleArrayOf(1.0, 1.0), doubleArrayOf(1.0, 0.0), dtype = DType.Float64)
        assertEquals(55.0, f.matrixPower(10).at(0, 1))
        assertEquals("tensor([[1., 0.],\n        [0., 1.]], dtype=float64)", f.matrixPower(0).toString())
        var p = f
        p @= f
        assertEquals(2.0, p.at(0, 0))
        val a = tensor(doubleArrayOf(2.0, 1.0), doubleArrayOf(1.0, 3.0), dtype = DType.Float64)
        val x = solve(a, tensor(3.0, 5.0, dtype = DType.Float64))
        assertTrue(x.allClose(tensor(0.8, 1.4, dtype = DType.Float64)))
        assertEquals(5.0, a.trace().item())
    }

    @Test
    fun largeMatchesNaive() {
        val g = Generator(11)
        val a = randn(37, 53, generator = g, dtype = DType.Float64)
        val b = randn(53, 29, generator = g, dtype = DType.Float64)
        val fast = a @ b
        Blas.enabled = false
        val slow = a @ b
        Blas.enabled = true
        val ref = zeros(37, 29, dtype = DType.Float64)
        for (i in 0 until 37) for (j in 0 until 29) {
            var s = 0.0
            for (k in 0 until 53) s += a.at(i, k) * b.at(k, j)
            ref[i, j] = s
        }
        assertTrue(fast.allClose(ref, 1e-12, 1e-12))
        assertTrue(slow.allClose(ref, 1e-12, 1e-12))
    }

    @Test
    fun float32StorageAndProduct() {
        val g = Generator(5)
        val a = randn(37, 53, generator = g)
        val b = randn(53, 29, generator = g)
        assertTrue(a.dtype == DType.Float32)
        assertTrue(a.data.size == 37 * 53)
        // elements are real 32-bit floats
        for (i in 0 until a.data.size) assertTrue(a.data[i] == a.data[i].toFloat().toDouble())
        val fast = a @ b
        Blas.enabled = false
        val slow = a @ b
        Blas.enabled = true
        val ref = zeros(37, 29, dtype = DType.Float64)
        for (i in 0 until 37) for (j in 0 until 29) {
            var s = 0.0
            for (k in 0 until 53) s += a.at(i, k) * b.at(k, j)
            ref[i, j] = s
        }
        assertTrue(fast.dtype == DType.Float32)
        assertTrue(fast.allClose(ref, 1e-4, 1e-4))
        assertTrue(slow.allClose(ref, 1e-4, 1e-4))
        val v = randn(53, generator = g)
        assertTrue((a @ v).allClose(a.double() @ v.double(), 1e-4, 1e-4))
    }

    @Test
    fun shapeMismatch() {
        val e = assertFailsWith<IllegalArgumentException> { zeros(2, 3) @ zeros(4, 5) }
        assertTrue(e.message!!.contains("[2, 3] @ [4, 5]"), e.message)
        assertTrue(e.message!!.contains("inner dimensions 3 and 4"), e.message)
        assertFailsWith<IllegalArgumentException> { zeros(3) @ zeros(4) }
        assertFailsWith<IllegalArgumentException> { zeros(2, 3).mm(zeros(3)) }
        assertFailsWith<IllegalArgumentException> { zeros(2, 2, 3) @ zeros(3, 3, 2) }
        assertFailsWith<IllegalArgumentException> { scalarTensor(1.0) @ zeros(2) }
    }
}
