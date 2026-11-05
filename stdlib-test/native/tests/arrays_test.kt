import kotlin.test.*

class ArraysTest {
    @Test
    fun copyOfAndRange() {
        val a = intArrayOf(3, 1, 2)
        assertContentEquals(intArrayOf(3, 1, 2), a.copyOf())
        assertContentEquals(intArrayOf(3, 1, 2, 0, 0), a.copyOf(5))
        assertContentEquals(intArrayOf(3, 1), a.copyOf(2))
        assertContentEquals(intArrayOf(1, 2), a.copyOfRange(1, 3))
        assertFailsWith<IllegalArgumentException> { a.copyOfRange(2, 1) }
    }

    @Test
    fun fillSortReverse() {
        val a = intArrayOf(3, 1, 2)
        a.sort()
        assertEquals("[1, 2, 3]", a.contentToString())
        a.reverse()
        assertEquals("[3, 2, 1]", a.contentToString())
        a.sortDescending()
        assertEquals("[3, 2, 1]", a.contentToString())
        a.fill(7)
        assertEquals("[7, 7, 7]", a.contentToString())
        val b = intArrayOf(5, 4, 3, 2, 1)
        b.sort(1, 4)
        assertEquals("[5, 2, 3, 4, 1]", b.contentToString())
        assertEquals(listOf(1, 2, 3, 4, 5), b.sorted())
        assertEquals(listOf(5, 4, 3, 2, 1), b.sortedDescending())
        assertEquals("[1, 2, 3, 4, 5]", b.sortedArray().contentToString())
    }

    @Test
    fun contentEqualsAndHash() {
        assertTrue(intArrayOf(1, 2).contentEquals(intArrayOf(1, 2)))
        assertFalse(intArrayOf(1, 2).contentEquals(intArrayOf(2, 1)))
        assertEquals(listOf(1, 2).hashCode(), intArrayOf(1, 2).contentHashCode())
        assertTrue(arrayOf("a", "b").contentEquals(arrayOf("a", "b")))
    }

    @Test
    fun aggregates() {
        val a = intArrayOf(3, 1, 2)
        assertEquals(6, a.sum())
        assertEquals(3, a.max())
        assertEquals(1, a.min())
        assertEquals(2.0, a.average())
        assertEquals(12, a.sumOf { it * 2 })
        assertEquals(1, a.indexOf(1))
        assertEquals(-1, a.indexOf(9))
        assertTrue(a.contains(2))
        assertEquals(2, a.count { it > 1 })
        assertEquals(3, a.first())
        assertEquals(2, a.last())
        assertNull(a.getOrNull(5))
        assertEquals(listOf(6, 2, 4), a.map { it * 2 })
        assertEquals(listOf(3, 2), a.filter { it > 1 })
        assertEquals(listOf(2, 1, 3), a.reversed())
        assertEquals(mapOf(3 to 9, 1 to 1, 2 to 4), a.associateWith { it * it })
        assertEquals(listOf(Pair(3, 1), Pair(1, 2)), a.zip(intArrayOf(1, 2)))
        assertEquals("3-1-2", a.joinToString("-"))
        assertEquals(1.5, doubleArrayOf(1.0, 2.0).average() )
        assertEquals(2.5, doubleArrayOf(1.0, 2.5, 4.0).max() - 1.5)
        assertEquals('c', charArrayOf('a', 'c', 'b').max())
    }

    @Test
    fun plusAndCopyInto() {
        val a = intArrayOf(1, 2)
        assertEquals("[1, 2, 3]", (a + 3).contentToString())
        assertEquals("[1, 2, 3, 4]", (a + intArrayOf(3, 4)).contentToString())
        val d = IntArray(4)
        a.copyInto(d, 1)
        assertEquals("[0, 1, 2, 0]", d.contentToString())
    }

    @Test
    fun objectArrays() {
        val a = arrayOf("b", "c", "a")
        assertEquals(listOf("a", "b", "c"), a.sorted())
        a.sort()
        assertEquals("[a, b, c]", a.contentToString())
        a.sortDescending()
        assertEquals("[c, b, a]", a.contentToString())
        a.sortBy { it }
        assertEquals("[a, b, c]", a.contentToString())
        a.sortWith(compareByDescending { it })
        assertEquals("[c, b, a]", a.contentToString())
        assertEquals("[b, a]", a.copyOfRange(1, 3).contentToString())
        assertEquals(listOf("c", "b", "a"), a.toList())
        assertEquals("[a, b, c]", a.reversedArray().contentToString())
        assertEquals(1, a.indexOf("b"))
        assertEquals(listOf("C", "B", "A"), a.map { it.uppercase() })
        assertEquals(3, a.maxOf { it.length } + 2)
        a.fill("z")
        assertEquals("[z, z, z]", a.contentToString())
        assertEquals(4, (a + "y").size)
        assertEquals(listOf(1, 2), listOf(1, 2).toTypedArray().toList())
        assertEquals("[1, 2]", listOf(1, 2).toIntArray().contentToString())
    }
}
