# 8. Threads and ThreadSafe

A **thread boundary** is a call of a function whose lambda parameter is annotated `@ThreadBoundary`. `mini.memory.spawn` is one; `kotlin.concurrent.thread` and `Thread(Runnable)` are recognized as well. For the lambda:

- captured **owned** values are *moved* in: the lambda owns them and must free or pass them on. Using them afterwards in the caller is [M0104](/reference/diagnostics/M0104);
- a value whose allocator is **not `ThreadSafe`** (`Arena`, `Pool`), or the allocator itself, must not cross: [M0206](/reference/diagnostics/M0206). `Heap` objects may;
- borrowed values and arena objects must not go into a thread lambda ([M0109](/reference/diagnostics/M0109) and [M0105](/reference/diagnostics/M0105)).

## Moving an owned value into a thread

<<< @/samples/memory/thread_move.kt{kotlin}

`b` is moved into the thread, which frees it. The checker accepts the program.

## A non-ThreadSafe allocator

<<< @/samples/memory/thread_pool.kt{kotlin}

Rejected with [M0206](/reference/diagnostics/M0206). Use a `Heap` for objects that cross threads.

## At run time

Reference counts are non-atomic by default and become atomic when an object is shared between threads (`mk_share`, the `MK_FLAG_SHARED` flag). The runtime test suite runs its threading test under ThreadSanitizer. The `java.lang.Thread` used by `spawn` is currently only a placeholder in the Mini stdlib, which is another reason Strict programs using `spawn` are check-only today. A concurrency and async IO layer is [planned](/roadmap).

Next: [`@NoAlloc`](./noalloc).
