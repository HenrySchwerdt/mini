import kotlin.coroutines.*

class Ctx(val name: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<Ctx>
}

// a user-defined suspend property
suspend val myName: String
    get() = coroutineContext[Ctx.Key]!!.name

suspend fun viaFun(): String = coroutineContext[Ctx.Key]!!.name + "!"

fun main() {
    var r = ""
    val c = object : Continuation<Unit> {
        override val context: CoroutineContext = Ctx("top")
        override fun resumeWith(result: Result<Unit>) {}
    }
    suspend {
        r = myName + " " + viaFun() + " " + (coroutineContext === c.context)
    }.startCoroutine(c)
    println(r)
}
