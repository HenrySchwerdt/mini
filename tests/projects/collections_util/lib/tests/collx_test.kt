import collx.*
import kotlin.test.*

class CollxTest {
    @Test
    fun multimapKeepsOrder() {
        val m = multimapOf("a" to 1, "b" to 2, "a" to 3)
        assertEquals(listOf(1, 3), m["a"])
        assertEquals(3, m.size)
        assertTrue(m.remove("a", 1))
        assertFalse(m.remove("zz", 1))
    }

    @Test
    fun pairwiseAndSplit() {
        assertEquals(listOf(1 to 2, 2 to 3), listOf(1, 2, 3).pairwise())
        assertEquals(listOf(listOf(1, 2), listOf(3)), listOf(1, 2, 0, 3).splitBy { it == 0 })
    }

    @Test
    fun duplicatesAndTypes() {
        assertEquals(listOf(2, 3), listOf(1, 2, 3, 2, 3, 3).duplicates())
        assertEquals("x", listOf(1, "x", 2.0).firstOfType<String>())
    }
}
