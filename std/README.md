# Standard-library direction

Mini's std follows Kotlin's naming and interface/implementation organization,
with explicit value-based error handling. This is a design contract and the
small implemented foundation; it is not a claim that Kotlin's std is ported.

The Kotlin API is the default reference for public names, collection interfaces,
method organization and documented behavior. Track intentional deviations instead
of inventing an unrelated API. References: [Kotlin std API](https://kotlinlang.org/api/core/kotlin-stdlib/)
and [collection interfaces](https://kotlinlang.org/docs/collections-overview.html).
Use the platform-independent surface first; JVM/JDK-specific APIs require a native
adapter or an explicit replacement. Read-only collection views and mutable
implementations are separate contracts; a read-only view does not promise deep
immutability of the underlying storage.

## Rules

- Ordinary absence is `Optional<T>`. Recoverable failure is `Result<T, E>`.
- Public fallible APIs do not throw or return magic sentinel values. Concrete
  error types must distinguish errors meaningful to callers.
- Mini has no `null`. C/runtime sentinels, negative OS return codes and `errno`
  stay inside adapters. Convert absence to Optional and failures to typed errors.
- Resource ownership and release must be explicit. A `Result<File, IOError>` owns
  its successful file handle only according to the future File ownership contract;
  moving/copying owners must be settled before shipping that API.
- Programmer errors need an explicit policy (compile error where possible,
  checked result or documented trap); no implicit exception subsystem is assumed.
- All methods live in classes/interfaces. Helpers that Kotlin exposes at top level
  become static utility-class methods. No public API relies on a global mutable singleton.
- Preserve `match` with payload destructuring as the standard way to handle native
  Optional/Result values. Further Kotlin-inspired syntax must not replace it with
  `when` or an exception-based control-flow model.

## Intended Kotlin-shaped surface

| Area | Intended shape | Value-based result |
| --- | --- | --- |
| Collections | `List<T>`, `MutableList<T>`, `ArrayList<T>` | Indexed operations use `Result<T, IndexError>` |
| Maps | `Map<K,V>`, `MutableMap<K,V>`, `HashMap<K,V>` | Lookup uses `Optional<V>` |
| Sets / iteration | `Set<T>`, `HashSet<T>`, `Iterable<T>`, `Iterator<T>` | Iterator exhaustion uses `Optional<T>` |
| Text | `String`, `StringBuilder` | Parsing uses `Result<T, ParseError>` |
| Files | `Path`, `File`, stream interfaces | Open/read/write/close use `Result<..., IOError>` |
| Allocation | `Allocator`, `HeapAllocator` | `Result<*u8, AllocationError>` |

The names above are a roadmap except for the implementation listed below.
Function types and the first lambda/capture model are implemented. Class-scoped
`Functions.run<R>` and `Functions.repeat` provide synchronous callback helpers.
A full Kotlin-style `map`/`filter` surface still needs generic instance methods;
resource `use` also needs a resource cleanup contract. Broader closure captures
and lifetime enforcement remain open.

## Implemented APIs

- `mini.core.Value<T>` and static helpers in `Core`.
- `mini.core.Functions.run<R>` and `repeat` with lambda arguments.
- `mini.collections.Cell<T>` with get/set; it is not an owning container.
- `Collection<T>`, `MutableCollection<T>`, `List<T>`, `MutableList<T>` and the
  `RandomAccess` marker, with allocator-backed `ArrayList<T>`.
  Implemented methods: `size`, `isEmpty`, `get`, `set`, `add`, `removeAt`, `clear`,
  `ensureCapacity` and `close`.
- `mini.memory.Allocator`, `HeapAllocator`, `AllocationError.OutOfMemory`.
  Raw `heap.alloc`/`heap.realloc` return `Optional<*u8>`; the std allocator converts
  allocation failure into `Result.Err`.
- `mini.io.Console`, `ByteIO`, `IOError.System`.
- `mini.math.Math`: integer `abs`, `min`, `max`, `clamp`, `sign`, `pow`, `gcd`,
  `lcm` and `isqrt`. Pure arithmetic, no allocation.
- `mini.core.Pair<A, B>` and `Triple<A, B, C>`: generic value tuples with public
  `first`/`second`/`third` fields and `Pair<A, B>.of(...)` / `Triple<...>.of(...)`
  factories. (Factories are class-scoped; method-level type parameters are not used
  because generic instance methods are a separate feature.)
- `mini.core.IntRange`: inclusive `i32` range with `of`, `contains`, `count`,
  `sum` and a `forEach((i32) -> void)` iterator helper.
- `mini.net`: libuv-backed blocking TCP. `NetError`/`NetErrorKind`, `TcpConnection`
  (`connect`, `connectLoopback`, `write`, `read`, `close`) and `TcpListener`
  (`bind`, `accept`, `close`). See "Native networking" below.

The initial I/O error is deliberately coarse; platform errors must be expanded
before promising a complete filesystem/network API. These modules currently
compile from source dependencies together and still use libc through the compiler's
small generated runtime adapters. Native Optional/Result allocation and matching
are handled by the compiler, not std classes or exceptions.

## First collection port

The public shape follows Kotlin's [List](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.collections/-list/),
[MutableList](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.collections/-mutable-list/)
and [ArrayList](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.collections/-array-list/)
APIs. This is a selected subset implemented in Mini, not a complete compatible port.

`get`, `set` and `removeAt` return `Result<T, IndexError>`; the error carries
`index` and `size`. `set` and `removeAt` return the previous element on success.
`add` and `ensureCapacity` return `Result<bool, CollectionError>` and distinguish
`OutOfMemory` from `CapacityOverflow`. A failed growth leaves existing elements
and size unchanged. `clear` retains capacity; `close` frees it, is idempotent and
leaves an empty reusable list.

Intentional native differences: indices are `usize`; `size()` is a method;
`create()` and `withAllocator()` are factories. `ensureCapacity` reports whether
capacity changed. Read-only interface pointers restrict the available methods,
but another mutable alias can still change the same list. Iterators, equality,
search, indexed insertion, sublists and higher-order APIs remain unimplemented.

**Ownership:** keep exactly one owning `ArrayList` value and call `close` explicitly.
Do not copy that value: the compiler currently does not prevent double ownership.
Pass borrowed pointers or interface views instead. Class elements stored in the heap buffer must extend `AllocatableObject`;
stack-only class values cannot be embedded in that buffer. An injected allocator is also
borrowed and must outlive the list. Elements are copied as values, with no automatic
destructor; use copyable/non-owning elements. `clear`, removal and `close` do not
release resources owned by elements. These rules must be enforced by a future
ownership/drop system before offering general owning element containers.

Build the example from the repository root:

```sh
xmake run mini0 build examples/collections.mini \
  --dep collections=std/mini/collections/list.mini \
  --dep collections=std/mini/collections/array_list.mini \
  --dep memory=std/mini/memory/allocator.mini \
  --depends collections=memory -o build/collections
```

Run `build/collections` (`.exe` on Windows); it prints `42`.
`examples/collections_errors.mini` uses the same dependencies and checks injected
allocation failure, capacity overflow, retained data, generic class elements and
balanced backing-buffer allocation/release. Both run in `xmake test`.

## Object allocation

`Object` and `AllocatableObject` are compiler-provided roots. Class values default
to `Object`; extend `AllocatableObject` to enable `Type.allocate(value[, allocator])`
and `Type.free(pointer[, allocator])`. Allocation returns `Result<*Type, AllocationError>`
and uses the existing `mini.memory.Allocator` contract for custom allocators.
These allocate/free the outer object only. `ArrayList.withAllocator` independently
selects the allocator for its backing buffer. Include this module's source when
using the factories. See [object and lifetime rules](../docs/language-v0.2.md#object-roots-and-typed-allocation)
and [the example](../examples/objects.mini). Raw pointers still require manual,
single-owner cleanup; compile-time lifetime and leak enforcement remains unfinished.

## Synchronous lambda helpers

`Functions.repeat(times: usize, action: (usize) -> void)` passes zero-based indices.
`Functions.run<R>(block: () -> R)` invokes once and returns its result. Both are
class-scoped counterparts of Kotlin helpers, with the current restricted capture
model documented in [the language guide](../docs/language-v0.2.md#function-types-and-lambdas).
Build the native example:

```sh
xmake run mini0 build examples/lambdas.mini \
  --dep core=std/mini/core/functions.mini -o build/lambdas
```

It prints `42`; it also checks returned/nested closures, generic callbacks, callback
fields, alternate closures with one signature, and explicit pointer sharing.
These helpers remain synchronous. Use the separate `mini.concurrent` APIs below
for native multithreading.

## Native concurrency

The first Kotlin-inspired threading subset uses libuv behind a small replaceable
adapter. `Thread.create(block)` creates an unstarted thread value; `start`, `join`,
`isAlive`, `close` and static `sleep` manage it. `Threads.thread() { ... }` starts
immediately and returns `Result<Thread, ConcurrencyError>`. Tasks are void lambdas.
`Mutex.create`, `lock`, `unlock`, `tryLock`, `withLock` and `close` provide consistent
non-recursive locking, with typed failures instead of exceptions.

```sh
xmake run mini0 build examples/threads.mini \
  --dep concurrent=std/mini/concurrent/thread.mini \
  --dep concurrent=std/mini/concurrent/mutex.mini -o build/threads
```

The example starts two workers behind an initially locked mutex, performs 20,000
synchronized increments, joins/releases both, and prints `42`. It also checks
invalid lifecycle operations, idempotent cleanup, busy mutexes and closed handles.
The runtime-adapter test injects allocation/start/condition failures, checks
copied captures, self-join and foreign-thread unlocks, and counts adapter allocations.

Thread and Mutex values are single owners by convention: do not copy live handles.
Borrowed task captures must outlive join/close. Join each successful start, close
mutexes after all users have finished, and synchronize shared writes. Lifecycle
operations on one owning handle must not race. The compiler does not enforce these
ownership/lifetime/data-race rules yet. This is OS threading, not a coroutine
scheduler or async event loop. See [the full API rules](../docs/language-v0.2.md#threads-and-mutexes).

## Native networking

`mini.net` adds a first blocking TCP subset over libuv, behind the same small
replaceable adapter the concurrency APIs use. `TcpListener.bind(port)` listens on
`127.0.0.1:port`; `accept()` blocks for one inbound connection. `TcpConnection`
`connectLoopback(port)` / `connect(host, port)` dial out (dotted-quad IPv4, no DNS
yet); `write`, `read` and `close` move bytes with typed failures. Each listener or
connection drives its own `uv_loop_t` to completion, so calls are synchronous and
need no event loop in user code. Combine with `mini.concurrent.Thread` for a
parallel server, exactly like the example.

```sh
xmake run mini0 build examples/net.mini \
  --dep net=std/mini/net/socket.mini \
  --dep concurrent=std/mini/concurrent/thread.mini \
  --dep concurrent=std/mini/concurrent/mutex.mini -o build/net
```

The example binds a loopback listener, accepts one connection on a worker thread,
exchanges a byte and prints `42`. `NetError` carries a typed `kind`
(`OutOfMemory`, `Refused`, `Reset`, `Closed`, `System`) plus the native libuv
`code`. Absence of a handle is `Optional<*u8>`; there is no nullable socket.

**Ownership and limits:** one owner per `TcpConnection`/`TcpListener`; close each
once. Close accepted connections before their listener (they share the listener's
loop). This is a selected synchronous subset: no DNS resolution, UDP, TLS, non-
blocking/async I/O, timeouts or back-pressure yet. The compiler does not enforce
socket ownership, lifetimes or data-race freedom. Like the concurrency adapter,
`requires_libuv` adds libuv link flags only to programs that use the net intrinsics.

> Kotlin-source standard library (v1-S): see [kotlin-mini/](kotlin-mini/) and [docs/stdlib.md](../docs/stdlib.md).

