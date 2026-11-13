# Native threads

Status: threads-1, threads-2 and threads-3. Related: `docs/memory-model.md` (5.9 thread boundaries, "Threads" in the cycle collector part),
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
| Collections | `ConcurrentHashMap` (one mutex by default, `SEGMENTS` in `Collections.kt` selects lock striping, see Benchmark; `putIfAbsent`, `computeIfAbsent`, `compute`, `merge`, `replace`, snapshots as views), `ConcurrentLinkedQueue`, `BlockingQueue` with `LinkedBlockingQueue`, `ArrayBlockingQueue`, `LinkedBlockingDeque` (one mutex, two conditions), `CopyOnWriteArrayList` |
| Executors | `Executor`, `ExecutorService`, `ScheduledExecutorService`, `Executors.newFixedThreadPool` / `newSingleThreadExecutor` / `newCachedThreadPool` / `newWorkStealingPool` / `newScheduledThreadPool`, `Future`, `FutureTask`, `Callable`, `submit`, `invokeAll`, `invokeAny`, `shutdown`, `shutdownNow`, `awaitTermination`, `close` (delayed and periodic scheduling: see threads-3) |
| `CompletableFuture` | `supplyAsync`, `runAsync`, `completedFuture`, `thenApply`, `thenAccept`, `thenRun`, `thenCompose`, `thenCombine`, `exceptionally`, `whenComplete`, `allOf`, `join`, `get`, `complete`, `completeExceptionally` |
| Exceptions | `ExecutionException`, `TimeoutException`, `CancellationException`, `RejectedExecutionException`, `BrokenBarrierException`, `CompletionException` |
| `java.util.function` | `Supplier`, `Consumer`, `BiConsumer`, `Function`, `BiFunction`, `Predicate`, the int / long operators |

The Java `isX()` / `getX()` accessors of these classes are Kotlin properties here (`future.isDone`, `latch.count`, `executor.isShutdown`), as
Kotlin code reads them. Where a lambda reaches both a function-type overload and a SAM overload (`CompletableFuture.supplyAsync { }`,
`thenApply { }`), the frontend picks the function type, as kotlinc does.

## 3. Differences from the JVM

* No daemon threads, no thread priorities, no thread groups.
* The pools are simple (one queue, one mutex): no work stealing (a simple `ForkJoinPool` exists since threads-3), no `ThreadPoolExecutor` tuning API besides
  the factory methods; workers of a pool that is never shut down die with the process.
* The concurrent collections use locks (one for `ConcurrentHashMap`, one per queue) and snapshot iteration instead of the lock-free JVM
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

`ConcurrentHashMap` has a segmented implementation (`SEGMENTS` in `stdlib/java/util/concurrent/Collections.kt`, a mutex and a `HashMap` per segment), but the
default is **1 segment**, i.e. one lock. Measured (8 threads, 8 x 250000 `merge` calls over 4096 keys): about 350 ms with one lock, 900 to 1250 ms with 32
segments. While threads run, reference counts are atomic, and the segments' tables and the shared keys and values then bounce between cores; that cost is
larger than the lock contention it removes. The segmented code stays so that the measurement can be repeated when the reference counting changes.

### Single-thread cost

Callgrind instruction counts (total `Ir`, `-O2`), main (49fa76e) against threads-2; the relaxed atomics of the cast cache, the allocation volume counter
(`MK_CYCLES_ADD`, also on the inline allocation path) and the thread check of the reference array dispose are free on x86-64 (plain `mov`) and cost no
measurable time. The dispose check is made once per array, not per item.

| Benchmark | main `Ir` | threads-2 `Ir` | Change |
|---|---|---|---|
| binarytrees | 11314941690 | 11314941694 | +0.00% |
| strbuild | 10039189131 | 10039189134 | +0.00% |
| json | 3598407125 | 3606143112 | +0.21% |
| graph | 5205994449 | 5205293139 | -0.01% |
| wordcount | 13651171043 | 13651364494 | +0.00% |
| nbody | 8875278114 | 8875278111 | -0.00% |
| hello world (bytes) | 19072 | 19072 | equal |


## 6. threads-3

