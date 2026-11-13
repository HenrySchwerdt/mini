enum class Op(val sym: Char) : (Int, Int) -> Int {
    ADD('+') { override fun invoke(a: Int, b: Int) = a + b },
    MUL('*') { override fun invoke(a: Int, b: Int) = a * b };
}

enum class Plain(val v: Int) : () -> String {
    ONE(1), TWO(2);
    override fun invoke() = "p$v"
}

fun main() {
    println(Op.entries.map { "${it.sym}${it(3, 4)}" })
    println(Plain.TWO())
    val f: (Int, Int) -> Int = Op.MUL
    println(f(5, 6))
    println(listOf(1, 2, 3).fold(1) { acc, x -> Op.ADD(acc, x) })
}
