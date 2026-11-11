# Native threads

Status: threads-1. Related: `docs/memory-model.md` (5.9 thread boundaries, "Threads" in the cycle collector part),
`docs/coroutines.md`, `docs/stdlib.md`. Code: `runtime/src/sys.c`, `runtime/src/lock.h`, `stdlib/mini/internal/Sys.kt`,
`stdlib/mini/concurrent/Concurrent.kt`, `stdlib/java/lang/Thread.kt`, `stdlib/java/util/concurrent/**`, `stdlib/kotlin/concurrent/*`.

There is no libuv and no other third-party layer: POSIX threads, mutexes and condition variables on Linux and macOS, Win32 threads
and SRW locks on Windows. On wasm32 there are no threads (a thread start or an unbounded wait panics instead of hanging).

## 1. What there is (the state before threads-1, and what stays)

| Area | Implementation |
|---|---|
| Thread start / join | `mk_sys_thread_start(fn)` / `mk_sys_thread_join(handle)`: pthread_create / CreateThread; an exception that ends a thread is printed and ends only that thread |
| Locks, conditions | `mk_sys_lock*` / `mk_sys_cond*` over a `LongArray` of 16 words (pthread mutex + condvar with CLOCK_MONOTONIC, SRWLOCK on Windows); `mini.concurrent.Lock` / `Condition` |
| Atomics | `mk_atomic_*` (`__atomic` builtins), `mini.concurrent.AtomicInt/Long/Reference`, `kotlin.concurrent.atomics.*` (Kotlin 2.1 API) |
| Reference counts | atomic from the first started thread on (`mk_threads_active`, one-way switch; the C backend defines `MK_ATOMIC_RC` when the program contains `java.lang.Thread.start`) |
| Allocator | thread-local free lists, a mutex-protected central pool (memory-model.md section 3) |
| Cycle collector | runs only when no Kotlin thread is running (`mk_threads_running == 0`) |
| Coroutines | `runBlocking` event loop on the calling thread, `Dispatchers.Default` / `IO` fixed pools of native threads, a timer thread (`stdlib/mini/coroutines/Dispatchers.kt`) |
| Memory checker | thread lambdas are a move boundary (`thread { }`, `Thread { }`, `@ThreadBoundary`), `Pool` / `Arena` objects may not cross (M0206) |

Reference counting across threads: every count operation is a relaxed-to-seq-cst atomic once threads exist, and an object whose last
reference is dropped is freed by the thread that dropped it (its allocator free list is thread-local, so no cross-thread free list races).
Nothing is frozen: sharing a mutable object between threads is allowed, and the program is responsible for the synchronisation of its
fields, as on the JVM. Ownership transfer is the Strict mode `move` into a thread lambda (M0107 after the move).

## 2. What threads-1 adds

### Runtime

* Threads are detached at creation (their stacks and handles go back to the system when they end, whether or not anybody joins them).
  `mk_sys_thread_start` returns a handle shared by the thread and its creator (`refs`): `mk_sys_thread_join` waits for the end of the thread and
  gives the handle up, `mk_sys_thread_detach` gives it up at once. `java.lang.Thread` joins through its own mutex and condition variable (so a
  thread that is never joined leaks nothing: the leak suites run the thread tests under LeakSanitizer).
* `mk_sys_thread_id()`: a small id per thread (1 for the thread that starts the first thread, then in start order) and
  `mk_sys_thread_yield()` (`sched_yield` / `SwitchToThread`). Test: `runtime/tests/test_threads.c`.

### java.lang

* `Thread`: open class (subclasses overriding `run()` work), constructors `()`, `(block)`, `(Runnable)`, `(Runnable, name)`, `(name)`;
  `name`, `id`, `isDaemon`, `priority` (stored; scheduling priorities and daemon exit semantics are not implemented: the process ends when
  `main` returns, daemon or not), `isAlive`, `join()`, `join(millis)`, `interrupt()` / `isInterrupted` / `Thread.interrupted()` (a flag; blocking
  calls do not wake up), `Thread.currentThread()`, `Thread.sleep`, `Thread.yield`, `Thread.onSpinWait`. Starting twice throws
  `IllegalThreadStateException`.
* `Runnable`, `ThreadLocal` (`get` / `set` / `remove`, `initialValue()` override, `withInitial`; values are kept per thread id and are not
  freed when a thread ends), `InterruptedException`, `IllegalMonitorStateException`, `IllegalThreadStateException`.
* `kotlin.synchronized(lock) { }`: 256 reentrant monitors chosen by object identity (two objects can share one, which can turn a lock-order
  inversion between them into a deadlock that real monitors would not have); no `wait` / `notify`, `@Synchronized` is still ignored.
