// references to the constructors of the array classes and of fun interfaces

fun interface KSupplier<T> {
    fun get(): T
}

fun interface KRunnable {
    fun run()
}

val ks: (() -> String) -> KSupplier<String> = ::KSupplier

fun twice(b: (Int, (Int) -> String) -> Array<String>): List<String> = b(2) { "v$it" }.toList()

fun <T> upcast(value: T): T = value

fun main() {
    println(ks { "OK" }.get())
    val kr: (() -> Unit) -> KRunnable = ::KRunnable
    kr { println("ran") }.run()
    println(twice(::Array))
    println(upcast<(Int) -> IntArray>(::IntArray)(3).toList())
    println(upcast<(Int) -> BooleanArray>(::BooleanArray)(2).toList())
    println(upcast<(Int) -> DoubleArray>(::DoubleArray)(1).toList())
    println(upcast<(Int) -> ByteArray>(::ByteArray)(2).size)
    val mk: (Int, (Int) -> Int) -> IntArray = ::IntArray
    println(mk(4) { it * it }.toList())
}
