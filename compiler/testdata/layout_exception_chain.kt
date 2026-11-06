// User exception classes: the runtime-defined Throwable part (header, message, cause) is a fixed prefix, the own fields of the
// user classes come after it in the reordered layout. Message, cause and own fields must all survive throw/catch.
open class AppError(message: String, val code: Int, val fatal: Boolean, cause: Throwable? = null) : Exception(message, cause) {
    var attempts: Long = 3L
}

open class NetError(message: String, code: Int, val host: String, val retry: Boolean, val port: Short) :
    AppError(message, code, false) {
    var bytes: Long = -1L
}

class TimeoutError(host: String, val millis: Long, val soft: Boolean, val note: String?) :
    NetError("timeout after $millis ms", 408, host, true, 8080.toShort()) {
    var tries: Int = 2
}

class WrappedError(inner: Throwable, val level: Byte) : AppError("wrapped", 500, true, inner)

fun fail(kind: Int): Nothing = when (kind) {
    0 -> throw AppError("plain", 1, true)
    1 -> throw NetError("net", 2, "example.org", true, 443)
    2 -> throw TimeoutError("slow.example.org", 1L shl 35, true, "n/a")
    else -> throw WrappedError(TimeoutError("deep.example.org", 5L, false, null), 3)
}

fun main() {
    for (k in 0..3) {
        try {
            fail(k)
        } catch (e: TimeoutError) {
            println("timeout ${e.message} code=${e.code} fatal=${e.fatal} attempts=${e.attempts} host=${e.host} retry=${e.retry} port=${e.port} bytes=${e.bytes} millis=${e.millis} soft=${e.soft} note=${e.note} tries=${e.tries}")
        } catch (e: NetError) {
            println("net ${e.message} code=${e.code} fatal=${e.fatal} attempts=${e.attempts} host=${e.host} retry=${e.retry} port=${e.port} bytes=${e.bytes}")
        } catch (e: WrappedError) {
            val c = e.cause as TimeoutError
            println("wrapped ${e.message} code=${e.code} fatal=${e.fatal} level=${e.level} cause=${c.message} millis=${c.millis} soft=${c.soft} note=${c.note} host=${c.host}")
        } catch (e: AppError) {
            println("app ${e.message} code=${e.code} fatal=${e.fatal} attempts=${e.attempts}")
        }
    }
    val e = TimeoutError("h", 9L, false, "n")
    e.tries += 40
    e.bytes = 123456789012L
    e.attempts++
    println("${e.tries} ${e.bytes} ${e.attempts} ${e.code} ${e.message} ${e.port} ${e.millis}")
    val t: Throwable = e
    println(t is AppError && t is NetError)
}
