# Coroutines, threads and `mini.coroutines`

Mini compiles `suspend` functions and lambdas with CPS and a state machine (the same technique as Kotlin/JVM and Kotlin/Native),
ships `kotlin.coroutines` and `kotlin.sequences` in the standard library, native threads, and a small kotlinx.coroutines-like
library, `mini.coroutines`. There is **no libuv** and no dependency beyond libc and the platform's thread API.

## 1. Language: how `suspend` is lowered

The lowering is `compiler/lower/src/SuspendLowering.kt`, called from `Ast2Ir` (so both backends, C and LLVM, and the wasm build
see ordinary IR).

* **Signature.** `suspend fun f(a: A): R` becomes `fun f(a: A, $completion: Continuation<Any?>): Any?`. The result is the value
  of the function or the marker `COROUTINE_SUSPENDED` (an instance of `SuspendMarker`). A call of a suspend function gets the
  continuation of the caller as its last argument. `suspend` function types `suspend (A) -> R` are the `Function(n+1)` interface;
  a call is `invoke(a, completion)`. `fun interface` SAM conversion of suspend methods works the same way.
* **Without suspension points** a suspend function is an ordinary function that returns its value (no allocation).
* **With suspension points** the body becomes `return CoroutineImpl($completion, step).start()`. `step` is a lambda
  `(self, result) -> Any?` that holds the state machine:

  ```
  while (true) { try { when (label) { 0 -> ..., 1 -> ..., ... } } catch (t: Throwable) { if (handler < 0) throw t; exc = t; label = handler } }
  ```

  Every local variable of the function (and every temporary) is hoisted into the function body, so the lambda captures it in
  a heap cell: that is what keeps it alive across a suspension. A suspension point sets `label`, calls the function and returns
  the marker when it suspended. The resume block `k` reads the value, or throws the failure, from the result that `resumeWith`
  passes to `step`. Structured control flow that contains a suspension point (if/when, while/do-while/for, labeled
  break/continue, return, try/catch/finally) is flattened into blocks; everything without one is left as it is.
  try/catch uses a `handler` variable: the block the machine jumps to when an exception comes out of a block. `finally` is one
  shared block that is entered with a return address, so it also runs for `return`, `break` and `continue`.
* **Resuming.** `BaseContinuation.resumeRaw` runs a state machine and, when it completes, its parents that are state machines
  too, in a loop (no recursion). The exception ABI of Mini (pending-exception slot) needs nothing else: an exception thrown in a
  block is caught by the machine's `catch`, one that comes out of the machine ends the coroutine and is passed to the parent
  as `Result.failure`.
* **Reference counting.** Continuations, state machines and heap cells are ordinary reference-counted objects. There is no
  cycle collector, so the runtime library is built without cycles: a coroutine never refers to its driver (the `yield` of
  sequence builders hands its value up as the *return value* of the suspension, a `SuspendMarker` subclass), the suspension
  object is checked in a block-local variable and not kept in a heap cell, and handlers drop their function when disposed.
  All coroutine tests run clean under the leak checker (C and LLVM).
* **Sema.** `suspend` function types resolve, lambdas whose expected type is a suspend function type are suspend lambdas, and a
  call of a suspend function outside a suspend function or suspend lambda is an error (`Suspend function 'f' should be called
  only from a coroutine or another suspend function`).

### Limits of the language support

* A suspension point in a lambda that is passed to an `inline` function (`list.forEach { delay(1) }`, `repeat`) is an error
  (the lambda is expanded by `Ir2Mir`, after the state machine is built). `for` loops and `while` loops work.
* `for (x in array)` with a suspension point in the body is rejected (`call to array member 'iterator'`): the loop is flattened,
  so `Ir2Mir` does not see its `FOR_LOOP` shape any more; iterate a list or the indices. Ranges and collections work.
* No builder inference: `sequence { yield(1) }` needs `sequence<Int> { ... }` unless the expected type is known (a declared
  return type, a typed `val`).
* No `suspend` properties, `coroutineContext` is `currentCoroutineContext()`; `Unit` in generic positions is `null`.
* Default argument values of abstract/interface members are not supported by Ast2Ir (`cancel(cause: X? = null)` is an
  overload instead).
