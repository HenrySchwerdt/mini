import kotlin.test.*
import mini.tensor.*

class BasicsTest {
    @Test
    fun factoriesAndShape() {
        val z = zeros(2, 3)
        assertEquals(listOf(2, 3), listOf(z.shape[0], z.shape[1]))
        assertEquals(6, z.numel)
        assertEquals(2, z.ndim)
        assertEquals(3, z.size(-1))
        assertEquals(3, z.strides[0])
        val e = eye(3)
        assertEquals(1.0, e.at(1, 1))
        assertEquals(0.0, e.at(1, 2))
        val a = arange(5)
        assertEquals(DType.Int64, a.dtype)
        assertEquals(4.0, a.at(4))
        val l = linspace(0.0, 1.0, 5)
        assertEquals(0.25, l.at(1))
        val s = scalarTensor(2.5)
        assertEquals(0, s.ndim)
        assertEquals(2.5, s.item())
    }

    @Test
    fun printing() {
        val t = tensor(doubleArrayOf(1.0, 2.0), doubleArrayOf(3.0, 4.0))
        assertEquals("tensor([[1., 2.],\n        [3., 4.]])", t.toString())
        val f = tensor(1.5, 2.25, -3.0)
        assertEquals("tensor([ 1.5000,  2.2500, -3.0000])", f.toString())
        assertEquals("tensor([0, 1, 2])", arange(3).toString())
    }
}
