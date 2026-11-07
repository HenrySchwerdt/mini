// a parameter of type `Recv.(T) -> Unit` is closer than a member extension of the same name: `encode(value)` inside the member extension calls the parameter
interface WriteContext {
    val x: String
}

interface ReadContext {
    val y: String
}

interface Codec<T> {
    fun WriteContext.encode(value: T)
    fun ReadContext.decode(): T?
}

fun <T> codec(
    encode: WriteContext.(T) -> Unit,
    decode: ReadContext.() -> T?,
): Codec<T> = object : Codec<T> {
    override fun WriteContext.encode(value: T) = encode(value)
    override fun ReadContext.decode(): T? = decode()
}

fun main() {
    var result = ""
    val t = codec<String>(
        { result += x + it },
        { y },
    )
    with(t) {
        object : WriteContext {
            override val x: String
                get() = "O"
        }.encode("K")
        result += object : ReadContext {
            override val y: String
                get() = "123"
        }.decode()
    }
    println(result)
}
