import kotlin.test.*

class SequencesTest {
    @Test
    fun generators() {
        assertEquals(listOf(1, 2, 4, 8, 16), generateSequence(1) { it * 2 }.take(5).toList())
        assertEquals(listOf(1, 2, 3, 4, 5), generateSequence(1) { if (it < 5) it + 1 else null }.toList())
        var n = 0
        assertEquals(listOf(1, 2, 3), generateSequence { if (n < 3) ++n else null }.toList())
        assertEquals(listOf(7, 8), sequenceOf(7, 8).toList())
        assertEquals(0, emptySequence<Int>().count())
    }

    @Test
    fun laziness() {
        val log = ArrayList<Int>()
        val s = sequenceOf(1, 2, 3, 4).onEach { log.add(it) }.map { it * 2 }.filter { it > 2 }
        assertEquals(0, log.size)
        assertEquals(4, s.first())
        assertEquals(listOf(1, 2), log)
        assertEquals(listOf(4, 6), s.take(2).toList())
    }

    @Test
    fun operators() {
        val seq = (1..20).asSequence().filter { it % 2 == 0 }.map { it * it }
        assertEquals(listOf(4, 16, 36), seq.take(3).toList())
        assertEquals(1540, seq.sum())
        assertEquals("1-2-3", sequenceOf(3, 1, 2).sorted().joinToString("-"))
        assertEquals(listOf('a', 'b', 'b'), listOf("a", "bb").asSequence().flatMap { it.toList().asSequence() }.toList())
        assertEquals(listOf(1, 2, 3), sequenceOf(listOf(1), listOf(2, 3)).flatten().toList())
        assertEquals(listOf(listOf(1, 2), listOf(2, 3)), sequenceOf(1, 2, 3).windowed(2).toList())
        assertEquals(listOf(Pair(1, "a"), Pair(2, "b")), sequenceOf(1, 2, 3).zip(sequenceOf("a", "b")).toList())
        assertEquals(listOf(1, 2), sequenceOf(1, 1, 2).distinct().toList())
        assertEquals(listOf(1, 2, 3, 4), sequenceOf(1, 2, 3, 4).takeWhile { it < 3 }.toList() + sequenceOf(1, 2, 3, 4).dropWhile { it < 3 }.toList())
        assertEquals(listOf(IndexedValue(0, 1), IndexedValue(1, 2)), sequenceOf(1, 2).withIndex().toList())
        assertEquals(listOf(2, 3), sequenceOf(1, 2, 3).drop(1).toList())
        assertEquals(4.0, sequenceOf(1.5, 2.5).sum())
        assertEquals(3, sequenceOf(1, 2, 3).max())
        assertEquals(mapOf(1 to listOf(1, 3), 0 to listOf(2)), sequenceOf(1, 2, 3).groupBy { it % 2 })
        assertEquals(listOf(3), mapOf(1 to 2).asSequence().map { it.key + it.value }.toList())
        assertEquals(6, sequenceOf(1, 2, 3).fold(0) { a, b -> a + b })
        assertTrue(sequenceOf(1, 2).any { it > 1 })
        assertTrue(sequenceOf(1, 2).none { it > 5 })
        assertEquals(listOf(1, 3, 6), sequenceOf(1, 2, 3).runningFold(0) { a, b -> a + b }.drop(1).toList())
        var sum = 0
        for (x in sequenceOf(7, 8)) sum += x
        assertEquals(15, sum)
    }

    @Test
    fun stringSequences() {
        assertEquals(listOf("a", "b"), "a b".splitToSequence(" ").toList())
        assertEquals("heo", "hello".asSequence().filter { it != 'l' }.joinToString(""))
        assertEquals(2, "x\ny".lineSequence().count())
    }
}