* `@RestrictsSuspension` is not enforced.

## 2. Standard library

* `kotlin.coroutines`: `Continuation`, `CoroutineContext` (`Key`, `Element`, `EmptyCoroutineContext`, `plus`, `minusKey`),
  `ContinuationInterceptor`, `createCoroutine`, `startCoroutine`, `createCoroutineUnintercepted`, `suspendCoroutine`,
  `suspendCoroutineUninterceptedOrReturn` (lowered by the compiler to `block(<current continuation>)`),
  `startCoroutineUninterceptedOrReturn`, `currentCoroutineContext`, `Continuation.resume/resumeWithException`,
  `kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED`, `Result<T>` (already there).
* `kotlin.sequences`: `Sequence`, `sequence { }`, `iterator { }` (`yield`, `yieldAll` of values, iterators, iterables and
  sequences), `sequenceOf`, `generateSequence`, `asSequence`, and `map`, `mapIndexed`, `filter`, `filterNot`, `take`, `drop`,
  `takeWhile`, `flatMap`, `toList`, `toSet`, `first`, `firstOrNull`, `count`, `sum`, `joinToString`, `forEach`.
  Sequence builders work everywhere the compiler does, including the wasm build (no threads or timers involved).
* `java.lang.Thread`, `kotlin.concurrent.thread { }`, and in `mini.concurrent`: `Lock` (`withLock`), `Condition`, `AtomicInt`,
  `AtomicLong`, `AtomicReference`, `monotonicNanos`, `currentTimeMillis`, `sleepMillis`, `availableProcessors`.

## 3. Native threads and the memory model

`runtime/src/sys.c` is the whole OS layer: POSIX threads (Linux, macOS), Win32 threads and SRW locks (Windows), no-op stubs on
wasm (single-threaded; `Thread.start` fails there). Locks, condition variables and atomics live in the elements of a Kotlin
`LongArray`/`IntArray`/`Array` (so reference counting frees them); the C functions are bound with `@CSymbol`
(`stdlib/mini/internal/Sys.kt`).

**Reference counts across threads.** Objects normally have a non-atomic count and are marked `MK_FLAG_SHARED` to get an atomic one
(`mk_share`), but nothing can say which objects a closure or a continuation reaches. So the first started thread switches
*every* retain/release of the process to atomic operations (`mk_threads_active`, read by `mk_retain`/`mk_release`), and a program
that can start a thread (`java.lang.Thread.start` is reachable) is compiled without the inline reference-count fast paths (C:
`MK_ATOMIC_RC`, LLVM: `inlineRc` off). Before the first thread nothing changes. What this does *not* make safe is the data
itself: fields that several threads write need a `Lock` or an atomic, as in Kotlin. Kotlin `object` initialisers and top-level
property initialisers run lazily and exactly once, also when threads race on the first access (`mk_object_init`: one global
initialisation lock, the instance is published with a release store after its initialiser ran; a thread that re-enters during its
own initialisation sees the partial object, as on the JVM).

Tests: `runtime/tests/test_sys.c` and `test_threads.c` (ASan/UBSan and TSan via `runtime/run_tests.sh`; they use the platform
threads directly), `compiler/testdata/coroutines_threads.kt` (TSan-clean when built with `-fsanitize=thread`).

## 4. `mini.coroutines`

Package `mini.coroutines` (`stdlib/mini/coroutines`), precompiled with the standard library. The API follows kotlinx.coroutines
for the parts below; semantics that differ are listed after.

| Area | API |
|---|---|
| Builders | `runBlocking`, `launch`, `async`/`Deferred.await`, `coroutineScope`, `supervisorScope`, `withContext`, `produce` |
| Jobs | `Job` (`cancel`, `join`, `isActive/isCancelled/isCompleted`, `invokeOnCompletion`), `CompletableJob`, `SupervisorJob`, `CancellationException`, `ensureActive`, `CoroutineScope(ctx)`, `GlobalScope` |
| Time | `delay`, `withTimeout`, `withTimeoutOrNull`, `yield`, `TimeoutCancellationException` |
| Dispatchers | `Dispatchers.Default` (pool, CPU count), `Dispatchers.IO` (pool, 16), `Dispatchers.Unconfined`, the event loop of `runBlocking` |
| Sync | `Mutex` (`lock`, `unlock`, `tryLock`, `withLock`) |
| Channels | `Channel(capacity)` (rendezvous, buffered, `UNLIMITED`), `send`, `receive`, `receiveOrNull`, `trySend`, `tryReceive`, `close`, `cancel`, `for (x in channel)`, `consumeEach` |
| Flow | `flow { }`, `flowOf`, `asFlow`, `collect`, `map`, `filter`, `onEach`, `take`, `toList`, `first`, `sum`, `launchIn` |

