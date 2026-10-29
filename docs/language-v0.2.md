# Mini v0.2

This describes the implemented bootstrap language. Mini has Kotlin-inspired
inheritance and module visibility, explicit memory management, and built-in
`Optional<T>` / `Result<Res, Err>` types. It is not Kotlin or a JVM language.

## Classes and methods

```mini
interface Value<T> { get(): T }

class Box<T> : Value<T> {
    public val value: T

    public override get(): T = this.value
}

class Main {
    static identity<T>(value: T): T = value

    public static main(): i32 {
        val box = Box<i32> { value: 42 }
        print_i32(Main.identity<i32>(box.get()))
        return 0
    }
}
```

Methods are recognized by `name(...)` or `name<T>(...)`; `fn` is unnecessary.
The parser still accepts the legacy introducer inside a class. **Top-level
functions are rejected by project analysis**, including generic functions.
Top-level declarations are classes, interfaces, enums, annotation classes and
plain data structs. Structs cannot contain methods or inherit.

Instance methods automatically receive `this: *Self`. Do not declare a receiver
parameter. `this.field` accesses an instance field; `this.method()` calls another
method. `this` cannot be reassigned, shadowed or used in a static method. `Self`
can still be used as a type in class signatures. Static methods have no receiver
and are called through their class, such as `Main.identity<i32>(42)`.

A class is a nominal **value type**. A literal `Box<i32> { value: 42 }` initializes
stack storage without a heap allocation. `*Box<i32>` is a pointer to that storage.
`box.get()` automatically borrows the address of the original value; it does not
copy the receiver. Pointer receivers use the same `pointer.get()` syntax.
Temporary receivers are materialized for the call; pointers escaping such a
receiver have no lifetime protection yet. Passing a pointer argument still requires
an explicit address such as `consume(&box)`.
Fields can be separated by commas, semicolons or line breaks. Use `val` for an
immutable field binding and `var` for a mutable field. Field types remain explicit.
There are no implicit constructors, automatic heap allocation or garbage collection.
Factories are ordinary static methods and may initialize private fields.

Native executable entry is a non-generic `Main` class with a static zero-argument
`main()` returning `i32` or `void`. Library source sets can be checked/emitted as C
or LLVM IR without an entry point.

## Locals and expression bodies

`val name = expression` infers a local type and prevents reassignment. `var` allows
reassignment. An explicit type can be supplied (`val count: usize = 0`) and is
required if initialization is deferred. The checker tracks both definite
initialization and possible assignment across branches and match arms, so a
partially initialized `val` cannot be assigned twice on a reachable path.

A `val` class value has stable storage but mutable instance state: `box.set(42)`
and writes to `var` fields are allowed. Its binding cannot be replaced. A scalar
`val`, a `val` pointer binding, and scalar `val` fields cannot expose mutable
addresses. An immutable pointer binding can still point to mutable storage.
Method parameters are immutable bindings. The raw pointer/cast model is not an
ownership or alias-safety guarantee.

Methods use `method(): Type` and can use expression bodies:
`static identity<T>(value: T): T = value`. Expression-bodied methods require an
explicit return type; `: void = print_i32(42)` is valid. Block-bodied methods may
omit a return annotation to return `void`. Legacy `-> Type` is also accepted.

Semicolons are optional at line boundaries or before `}`/EOF. Multiple simple
statements on one line need semicolons. A complete outer expression stops at a
newline; grouped expressions, argument/index lists, trailing operators and leading
member-access dots continue. `return` followed by a newline is a bare return.
Match arms can use commas or line breaks. See [the syntax rules](syntax-direction.md)
for the full continuation and `val` rules.

## Optional pointers; no null literal

`null` is not a Mini value, literal, type or identifier. A pointer `*T` represents
a present address. Absence uses `Optional<*T>`, constructed with `Optional.None`
or `Optional.Some(pointer)` and handled with exhaustive `match`.

```mini
val allocation = heap.alloc(64) // Optional<*u8>
match allocation {
    Some(memory) => heap.free(memory)
    None => print_i32(-1)
}
```

