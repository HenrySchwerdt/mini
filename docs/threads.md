# Native threads

Status: threads-1 and threads-2. Related: `docs/memory-model.md` (5.9 thread boundaries, "Threads" in the cycle collector part),
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
  `main` returns, daemon or not), `isAlive`, `join()`, `join(millis)`, `interrupt()` / `isInterrupted` / `Thread.interrupted()` (see "Interrupts"),
  `Thread.currentThread()`, `Thread.sleep`, `Thread.yield`, `Thread.onSpinWait`. Starting twice throws
  `IllegalThreadStateException`.
* `Runnable`, `ThreadLocal` (`get` / `set` / `remove`, `initialValue()` override, `withInitial`; values are kept per thread id and are not
  freed when a thread ends), `InterruptedException`, `IllegalMonitorStateException`, `IllegalThreadStateException`.
* `kotlin.synchronized(lock) { }`: 256 reentrant monitors chosen by object identity (two objects can share one, which can turn a lock-order
  inversion between them into a deadlock that real monitors would not have). See "Monitors" below for `@Synchronized`, `wait` and `notify`.
* `kotlin.concurrent`: `thread(...)` with `name`, `isDaemon`, `priority`; `Lock.withLock`, `ReentrantReadWriteLock.read` / `write`, `ThreadLocal.getOrSet`.

### java.util.concurrent

| Group | API |
|---|---|
| Atomics (`.atomic`) | `AtomicInteger`, `AtomicLong`, `AtomicBoolean`, `AtomicReference` with the JVM method names (`incrementAndGet`, `compareAndSet`, `updateAndGet`, `accumulateAndGet`, ...) |
| Locks (`.locks`) | `Lock`, `Condition`, `ReentrantLock` (reentrant, `tryLock(time)`, conditions with `await` / `awaitNanos` / `signal`), `ReentrantReadWriteLock` (no fairness) |
| Synchronizers | `TimeUnit`, `CountDownLatch`, `Semaphore`, `CyclicBarrier` |
| Collections | `ConcurrentHashMap` (32 segments with a mutex each; `putIfAbsent`, `computeIfAbsent`, `compute`, `merge`, `replace`, snapshots as views), `ConcurrentLinkedQueue`, `BlockingQueue` with `LinkedBlockingQueue`, `ArrayBlockingQueue`, `LinkedBlockingDeque` (one mutex, two conditions), `CopyOnWriteArrayList` |
| Executors | `Executor`, `ExecutorService`, `ScheduledExecutorService`, `Executors.newFixedThreadPool` / `newSingleThreadExecutor` / `newCachedThreadPool` / `newWorkStealingPool` / `newScheduledThreadPool`, `Future`, `FutureTask`, `Callable`, `submit`, `invokeAll`, `invokeAny`, `shutdown`, `shutdownNow`, `awaitTermination`, `close` (`schedule` runs the task on a worker that sleeps for the delay; no periodic scheduling) |
| `CompletableFuture` | `supplyAsync`, `runAsync`, `completedFuture`, `thenApply`, `thenAccept`, `thenRun`, `thenCompose`, `thenCombine`, `exceptionally`, `whenComplete`, `allOf`, `join`, `get`, `complete`, `completeExceptionally` |
| Exceptions | `ExecutionException`, `TimeoutException`, `CancellationException`, `RejectedExecutionException`, `BrokenBarrierException`, `CompletionException` |
| `java.util.function` | `Supplier`, `Consumer`, `BiConsumer`, `Function`, `BiFunction`, `Predicate`, the int / long operators |

The Java `isX()` / `getX()` accessors of these classes are Kotlin properties here (`future.isDone`, `latch.count`, `executor.isShutdown`), as
Kotlin code reads them. Where a lambda reaches both a function-type overload and a SAM overload (`CompletableFuture.supplyAsync { }`,
`thenApply { }`), the frontend picks the function type, as kotlinc does.

## 3. Differences from the JVM

* No daemon threads, no thread priorities, no thread groups.
* The pools are simple (one queue, one mutex): no work stealing, no `ForkJoinPool`, no periodic scheduling, no `ThreadPoolExecutor` tuning API besides
  the factory methods; workers of a pool that is never shut down die with the process.
* The concurrent collections use locks (one per segment for `ConcurrentHashMap`, one per queue) and snapshot iteration instead of the lock-free JVM
  designs. Every object that several threads touch pays an atomic reference count operation per access (docs/memory-model.md), so they scale
  less well than on the JVM.