* **Structured concurrency.** A coroutine is a job that is the completion of its block and the scope of its children; it
  completes when its body and all its children are complete. A child's failure cancels its parent and, through it, the
  siblings (not under `supervisorScope`/`SupervisorJob`); `coroutineScope`, `withContext` and `withTimeout` rethrow the
  failure in the caller. A failure of a root `launch` that nobody handles is printed to stderr. Cancellation is cooperative:
  every suspension point of the library (`delay`, `join`, `await`, `send`, `receive`, `Mutex.lock`, `yield`) resumes with a
  `CancellationException` when the job is cancelled, also while it waits in a queue (the entry is removed).
* **Dispatchers.** A dispatcher is a `ContinuationInterceptor`; a resumed continuation is queued on it, never run inline (stack
  safe, deterministic order on the event loop). The event loop of `runBlocking` is a task queue plus a timer heap that sleeps on a
  condition variable (timed wait = timers, signal = wakeup): the whole "poller" is the small `TaskQueue`/`CoroutineDispatcher`
  interface in `Dispatchers.kt`, which is where an epoll/kqueue/WSAPoll-based poller for sockets and files will plug in
  (planned, together with `mini.io` suspend APIs; v1 is timers and wakeups only). `Dispatchers.Default`/`IO` are fixed pools of
  native threads with a work queue; their `delay` uses one timer thread.
* **Thread safety.** All state of jobs, continuations, channels and mutexes is protected by one global lock
  (`CoroutinesLock`); handlers and resumptions run after it is released. That is simple and correct, not fast.
* **Differences from kotlinx.coroutines:** no `CoroutineStart` options (all coroutines start immediately, dispatched), no
  `Dispatchers.Main`, no `CoroutineExceptionHandler` (uncaught failures go to stderr), no `select`, `actor`, `StateFlow`,
  `SharedFlow`, `buffer`/`flowOn`/`combine` operators, no `Semaphore`; `Flow` is cold and sequential only; the lambdas of
  `collect` run in the collecting coroutine; `Job.cancel()` without argument is an extension.
* `runBlocking` blocks the calling thread by waiting on its condition variable; a `Dispatchers.Default` coroutine started from it
  runs on a pool thread and completes the blocking coroutine through the same lock.

## 5. Tests

* `compiler/testdata/coroutines_*.kt` with `.out` (C, LLVM, and both with the leak sanitizer): the state machine
  (`coroutines_state_machine`, `coroutines_control_flow`: loops with labeled break/continue, try/catch/finally with
  return/break across suspension points, expressions with several suspension points, interface default methods, operators,
  generics), sequences (`coroutines_sequence`), `launch`/`async`/`join` ordering and `delay` (`coroutines_async`), the library
  (`coroutines_library`: channels, flows, `Mutex`, timeouts, cancellation, scope failure), threads (`coroutines_threads`), and
  the diagnostic (`coroutines_suspend_error.cerr`). The library tests carry a `.nok2` marker: the K2 oracle has no
  `mini.coroutines`.
* `runtime/tests/test_sys.c`, `test_threads.c`.
* The website guide runs the `sequence { }` examples through `website/scripts/check-samples.sh`.

## 6. Not done yet

Suspension points inside inline lambdas, builder inference, `select`, an I/O poller (sockets, files, processes) behind the
dispatcher interface, `Dispatchers.Main`, `Semaphore`, more `Flow` operators, a deterministic virtual-time test dispatcher,
Windows build verification of `sys.c` (written against the Win32 API, not run), a wasm event loop (sequences work in the browser;
timers and threads do not exist there).