`heap.alloc(bytes)` returns `Optional<*u8>`; allocation failure or a zero-byte
request returns `None`. `heap.realloc(pointer, bytes)` also returns `Optional<*u8>`.
On `None`, including a zero-byte resize, the original allocation remains valid and
owned by the caller. On `Some(replacement)`, use only the replacement pointer.
`heap.free` requires an unwrapped `*u8`. These are low-level runtime intrinsics;
the std `Allocator.allocate` and object factories expose typed `Result` errors.
Empty collection buffers and optional allocator choices also use Optional values.

Known zero-address casts are compile errors. Pointer casts involving dynamic
addresses check for a zero address and trap instead of creating an absent `*T`.
An extern method returning `*T` declares a non-zero-address contract, checked on
return; an adapter for a nullable foreign API must explicitly translate its result
to Optional. C/runtime internals may still use C's `NULL` while performing that
translation. This removes nullable pointers from the normal Mini surface; it does
not prove raw memory validity, bounds, ownership or lifetimes, and unsafe foreign
code or byte-level mutation can still violate pointer contracts.

See [the allocation/resize example](../examples/optional_memory.mini).

## Function types and lambdas

Function values have explicit signatures: `(i32) -> i32`, `() -> void`, or
`(i32, bool) -> Result<i32, Error>`. Parameter and return types must match exactly;
there is no function-type variance or implicit numeric conversion between function
signatures. Function values can be local bindings, parameters, return values,
record fields and native Optional/Result payloads. Invoke them with `callback(value)`.

```mini
class Main {
    static adder(offset: i32): (i32) -> i32 = { value -> value + offset }

    static apply(value: i32, action: (i32) -> i32): i32 = action(value)

    static main(): i32 {
        val addOne = Main.adder(1)
        val answer = Main.apply(41) { it + 1 }
        return addOne(answer - 1)
    }
}
```

Lambda parameters use `{ x: i32, y: i32 -> x + y }`. Parameter annotations may be
omitted when a function-type context supplies them. A contextual one-parameter
lambda without a parameter header receives implicit `it`. Without a context,
a lambda without a header takes zero arguments. The final expression determines
the result; a contextual `void` lambda discards its final expression value.
Multiple statements and nested lambdas are supported. Explicit `return` exits the
lambda locally and requires a contextual function type. Without a context, parameter
types must be written and the final expression's return type is inferred.

A final lambda argument can follow a parenthesized call: `Helpers.repeat(3) { i -> ... }`.
Other lambda arguments go inside the argument list. Calls without the parentheses,
like `Helpers.run { ... }`, are not supported yet. Names of ordinary methods are
not function values; use a lambda to call them. There are no function references,
receiver lambdas, labelled/non-local returns or suspend functions yet.

Closures copy only the immutable scalar/enum bindings they actually use. Their
capture storage is inline in the function value, so returning such a closure does
not borrow a dead activation frame or implicitly allocate on the heap. Copying a
closure copies these snapshots. Mutable `var` captures, aggregate/class captures
and captures of other function values are rejected in this first implementation.
To share mutable data, explicitly capture an immutable borrowed pointer binding:

```mini
var total: usize = 0
val output = &total
Functions.repeat(7) { index -> *output = *output + index }
```

The caller must keep borrowed pointees alive for all callback invocations. Captured
`this` is also a borrowed pointer. There is no borrow-lifetime or thread-safety proof,
and capturing a pointer does not extend the pointee's lifetime. These capture rules
are deliberately narrower than Kotlin's shared mutable closures.

The bootstrap uses closed-world tags and inline capture unions for each concrete
function signature. This is not a stable external callback ABI; runtime adapters
will need explicit trampolines. The class-scoped std helpers `mini.core.Functions.run<R>`
and `Functions.repeat` are implemented in `std/mini/core/functions.mini`.
See [the executable example](../examples/lambdas.mini).

## Threads and mutexes

