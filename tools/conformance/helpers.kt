// Minimal port of the Kotlin test suite's coroutine helpers (`import helpers.*`), added to tests that use them.
package helpers

import kotlin.coroutines.*

fun <T> handleResultContinuation(x: (T) -> Unit): Continuation<T> = object : Continuation<T> {
    override val context: CoroutineContext = EmptyCoroutineContext
    override fun resumeWith(result: Result<T>) {
        x(result.getOrThrow())
    }
}

fun handleExceptionContinuation(x: (Throwable) -> Unit): Continuation<Any?> = object : Continuation<Any?> {
    override val context: CoroutineContext = EmptyCoroutineContext
    override fun resumeWith(result: Result<Any?>) {
        val e = result.exceptionOrNull()
        if (e != null) x(e)
    }
}

open class EmptyContinuation(override val context: CoroutineContext) : Continuation<Any?> {
    companion object : EmptyContinuation(EmptyCoroutineContext)

    override fun resumeWith(result: Result<Any?>) {
        result.getOrThrow()
    }
}

class ResultContinuation : Continuation<Any?> {
    override val context: CoroutineContext = EmptyCoroutineContext
    var result: Any? = null
    override fun resumeWith(result: Result<Any?>) {
        this.result = result.getOrThrow()
    }
}

abstract class ContinuationAdapter<T> : Continuation<T> {
    override val context: CoroutineContext = EmptyCoroutineContext
    override fun resumeWith(result: Result<T>) {
        if (result.isSuccess) resume(result.getOrThrow()) else resumeWithException(result.exceptionOrNull()!!)
    }

    abstract fun resumeWithException(exception: Throwable)
    abstract fun resume(value: T)
}

class StateMachineCheckerClass {
    private var counter = 0
    var finished = false

    var proceed: () -> Unit = {}

    fun reset() {
        counter = 0
        finished = false
        proceed = {}
    }

    suspend fun suspendHere() = kotlin.coroutines.suspendCoroutine<Unit> { c ->
        counter++
        proceed = { c.resume(Unit) }
    }

    fun check(numberOfSuspensions: Int, checkFinished: Boolean = true) {
        for (i in 1..numberOfSuspensions) {
            if (counter != i) error("Wrong state-machine generated: suspendHere called should be called exactly once in one state. Expected " + i + ", got " + counter)
            proceed()
        }
        if (counter != numberOfSuspensions)
            error("Wrong state-machine generated: wrong number of overall suspensions. Expected " + numberOfSuspensions + ", got " + counter)
        if (finished) error("Wrong state-machine generated: it is finished early")
        proceed()
        if (checkFinished && !finished) error("Wrong state-machine generated: it is not finished yet")
    }
}

val StateMachineChecker = StateMachineCheckerClass()

object CheckStateMachineContinuation : ContinuationAdapter<Unit>() {
    override fun resume(value: Unit) {
        StateMachineChecker.proceed = { StateMachineChecker.finished = true }
    }

    override fun resumeWithException(exception: Throwable) {
        throw exception
    }
}
