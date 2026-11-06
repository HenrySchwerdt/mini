fun interface KRunnable {
    fun invoke()
}

fun interface Mapper {
    fun map(s: String): Int
}

typealias KRunnableAlias = KRunnable

typealias MapperAlias = Mapper

fun foo(f: KRunnable) = f

fun main() {
    var result = ""
    foo(KRunnableAlias {
        result += "K"
    }).invoke()
    result += MapperAlias { it.length }.map("abc")
    println(result)
}
