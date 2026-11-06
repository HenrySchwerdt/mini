fun interface KRunnable {
    fun invoke()
}

fun interface KConsumer<T> {
    fun accept(value: T)
}

typealias KRunnableAlias = KRunnable

typealias StringConsumer = KConsumer<String>

fun foo(f: KRunnable) = f

fun main() {
    var result = ""
    foo(KRunnableAlias {
        StringConsumer {
            result += it
        }.accept("K")
    }).invoke()
    StringConsumer { result += it.length }.accept("abc")
    println(result)
}
