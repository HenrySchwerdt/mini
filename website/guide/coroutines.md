# Coroutines and sequences

Mini compiles Kotlin `suspend` functions to state machines, in both backends and in the WebAssembly build. On top of that the
standard library has **sequence builders** (`sequence { }`, `iterator { }`), and a small library, `mini.coroutines`, gives you
`launch`, `async`, `delay`, channels and flows on native threads and an event loop. No libuv, no JVM: the thread layer is a few
hundred lines of C in the runtime. The sample below is compiled and run by the documentation checks.

<<< @/samples/guide/sequences.kt{kotlin}

<<< @/samples/guide/sequences.out{text}

The block of `sequence { }` is a coroutine: `yield` suspends it and hands a value to whoever is iterating, so the infinite
Fibonacci sequence above computes exactly as many numbers as `take(10)` asks for. `yieldAll` takes a collection, an iterator or
another sequence. Operators such as `map`, `filter`, `take`, `drop`, `takeWhile`, `flatMap`, `toList` and `joinToString` are
available; `sequence<Int> { ... }` needs its type argument whenever the expected type is not known (Mini has no builder
inference).

## Suspend functions

`suspend fun` and `suspend` lambdas work as in Kotlin: a suspend function can call other suspend functions, loops, `try`/`finally`,
labeled `break`/`continue` and `return` may contain suspension points, suspend lambdas can be stored in `suspend () -> T` values,
and suspend methods can be overridden and have default bodies in interfaces. Calling a suspend function from a function that
is not `suspend` is a compile error:

```
error: Suspend function 'work' should be called only from a coroutine or another suspend function
```

The low-level API is `kotlin.coroutines`: `Continuation`, `CoroutineContext`, `suspendCoroutine`, `createCoroutine`,
`startCoroutine`. How the compiler does it (continuation passing, one state-machine lambda per function, handler and finally
blocks, why there are no reference cycles) is described in [`docs/coroutines.md`](https://github.com/HenrySchwerdt/mini/blob/main/docs/coroutines.md).

## `mini.coroutines` (native)

```kotlin
import mini.coroutines.*

suspend fun fetch(name: String, millis: Long): String {
    delay(millis)
    return "result of " + name
}

fun main() {
    val text = runBlocking {
        val a = async { fetch("a", 30) }
        val b = async { fetch("b", 10) }
        launch {
            delay(20)
            println("a job in between")
        }
        println("started")
        a.await() + " and " + b.await()
    }
    println(text)
}
```

```text
started
a job in between
result of a and result of b
```

What is there: `runBlocking`, `launch`, `async`/`await`, `coroutineScope`, `supervisorScope`, `withContext`, `withTimeout`, `delay`,
`yield`, `Job` with cancellation (cooperative, at every suspension point of the library) and structured concurrency (a failing
child cancels its parent and siblings; a scope waits for its children), `Mutex`, `Channel` (rendezvous, buffered, unlimited) and
`Flow` basics (`flow { }`, `collect`, `map`, `filter`, `take`, `toList`).

`runBlocking` runs an event loop on the calling thread: timers and wake-ups, no busy waiting. `Dispatchers.Default` and
`Dispatchers.IO` are pools of native threads: `launch(Dispatchers.Default) { ... }`, `withContext(Dispatchers.IO) { ... }`. The first
thread you start switches the reference counts of the whole program to atomic operations, so continuations, jobs and channels can
cross threads; data that two threads write still needs a `Lock` (`mini.concurrent`) or an atomic (`AtomicInt`, `AtomicLong`,
`AtomicReference`). Plain threads exist too: `thread { }` and `java.lang.Thread`.

The coroutine library is native only: the browser build has no threads and no clock, so `sequence { }` works there, `runBlocking`
and `delay` do not. Sockets, files and processes on the event loop (an epoll/kqueue/Windows poller behind the dispatcher) are
planned; v1 is timers and wake-ups.

## Limits

- A suspension point inside a lambda that is passed to an `inline` function (`forEach { delay(1) }`, `repeat`) is rejected; use a
  `for` loop. A `for` over an array with a suspension point in its body is rejected too (iterate a list or the indices).
- No builder inference, no `suspend` properties; `currentCoroutineContext()` replaces the `coroutineContext` property.
- No `select`, `actor`, `StateFlow`/`SharedFlow`, `Dispatchers.Main`, `Semaphore`.