* `kotlin.concurrent`: `thread(...)` with `name`, `isDaemon`, `priority`; `Lock.withLock`, `ReentrantReadWriteLock.read` / `write`, `ThreadLocal.getOrSet`.

### java.util.concurrent

| Group | API |
|---|---|
| Atomics (`.atomic`) | `AtomicInteger`, `AtomicLong`, `AtomicBoolean`, `AtomicReference` with the JVM method names (`incrementAndGet`, `compareAndSet`, `updateAndGet`, `accumulateAndGet`, ...) |
| Locks (`.locks`) | `Lock`, `Condition`, `ReentrantLock` (reentrant, `tryLock(time)`, conditions with `await` / `awaitNanos` / `signal`), `ReentrantReadWriteLock` (no fairness) |
| Synchronizers | `TimeUnit`, `CountDownLatch`, `Semaphore`, `CyclicBarrier` |
| Collections | `ConcurrentHashMap` (one mutex; `putIfAbsent`, `computeIfAbsent`, `compute`, `merge`, `replace`, snapshots as views), `ConcurrentLinkedQueue`, `BlockingQueue` with `LinkedBlockingQueue`, `ArrayBlockingQueue`, `LinkedBlockingDeque` (one mutex, two conditions), `CopyOnWriteArrayList` |
| Executors | `Executor`, `ExecutorService`, `ScheduledExecutorService`, `Executors.newFixedThreadPool` / `newSingleThreadExecutor` / `newCachedThreadPool` / `newWorkStealingPool` / `newScheduledThreadPool`, `Future`, `FutureTask`, `Callable`, `submit`, `invokeAll`, `invokeAny`, `shutdown`, `shutdownNow`, `awaitTermination`, `close` (`schedule` runs the task on a worker that sleeps for the delay; no periodic scheduling) |
| `CompletableFuture` | `supplyAsync`, `runAsync`, `completedFuture`, `thenApply`, `thenAccept`, `thenRun`, `thenCompose`, `thenCombine`, `exceptionally`, `whenComplete`, `allOf`, `join`, `get`, `complete`, `completeExceptionally` |
| Exceptions | `ExecutionException`, `TimeoutException`, `CancellationException`, `RejectedExecutionException`, `BrokenBarrierException`, `CompletionException` |
| `java.util.function` | `Supplier`, `Consumer`, `BiConsumer`, `Function`, `BiFunction`, `Predicate`, the int / long operators |

The Java `isX()` / `getX()` accessors of these classes are Kotlin properties here (`future.isDone`, `latch.count`, `executor.isShutdown`), as
Kotlin code reads them. Where a lambda reaches both a function-type overload and a SAM overload (`CompletableFuture.supplyAsync { }`,
`thenApply { }`), the frontend picks the function type, as kotlinc does.

## 3. Differences from the JVM

* No daemon threads, no thread priorities, no `wait` / `notify`, no `Thread.interrupt` wake-up of sleeping or waiting threads, no thread groups.
* The pools are simple (one queue, one mutex): no work stealing, no `ForkJoinPool`, no periodic scheduling, no `ThreadPoolExecutor` tuning API besides
  the factory methods; workers of a pool that is never shut down die with the process.
* The concurrent collections use a single lock and snapshot iteration instead of the lock-free JVM designs; they are correct, not scalable.
* A program is ended when `main` returns: running threads are not waited for.
* `kotlinx.coroutines` is not present: the coroutine library is `mini.coroutines` (docs/coroutines.md).

## 4. Tests

* `runtime/tests/test_threads.c` (ASan/UBSan and TSan in `runtime/run_tests.sh`): atomic reference counts, the thread-safe allocator, thread ids.
* `compiler/testdata/threads_api.kt`, `threads_concurrent.kt`: the API above, outputs produced by the same programs on the JVM (kotlinc 2.1.20).
* `compiler/testdata/threads_stress.kt`: producers / consumers moving objects through queues, maps, executors and futures; run by the leak suites
  (AddressSanitizer + LeakSanitizer, C and LLVM backend).

### ThreadSanitizer

`runtime/run_tests.sh` runs `test_threads.c` under TSan (gcc when clang lacks the runtime). The generated C of the thread tests, built with
`gcc -fsanitize=thread` against the runtime sources, reports no race in the library code of this document (the `ReentrantLock` owner is an
atomic cell for that reason). What remains are two sites of the runtime that predate threads-1 and that the older thread tests (`threads_shared_readers`)
show as well: the statistics counter `mk_cycles_alloc_bytes` (a heuristic that is updated without atomics on purpose) and the plain reads of
object flags in the cycle-candidate buffer (`cycles_candidate`, protected by the roots lock on the writer side).
