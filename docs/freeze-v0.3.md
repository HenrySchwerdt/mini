# Mini v0.3 — freeze manifest

This document defines the **frozen surface** of Mini v0.3: the language and
standard-library features that are implemented, tested end to end, and intended to
stay source-compatible through the v0.3 line. It also states, honestly, what is
**not** in scope for this freeze so that "frozen" means a real boundary rather than
a claim of completeness.

A freeze fixes a surface; it does not assert that everything Kotlin has is present.
The authoritative behavioral description remains [language-v0.2.md](language-v0.2.md)
(the implemented language reference); this file records what v0.3 adds on top and
where the line is drawn.

## What v0.3 adds over v0.2

| Area | Addition | Status |
| --- | --- | --- |
| Generics | **Generic instance methods** (non-virtual), explicit type args, class+method type params in scope, interface/class bounds, value & pointer receivers, monomorphized per (receiver class, type args) | Implemented, tested (`examples/generic_methods.mini`) |
| Networking | **`mini.net`**: libuv-backed blocking TCP — `TcpListener` (`bind`/`accept`/`close`), `TcpConnection` (`connect`/`connectLoopback`/`write`/`read`/`close`), typed `NetError` | Implemented, tested over real loopback (`examples/net.mini`) |
| Math | **`mini.math.Math`**: `abs`, `min`, `max`, `clamp`, `sign`, `pow`, `gcd`, `lcm`, `isqrt` (integer) | Implemented, tested (`examples/math.mini`) |
| Tuples | **`mini.core.Pair<A,B>` / `Triple<A,B,C>`** generic value tuples with factories | Implemented, tested (`examples/pairs.mini`) |
| Ranges | **`mini.core.IntRange`**: `of`, `contains`, `count`, `sum`, `forEach((i32)->void)` | Implemented, tested (`examples/ranges.mini`) |

All additions keep Mini's existing invariants: no `null` (absence is `Optional`,
failure is `Result`, both via exhaustive `match`), class-only methods, explicit
memory management, value-type classes, and monomorphized generics (no erased
execution). The full suite (`xmake test`) is green: 14 targets, including every new
example built and run by the native CLI integration test.

## Frozen language surface

Stable and intended source-compatible for v0.3:

- Classes, interfaces, enums (payload-free), plain structs, annotation classes.
  `object.method()` / `pointer.method()` with implicit `this: *Self`; static methods
  via the class name. No top-level functions.
- `open`/`abstract`/`override`/`final`, single class inheritance, multiple interface
  implementation, interface default methods, `super`/`super<Interface>` dispatch via
  closed-world tags.
- `val`/`var` with inference and definite-initialization checking; optional
  semicolons; expression and block method bodies.
- Native `Optional<T>` and `Result<Res, Err>` with exhaustive, recursively-checked
  `match` and payload destructuring. No `null` literal.
- Generics on classes/interfaces/structs, static methods, and **now** non-virtual
  instance methods; explicit type arguments; interface/class bounds; monomorphization
  with instance caching and a finite expansion limit.
- Function types and lambdas with the documented restricted capture model
  (immutable scalar/enum snapshots and explicitly-captured immutable borrowed
  pointers), trailing-lambda-after-parens, implicit `it`.
- `Object` / `AllocatableObject` roots; `Type.allocate`/`Type.free` typed factories
  returning `Result<*T, AllocationError>`; recursive heap-residency checks.
- Packages, file-local imports (named/alias/non-recursive star), source-module graph
  with `--module`/`--dep`/`--depends`, `internal`/visibility enforcement.
- Scalar types `i32 i64 u8 usize isize bool`, pointers `*T`, `&`, `*p`, `cast<T>(…)`,
  `sizeof`, arrays, arithmetic, control flow.
- CLI: `check`, `build`, `emit-c`, `emit-llvm`; `--cc`/`MINI_CC`; libuv discovery via
  `--libuv-root`/`MINI_LIBUV_ROOT`.

## Frozen standard library

`mini.core` (`Value`, `Functions.run`/`repeat`, `Pair`/`Triple`, `IntRange`),
`mini.collections` (`Cell`, `Collection`/`List`/`MutableList`/`ArrayList` subset),
`mini.memory` (`Allocator`/`HeapAllocator`/`AllocationError`), `mini.io`
(`Console`/`ByteIO`/`IOError`), `mini.math` (`Math`), `mini.concurrent`
(`Thread`/`Threads`/`Mutex`/`ConcurrencyError`), `mini.net`
(`TcpListener`/`TcpConnection`/`NetError`). Ownership and lifetime obligations are
by convention and documented per module in [std/README.md](../std/README.md).

## Explicitly NOT in v0.3 (deferred to v0.4+)

These are known gaps. v0.3 does not implement them and does not pretend to:

- **Type system / generics:** type-argument inference for ordinary calls; variance;
  specialization; const generics; **virtual** generic methods; user-defined enums
  with payloads (sealed-class-style ADTs).
- **Core types:** floating point (`f32`/`f64`); `char`; wider integer set
  (`i8/i16/u16/u32/u64`); a real `String`/`StringBuilder`/text, formatting and
  parsing (output is still `print_i32`).
- **Collections:** `Map`/`HashMap`, `Set`/`HashSet`, `Iterable`/`Iterator`, and the
  higher-order `map`/`filter`/`fold` surface (the generic-instance-method mechanism
  now exists, but these APIs are not written yet).
- **Memory safety:** ownership/move/drop checking, borrow lifetimes, leak /
  double-free / use-after-free detection, allocator-pairing enforcement, pointer
  constness, full aggregate/alias initialization analysis, RAII/`use`/destructors.
- **Ergonomics:** error-propagation operator; data classes
  (`equals`/`hashCode`/`copy`/`componentN`) and destructuring declarations;
  extension functions/properties; operator overloading; properties with get/set,
  `lazy`, top-level constants, companion objects; default/named arguments, `vararg`;
  `var`/aggregate/closure captures, function references, labelled/non-local returns,
  `run { }` without parentheses, receiver lambdas.
- **Concurrency:** atomics, thread-local storage, public condition variables, timed
  joins, executors/thread pools, structured concurrency, coroutines/`async`/`suspend`;
  non-void `withLock` (the language blocker is gone, but the std API is unchanged).
- **Networking:** DNS resolution, UDP, TLS, non-blocking/async I/O, timeouts,
  back-pressure.
- **Backend / runtime:** direct LLVM/object emission (still lowers through C17 +
  Clang); removal of libc/libuv/startup runtime dependencies; a freestanding target.
- **Tooling / distribution:** module binary metadata, a package manager/versioning,
  a Mini-native language server/diagnostics/formatter, and a self-hosted compiler.
- **Platforms:** only macOS arm64 is verified here; the Linux/Windows code paths and
  CI matrix exist but have not been run.

## Freeze guarantees and caveats

- **Tested, not proven.** Correctness rests on the regression/integration suite
  (166+ language cases plus the native CLI suite, with ASan/UBSan and ThreadSanitizer
  on the threading example), not a formal proof or a measured coverage number.
- **Raw-pointer model is not a safety guarantee.** The deferred memory-safety items
  above are real; programs can still violate pointer/ownership contracts.
- **ABI is not frozen.** The closed-world tag/capture lowering and generated C are
  implementation details, not a stable external callback or object ABI.