* A program is ended when `main` returns: running threads are not waited for.
* `kotlinx.coroutines` is not present: the coroutine library is `mini.coroutines` (docs/coroutines.md).

## 4. Tests

* `runtime/tests/test_threads.c` (ASan/UBSan and TSan in `runtime/run_tests.sh`): atomic reference counts, the thread-safe allocator, thread ids.
* `compiler/testdata/threads_api.kt`, `threads_concurrent.kt`, `threads_interrupt.kt`: the API above, outputs produced by the same programs on the JVM (kotlinc 2.1.20).
* `compiler/testdata/threads_stress.kt`: producers / consumers moving objects through queues, maps, executors and futures; run by the leak suites
  (AddressSanitizer + LeakSanitizer, C and LLVM backend).


## 5. threads-2

### Monitors

* `@Synchronized` on a function: the parser rewrites the body to `synchronized(this) { body }` (members of classes, objects and companions) or
  `synchronized(Unit) { body }` (top-level functions: one monitor for all of them; there is no class object to lock). It works for block and
  expression bodies, non-local `return` included. Not on local functions and not on property accessors.
* `Any.wait()`, `wait(timeoutMillis)`, `notify()`, `notifyAll()` are extension functions (package `kotlin`; `java.lang.Object` is a typealias of `Any`, so
  the JVM idiom `(lock as java.lang.Object).wait()` compiles). They need the monitor (`IllegalMonitorStateException` otherwise). Every monitor stripe
  has one condition variable: objects that share a stripe share it, so `notify` wakes all waiters of the stripe (the JVM permits spurious wake-ups:
  loop on the condition).

### Interrupts

`Thread.interrupt()` sets the flag of the target thread and wakes it when it is in `Thread.sleep`, `Thread.join`, `Condition.await` (the
`java.util.concurrent.locks` conditions and the ones under `CountDownLatch`, `Semaphore`, the blocking queues, `Future.get`, `CyclicBarrier`) or
`Object.wait`: the call throws `InterruptedException` and clears the flag. A flag that is set before a blocking call makes the call throw at once.
Implementation: the waiting thread registers the condition variable and mutex it waits on (`java.lang.interruptibleWait`), `interrupt` takes that mutex
and broadcasts the condition (the thread is then surely inside its wait); if the mutex is held by the interrupting thread itself the wait ends at its
next one-second slice. The java.util.concurrent conditions return with the lock held when they throw (JVM behaviour); the internal
`mini.concurrent.Condition` releases its lock then, which is what the library's own lock / await / unlock code relies on.
Not interruptible: `ReentrantLock.lock()` itself, monitor entry in `synchronized`, I/O, the coroutine event loops.

### Runtime

* The cycle collector's volume counter and its flag reads are relaxed atomics (plain loads and stores on x86-64 and arm64, so no cost on the single-thread
  path); a collection is only considered when no Kotlin thread runs (`mk_threads_running` is read first). The C backend's cast cache (one
  `static const MkTypeInfo *` per interface cast site) is read and written with relaxed atomics as well.
* `mk_sys_thread_data` / `mk_sys_thread_set_data`: a per-thread slot that holds the `java.lang.Thread`, so `Thread.currentThread()` is a thread-local read.
* Windows and wasm: the new functions sit in the existing `_WIN32` / `MK_POSIX_THREADS` / wasm branches. There is no Windows toolchain in the
  development environment, so the runtime was syntax-checked with `clang -D_WIN32` against a stand-in `windows.h` that declares the Win32 calls the runtime
  uses (`CreateThread`, `CloseHandle`, `SwitchToThread`, SRW locks, ...) and with `-D__wasm__` (no thread code): `-Wall -Wextra -Werror`, no diagnostics.
  Please watch the Windows CI for this part.

### ThreadSanitizer

`runtime/run_tests.sh` runs `test_threads.c` under TSan (gcc when clang lacks the runtime). The generated C of every `threads_*` and `corout*` e2e
program, built with `gcc -fsanitize=thread` against the runtime sources, reports no data race (the two sites of threads-1, the allocation volume counter
and the cycle-candidate flag reads, and the cast cache of the C backend are fixed).

### Benchmark

`bench/concurrent/concurrent.kt` (`bench/run.sh --only concurrent`): 8 threads merging into one `ConcurrentHashMap`, 4 producers and 4 consumers on an
`ArrayBlockingQueue`, atomics. It runs on Mini and on Kotlin/JVM with the same source.