The gap list against what ordinary Kotlin/JVM code uses (java.util.concurrent, kotlin.concurrent) was made from the state after threads-2; everything on it
that needs no I/O is closed. Tests: `compiler/testdata/threads3_*.kt` (outputs from kotlinc 2.1.20 on the JVM, `.nowasm`: wasm has no threads), run by the `c`,
`llvm`, `leak`, `tsan` suites (`memrun` skips programs with threads) and flake-checked 15 to 20 runs each.

### Added

| Area | What |
|---|---|
| Executors | `ThreadFactory`, `Executors.defaultThreadFactory()` (threads named `pool-N-thread-M` like the JDK), the factory overloads of `newFixedThreadPool` / `newSingleThreadExecutor` / `newCachedThreadPool` / `newScheduledThreadPool` / `newSingleThreadScheduledExecutor`, `Executors.callable`, `ThreadPoolExecutor.threadFactory`; **real delayed and periodic scheduling**: `schedule`, `scheduleAtFixedRate`, `scheduleWithFixedDelay` run on one timer thread per pool (a sorted list under the pool's mutex) that hands due tasks to the workers, instead of a worker that sleeps for the delay; `ScheduledFuture` is a `Delayed` (`getDelay`), `cancel` removes the task, `shutdown` cancels periodic tasks and lets one-shot delayed tasks run (`awaitTermination` waits for them), `shutdownNow` returns them; `CompletionService` / `ExecutorCompletionService` |
| Fork / join | `ForkJoinPool` (`invoke`, `submit`, `parallelism`, `commonPool()`), `ForkJoinTask` (`fork`, `join`, `invoke`, `get`, `invokeAll`, `isCompletedAbnormally`), `RecursiveTask`, `RecursiveAction`. `fork` queues the task on the pool, `join` runs it on the calling thread when no worker has started it (a join never waits for a task that is not running, so a pool of any size cannot deadlock on recursive tasks); there are no per-worker deques and no stealing. `Executors.newWorkStealingPool` is a `ForkJoinPool`; `CompletableFuture`'s async stages run on `ForkJoinPool.commonPool()` (threads named `ForkJoinPool.commonPool-worker-N`; the common pool grows like a cached pool, since its tasks may block) |
| Queues | `BlockingDeque` and a real `LinkedBlockingDeque` (both ends, timed variants, `push` / `pop`, `descendingIterator`), `ConcurrentLinkedDeque`, `PriorityBlockingQueue`, `SynchronousQueue` (hand-off, `offer` succeeds only when a taker waits), `DelayQueue` / `Delayed`, `LinkedTransferQueue` (without `transfer`), `CopyOnWriteArraySet`; `CopyOnWriteArrayList.remove(element)` / `clear` / `contains` now change the list (they went through the snapshot iterator and did nothing) |
| Sorted | `ConcurrentSkipListMap` / `ConcurrentSkipListSet`: a `TreeMap` / `TreeSet` behind one mutex; navigation (`floorKey`, `ceilingKey`, `pollFirstEntry`, ...) works, the sub-map views are snapshots |
| Atomics | `AtomicIntegerArray`, `AtomicLongArray`, `AtomicReferenceArray` (java.util.concurrent.atomic), `LongAdder` / `DoubleAdder` (16 padded cells chosen by thread id), `LongAccumulator` / `DoubleAccumulator`, `AtomicStampedReference`, `AtomicMarkableReference` |
| Synchronizers | `Phaser` (`register`, `bulkRegister`, `arrive`, `arriveAndAwaitAdvance`, `arriveAndDeregister`, `awaitAdvance`, `onAdvance`, termination), `Exchanger`, `StampedLock` (write / read / optimistic stamps, conversions, `validate`), `ThreadLocalRandom` (one shared instance with per-thread state, as on the JVM), `Condition.awaitUninterruptibly`, `ReentrantLock.isFair` |
| Timers | `java.util.Timer` / `TimerTask` on a single-thread scheduled pool, `kotlin.concurrent.timer`, `fixedRateTimer`, `timerTask`, `Timer.schedule` / `scheduleAtFixedRate` extensions |
| Functional interfaces | `BinaryOperator`, `DoubleUnaryOperator`, `DoubleBinaryOperator`, `IntSupplier`, `LongSupplier`, `BooleanSupplier`, `IntConsumer`, `IntFunction`, `IntPredicate`, `ToIntFunction`, `ToLongFunction`, `BiPredicate` |

### Runtime and compiler

* Element atomics on `IntArray` / `LongArray` (`mk_atomic_int_get_at`, `..._add_at`, `..._cas_at`, ...; tested in `runtime/tests/test_sys.c`). The atomic element operations of
  `kotlin.concurrent` (`IntArray.atomicGet` / `compareAndSet` / `getAndAdd`, the `Long` ones) no longer take one global lock: they are single atomic instructions.
  The `Array<T>` ones still use the lock.
* The cell of `AtomicReference` was guarded by **one** global spin lock without any backoff. There are now 64 locks (one cache line each, chosen by the address of
  the cell), and a waiter spins 64 rounds with a CPU pause and then yields its time slice (`mk_sys_thread_yield`), so a preempted holder does not burn the
  slices of the spinners. (The cycle collector's `roots_lock` got the same treatment in memsafe-2.)
* RC elision: the retain / release pair around the `cell` field of an `AtomicInteger` / `AtomicLong` (and around the storage of `Lock` / `Condition`) was made for every
  call, and with threads running those are atomic read-modify-writes on the very cache line that holds the value. `mk_atomic_int_*`, `mk_atomic_long_*`
  and the non-waiting `mk_sys_lock` / `unlock` / `try_lock` / `cond_signal` / `cond_broadcast` store no references, so RcOpt (`RcEffects.isPureRuntime`) now lets the field
  read borrow across them. An `AtomicLong.incrementAndGet()` loop went from three atomic operations per call to one.
* The blocking queues signal a condition only when a thread waits on it (`LinkedBlockingQueue`).
* Evaluation order of string templates (found while writing the queue test): `"$list ${list.add(1)} $list"` converts each piece when it is appended, as kotlinc does
  (the StringBuilder code of jvmTarget 1.8), not after all pieces were evaluated. Test: `string_template_order.kt`.

### Benchmarks against Rust

`bench/mutex`, `bench/atomics`, `bench/queue` (`.kt` and `.rs`, 4 threads, registered in `bench/run.py`; `bench/run.py --only mutex,atomics,queue --langs rust,kotlin-jvm,mini-managed`).
Wall seconds, best of 7, on the 4-core development machine **while its load average was 13** (other jobs were running, so only the ratios mean something):

| benchmark | Rust | Kotlin/JVM | Mini (before) | Mini (now) |
|---|---:|---:|---:|---:|
| mutex: 4 x 1000000 lock / increment / unlock (`ReentrantLock`, Rust `Mutex`) | 0.12 | 0.19 | 0.78 | 0.36 |
| atomics: 4 x 5000000 `incrementAndGet` + 4 x 1000000 compare-and-set loops | 0.22 | 0.53 | 0.77 | 0.39 |
| queue: 4 producers, 1 consumer, 1000000 items, capacity 1024 (`ArrayBlockingQueue`, Rust `sync_channel`) | 0.08 | 0.28 | 1.35 | 0.61 |

The atomics are now between Rust and the JVM (the C compiler sees one `lock xadd`); the mutex is about 3 times Rust's (a pthread mutex plus the reentrancy
bookkeeping of `ReentrantLock`); the queue is a mutex and two condition variables, about twice the JVM and 7 times Rust's channel (which is lock free).
Tried and dropped: spinning 100 or 1000 rounds on `pthread_mutex_trylock` before sleeping in `mk_sys_lock` (the adaptive mutex idea): on the loaded machine it made
the queue and the mutex benchmarks slower, not faster.

### Still different from the JVM

* No work stealing between per-thread deques, no `ForkJoinPool` parallelism management (a `ForkJoinPool(n)` has n workers), no `ThreadPoolExecutor` constructor with a `BlockingQueue` and `RejectedExecutionHandler`.
* `ConcurrentSkipListMap` / `Set` are locked tree maps (O(n) insertion into the sorted array), not skip lists; `LinkedTransferQueue.transfer` is missing; `ConcurrentHashMap.newKeySet()` is a set over a map (no `KeySetView` type).
* `ForkJoinTask.join` rethrows the original exception (the JVM rethrows a copy made in the joining thread when the task failed in another one).
* `Exchanger` and `SynchronousQueue` are not fair queues of waiters; `Phaser` has no tiers (parent phasers).
* Not added on purpose: everything that needs I/O, selectors or an event loop.
