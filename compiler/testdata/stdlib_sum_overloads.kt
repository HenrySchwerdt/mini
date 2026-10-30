fun main() {
    println(listOf(1, 2, 3).sum())
    println(listOf(1.5, 2.5).sum())
    println(listOf(10L, 20L).sum())
    println(listOf(1, 2).sumOf { it * 3 })
}
