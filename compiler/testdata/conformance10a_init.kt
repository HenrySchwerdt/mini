// A failing object initialiser: ExceptionInInitializerError for an Exception, an Error passes as it is, a later access
// raises NoClassDefFoundError (conformance round 10a).

object Failing {
    val value: String = compute()
    fun compute(): String = throw IllegalStateException("1")
}

object FailingError {
    val value: String = throw Error("2")
}

object Fine {
    val value = "fine"
}

class Holder {
    companion object {
        val x: Int = failingInt()
        fun failingInt(): Int = throw IllegalArgumentException("c")
    }
}

fun main() {
    try {
        println(Failing.value)
    } catch (t: Error) {
        println(t::class.simpleName + " cause=" + (t.cause as? IllegalStateException)?.message)
    }
    try {
        println(Failing.value)
    } catch (t: Error) {
        println(t::class.simpleName)
    }
    try {
        println(FailingError.value)
    } catch (t: Error) {
        println(t::class.simpleName + " " + t.message + " " + (t.cause == null))
    }
    try {
        println(Holder.x)
    } catch (t: Throwable) {
        println(t::class.simpleName + " " + t.cause?.message)
    }
    println(Fine.value)
    println(Fine.value)
}