`mini.concurrent.Thread`, `Threads`, `Mutex` and `ConcurrencyError` provide the
first native concurrency API. Threads execute `() -> void` lambdas on actual OS
threads through libuv; they do not need an event loop. This follows the shape of
[Kotlin's thread helper](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.concurrent/thread.html)
with class-scoped helpers, explicit ownership and typed failures. It is a selected
native subset, not compatibility with the JVM Thread API or Kotlin coroutines.

```mini
val task = Thread.create({ print_i32(42) })
val started = task.start() // Result<bool, ConcurrencyError>
val joined = task.join()  // handle errors with match
val closed = task.close()
```

`Thread.create(block)` creates an unstarted value without runtime resources.
`start()` allocates/copies the closure and starts it. `Threads.thread() { ... }`
combines those steps and returns `Result<Thread, ConcurrencyError>`. An unsuccessful
start frees partial allocations and leaves the Thread retryable. `Thread.sleep`
accepts milliseconds as `usize`. `isAlive()` reports whether a started callback has
not yet returned; its completion flag is atomic.

A Thread may start once. Joining before start returns `NotStarted`; restarting
returns `AlreadyStarted`. Successful `join()` waits, frees the native handle and
capture buffer, and returns `Ok(true)`; repeated joins return `Ok(false)`.
`close()` joins if needed, closes an unstarted value without running its task,
and is idempotent. After close, start/join return `Closed`. A self-join returns
`Busy` instead of deadlocking. Errors preserve live handles for a retry.

`Mutex.create()` returns `Result<Mutex, ConcurrencyError>`. `lock`, `unlock`,
`tryLock`, `withLock` and `close` return `Result<bool, ConcurrencyError>`.
`tryLock` returns `Ok(false)` when busy. `withLock() { ... }` accepts a void lambda,
runs it only after acquiring the lock and unlocks on normal lambda return. Mutexes
are non-recursive on every target: recursive acquisition returns `Busy`.
Unbalanced or foreign-thread unlocks return an error. Closing a held/waited-on
mutex returns `Busy`, keeping it valid; repeated successful close returns
`Ok(false)`. This adapter uses libuv mutex/condition primitives so the semantics
are independent of native recursive-mutex differences.

`ConcurrencyError` carries a typed `kind` plus the native libuv `code` when one
exists. Kinds include `OutOfMemory`, `AlreadyStarted`, `NotStarted`, `Closed`,
`Busy` and `System`. No exceptions or nullable Mini handles are used.

**Ownership and sharing:** keep exactly one owning Thread/Mutex value; the compiler
does not enforce moves or prevent copied handles. Join or close every successfully
started thread. Finish all workers/waiters before closing their mutex. A borrowed
Mutex may be locked/unlocked concurrently; other lifecycle methods, including
Thread start/join/close and Mutex close, require externally coordinated ownership.
Do not call close concurrently with new operations. Copying a task snapshots its
scalar captures; pointer captures (including captured `this`) stay borrowed and
must outlive completion. Shared mutable data requires synchronization. The compiler
still does not prove data-race freedom, borrow lifetimes or leak freedom.

The compiler embeds a small C adapter and reports `requires_libuv` in project
results. The driver adds resolved libuv headers/link flags only when needed; plain
programs retain no libuv runtime dependency. xmake pins a static libuv package.
`--libuv-root PREFIX` or `MINI_LIBUV_ROOT` overrides header/library search paths
for a compatible installation; an invalid explicit header prefix fails. C output
is self-contained apart from standard/libuv headers. Manually compiling emitted
C requires the corresponding libuv link flags; `emit-llvm` needs its headers only.

Daemon threads, names/priorities, interruption, timed joins, public condition
variables, atomics, thread-local storage, executors and coroutines are not yet
implemented. `withLock` is void-only until generic instance methods exist.
See [the executable parallel example](../examples/threads.mini) and the
[libuv threading contract](https://docs.libuv.org/en/v1.x/threading.html).

## Networking

`mini.net` provides a first blocking TCP subset over libuv, behind the same small
replaceable adapter the concurrency APIs use. It follows the shape of a synchronous
socket API with explicit ownership and typed failures; it is not an async event
loop exposed to user code.

```mini
val listener = TcpListener.bind(34567)      // Result<TcpListener, NetError>
val server = listener.accept()              // blocks for one connection
val client = TcpConnection.connectLoopback(34567)
client.write(payload, length)               // Result<usize, NetError>
client.read(buffer, capacity)               // Result<usize, NetError>
client.close()
```

`TcpListener.bind(port)` listens on `127.0.0.1:port`; `accept()` blocks for one
inbound connection. `TcpConnection.connectLoopback(port)` and `connect(host, port)`
dial out (dotted-quad IPv4, no DNS yet). `write`/`read` move bytes and `close`
releases the handle. Each listener/connection drives its own `uv_loop_t` to
completion, so calls are synchronous; combine with `mini.concurrent.Thread` for a
parallel server (see [the example](../examples/net.mini), which prints `42` over a
real loopback exchange). `NetError` carries a typed `kind` (`OutOfMemory`,
`Refused`, `Reset`, `Closed`, `System`) plus the native libuv `code`. A handle's
absence is `Optional<*u8>`; there is no nullable socket.

The compiler recognizes `runtime.tcp*` intrinsics, embeds a small `static` libuv
adapter only when they are used, and reports `requires_libuv` so the driver adds
libuv link flags exactly as for threading. Ownership is single-owner by convention
(close each handle once; close accepted connections before their listener). DNS,
UDP, TLS, non-blocking/async I/O, timeouts and back-pressure, plus socket
ownership/lifetime/data-race enforcement, are not yet implemented.
See [the std networking rules](../std/README.md#native-networking).

## Inheritance and interfaces

- Classes and ordinary methods are final by default. `open class` permits one
  class superclass; `open` on a method permits overrides.
- `abstract class` / `abstract method()` declare incomplete implementations.
  Abstract classes and interfaces cannot be stored or constructed by value.
- A class may implement multiple interfaces. Interfaces can inherit interfaces
  and supply default methods, but cannot store fields.
- An override requires `override` and a matching signature. It remains open
  unless marked `final override`. Static methods are not virtual.
- Cyclic inheritance, multiple class superclasses, extending a final class,
  missing abstract methods and incompatible overrides are errors.
- Calls through `*Base` and `*Interface` dispatch to the actual concrete class.
  `super.method()` calls the base implementation directly. `super<Interface>.method()`
  explicitly selects a direct interface default. Conflicting defaults require
  an explicit override.
- Pointer upcasts are non-owning. Derived-to-base value conversion is rejected.
  Copying/replacing a polymorphic object through a base pointer is also rejected
  to avoid slicing its hidden dispatch metadata.

The bootstrap currently lowers virtual calls using closed-world object tags and
switch dispatch. This provides the language semantics without exposing a vtable
ABI. An interface pointer is a borrowed view of existing class storage. It never
owns or frees an object. Keep the original allocation handle for explicit cleanup.

## Object roots and typed allocation

Every class without an explicit class superclass implicitly extends the built-in
`mini.core.Object`. Classes that only name interfaces also receive this root.
`Object` and `AllocatableObject` are public, open built-in classes, available without
imports; their short names are reserved. Interfaces, enums and plain structs do
not acquire class superclasses.

```mini
class StackView { public val value: i32 } // implicit : Object
open class Point : AllocatableObject { public var x: i32 }
class NamedPoint : Point { public val name: i32 }
```

`Object` supplies no heap-allocation capability. `AllocatableObject : Object`
adds compiler-specialized `allocate` and `free` factories to its descendant
classes. An allocatable class may still be created on the stack. A family's
allocation capability follows its single class-inheritance chain; adding an
interface cannot grant it. Class and interface bounds are both accepted, for
example `T: AllocatableObject`.

```mini
val stack = Point { x: 41 }
val result = Point.allocate(Point { x: 42 })
match result {
    Ok(pointer) => Point.free(pointer)
    Err(error) => print_i32(-1)
}
```

`T.allocate(value)` returns `Result<*T, mini.memory.AllocationError>` using the
default heap. `T.allocate(value, allocator)` borrows a `*mini.memory.Allocator`;
free with `T.free(pointer, allocator)` using the same allocator instance. Include
`std/mini/memory/allocator.mini` as a source dependency. A custom allocator must
return correctly aligned storage of the requested size or a typed failure.
An allocator's `Err` is preserved. Allocator pointers must be present;
absence is represented by omitting the optional factory argument. `T.free(pointer)` releases a default-heap allocation.
Factories require the exact concrete class value/pointer, allocate its full
layout and preserve its dispatch tag; they reject slicing through a base view.
The names `allocate` and `free` are reserved throughout allocatable class families.

Heap-residency checks recursively inspect inline class fields, arrays and native
Optional/Result payloads. A heap object cannot embed a stack-only class value.
Pointer fields are borrowed references and do not embed their pointees. Casts
from raw storage/integer addresses into pointers to stack-only values are rejected;
ordinary typed pointer upcasts remain permitted. Thus an `ArrayList<T>` buffer
requires heap-compatible `T`; class elements must extend `AllocatableObject`.

**Lifetime limitation:** these factories return raw pointers. The compiler does
not yet prove ownership, allocator identity, leaks, double-free, use-after-free or
borrow lifetimes. Never free stack addresses, free each successful allocation
once with its matching allocator, and finish using borrows before freeing. `free`
releases only the outer storage; it does not run destructors or recursively close
resources. Raw runtime/extern interop is unsafe and is not a sandbox. Explicit
resource cleanup remains necessary, including `ArrayList.close()` before freeing
an allocated list object. Ownership/move/drop checking is a separate unfinished
compiler feature.

See [the native object example](../examples/objects.mini).

## Visibility

| Modifier | Visibility |
| --- | --- |
| `public` (default) | Any consumer that can see the containing declaration |
| `private` member | Declaring class only |
| `protected` member | Declaring class and derived classes |
| `internal` | Declaring compilation module only |
| Top-level `private` | Declaring source file only |

`protected` is invalid at top level. Imports never bypass visibility. An override
inherits omitted visibility and may widen it, but cannot reduce it. Public APIs
cannot expose internal/private types. This applies to field and method signatures
and generic bounds. There is no reflection escape hatch.

## Generics

Classes, interfaces, structs and **static methods** can have type parameters.
A bound names an interface: `static read<T: Value<i32>>(value: *T) -> i32`.
Instantiations are checked and monomorphized; no erased `void*` generic execution
is used. Type identity includes the declaration and concrete arguments. Instances
are cached, recursion reuses existing instances, and expansion has a finite limit.

```mini
class Tools {
    static read<T: Value<i32>>(value: *T) -> i32 {
        return value.get();
    }
}
```

Use explicit arguments for generic methods (`Tools.read<Box<i32>>(&box)`).
The previous `Tools.read::<Box<i32>>(&box)` spelling also remains valid.
Nested applications such as `Box<Optional<i32>>` are supported. Names and type
signatures of unused templates are checked too; type-dependent body operations
are checked when instantiated.

**Generic instance methods** are supported for plain (non-virtual) instance
methods, with explicit method type arguments at the call site:

```mini
class Box<T> {
    public val value: T
    public map<R>(f: (T) -> R): R = f(this.value)
}

// box.map<i32>({ it * 2 })
```

A generic instance method may appear on a generic class (its class parameter `T`
and the method parameter `R` are both in scope in the body), on a non-generic
class, or inherited from an `open` base class. Method type parameters may carry
interface/class bounds (`areaOf<S: Shape>(s: S)`), checked on instantiation. Both
value receivers (auto-borrowing `this`) and pointer receivers work. Each distinct
(receiver class, method type arguments) combination is monomorphized once.

The method itself must be final: a generic instance method that is `open`,
`abstract`, `override` or an interface member is rejected, because virtual generic
dispatch is not modeled by the closed-world tag scheme yet. Type arguments must be
written explicitly; omitting them is an error. Variance, specialization, const
generics, virtual generic methods and type-argument inference remain deferred.

## Native Optional and Result

These names are built into the compiler and cannot be redeclared:

| Type | Variants |
| --- | --- |
| `Optional<T>` | `Some(T)`, `None` |
| `Result<Res, Err>` | `Ok(Res)`, `Err(Err)` |

Both are inline tagged values. Constructing one neither allocates nor boxes.
The expected type supplies type arguments in a variable initializer, return,
method argument or record field. Explicit constructors are also supported:

```mini
class Parsing {
    static answer(found: bool) -> Result<Optional<i32>, i32> {
        if found { return Result.Ok(Optional.Some(42)); }
        return Result.Err(1);
    }
}

class Main {
    static main() -> i32 {
        let explicit: Optional<i32> = Optional<i32>.Some(42);
        let result: Result<Optional<i32>, i32> = Parsing.answer(true);
        return match result {
            Ok(Some(value)) => value,
            Ok(None) => 0,
            Err(error) => error,
        };
    }
}
```

Pattern bindings are mutable, arm-local copies. `_` discards a payload or acts as
fallback. Qualified patterns (`Optional.Some(value)`, `Result.Err(error)`) also
work. Matching evaluates its subject once. The compiler checks payload types,
arm result types, duplicate/unreachable arms, and recursive exhaustiveness,
including cases such as `Some(true)`, `Some(false)`, `None`. Integer matches need
a catch-all. Arbitrary user-defined enums currently remain payload-free.

The standard-library foundation uses `Result<*u8, AllocationError>` for fallible allocation and
`Result<usize, IOError>` for byte I/O. There is no nullable-reference subsystem or
implicit error propagation operator yet; use `match` explicitly.

## Packages, imports and modules

```mini
package demo.app;
import mini.collections.Cell;
import mini.io.Console as Output;
import mini.core.*;
```

A package is a namespace. A module is a named set of source files compiled
together with declared dependencies. Files are collected before declaration/body
resolution, so file order does not determine visibility. A package header is
optional (root package), and directory layout is a convention rather than syntax.
Imports are file-local. Named imports, aliases and non-recursive star imports are
supported. Ambiguities are errors; explicit imports win over star imports.

The CLI uses `--module app`, repeated `--dep name=file.mini` and
`--depends from=to` to describe a source-module graph. Dependencies are direct,
not automatically re-exported. Cycles in the module graph are rejected. Mutually
referencing files inside one module are allowed. Imports do not perform textual
inclusion, run initialization or fetch packages from the network.

Compilation currently consumes dependency sources together. Serialized module
metadata, separately distributed generic libraries and a package manager are
future work. The module boundary and `internal` checks already apply to the
source graph.

## Annotations

Annotations precede declarations or fields. User-defined annotation classes
contain primitive literal parameters in field order:

```mini
annotation class Since { version: i32, }

@Since(2)
class Counter {
    @Deprecated("Use current instead")
    old() -> i32 { return 1; }

    current() -> i32 { return 2; }
}
```

Unknown annotation names, incorrect counts and mismatched literal types are errors.
Custom annotations are validated compile-time metadata; they have no automatic
runtime behavior or code-generation hooks. `@Deprecated("message")` is built in
and produces a diagnostic when an annotated class/method is used.

`@Extern("symbol")` selects the C ABI symbol for an explicitly `static extern`
method. Names must be C identifiers; the annotation is not injected as C code.
Only concrete signatures are supported, and they must match the linked C ABI.
The current C lowering includes standard C headers, so signatures for standard
library symbols must also agree with those headers (including pointer constness).

## Memory and compiler boundaries

`val` declares an immutable binding; `var` declares a mutable binding. Legacy
`let` remains mutable. Reads require definite initialization. Conditions
are `bool`; `&&` and `||` short-circuit. Assignment has type `void`. Arrays, pointers,
casts, `sizeof`, arithmetic, enums, blocks, conditionals and loops remain
available. Array bounds and raw pointer validity remain programmer obligations.

Classes and payload values have copy semantics. Resource-owning pointers are not
automatically moved or destroyed. Do not shallow-copy owners and free both copies.
The small std foundation avoids claiming a completed ownership model or an owning
`Vec<T>` / string API. Pointer constness and full aggregate/alias initialization
analysis remain future work; initialization checks are deliberately conservative.

The implemented path is:

```text
module source graph -> lexer/parser -> AST -> visibility/type/override checking
                    -> concrete type and method instantiation -> C17 intermediate
                    -> Clang/LLVM -> native executable or LLVM IR
```

The new project API is `mini_compile_project`. It returns owned C source and
source-located diagnostics. Frontend/lowering contains no LLVM handles or host
SDK paths. Native layouts are determined by the target C compiler. The driver
launches compiler processes with argument arrays, without a shell, and uses an
exclusively created temporary intermediate. The direct LLVM C API backend remains
an optional, unfinished alternative; the working CLI does not pretend to use it.

The legacy single-file checker remains available for its v0.1 regression tests;
it is not the v0.2 project compiler. Future backends can consume the concrete
lowering data without changing lexical syntax or import resolution.
