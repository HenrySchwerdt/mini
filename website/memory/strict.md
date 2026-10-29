# 4. Strict mode

Put this at the top of a file:

```kotlin
@file:MemoryMode(Mode.Strict)

import mini.memory.*
```

In **Strict** mode there is no reference-counting fallback. Every allocation needs a proven owner, and every owner must release it exactly once on every path, or hand it on. If the compiler cannot prove that, the build fails. `@MemoryMode(Mode.Managed)` on a single class overrides the file for that class.

## What is tracked

The checker follows references to instances of classes **declared in a Strict file or class**, plus `AutoCloseable` allocators (`Arena`, `Pool`). Primitives, `String`, objects of Managed classes, function values without tracked captures, and `Heap`/`Rc`/`DebugAllocator` are not tracked and carry no obligations.

A tracked reference is in one state at each program point: owned, null, freed, moved, or a union of those at a merge point. Each function is checked from its own body and the signatures of its callees; nothing is inferred across function boundaries.

## The leak from the front page

<<< @/samples/memory/leak.kt{kotlin}

[M0102](/reference/diagnostics/M0102): `buf` is still owned when `load` returns `null`. The diagnostic names the path (`early return`). The fix is to release on that path as well:

<<< @/samples/memory/leak_fixed.kt{kotlin}

The checker accepts this version. The checker also stops one cause from producing a cascade: after it reports a problem about a variable, it assumes the variable is in the state that makes the next step consistent, so you get one message per mistake.

## Using a value after it is freed

<<< @/samples/memory/use_after_free.kt{kotlin}

Rejected with [M0101](/reference/diagnostics/M0101). Freeing twice is [M0103](/reference/diagnostics/M0103).

## Control flow is followed

The checker builds a control-flow graph per function. At a merge point (`if`, `when`) the states are joined, so a value freed in one branch only is still a leak, or a possible use after free, afterwards. Loops are handled as a fixed point: an owner created per iteration must be released in every iteration, and an owner from outside the loop must not be moved or freed in the body unless the body restores it. `finally` bodies are copied into every exit path, so a release in `finally` covers `return`, `break` and exceptions.

Exception edges are an approximation: they only come from `throw`, from `error`/`require`/`check`/`requireNotNull`/`checkNotNull`/`TODO`, and from calls of functions in the same Strict module that contain a `throw`. An ordinary call, a division or an array access does not create an exception edge, otherwise all code would count as leaking.

## Strict and Managed code

The two modes mix in one program. Managed objects seen from Strict code are *borrowed with external lifetime*: you never free them. The rules at the boundary:

| Direction | Rule |
|---|---|
| Strict passes an untracked value (`Int`, `String`, Managed object) to Managed code | allowed |
| Strict passes a tracked value to a Managed function | only if the parameter is `@Borrowed` (a promise by the author, not checked yet), otherwise [M0110](/reference/diagnostics/M0110) |
| Strict stores a tracked value in a field of a Managed class | [M0110](/reference/diagnostics/M0110) |
| Strict puts a tracked value in a Kotlin collection | [M0110](/reference/diagnostics/M0110) |
| Managed calls a Strict function with `@Owned` parameters or an owning return | [M0110](/reference/diagnostics/M0110): Managed code cannot keep the promise. Write a Strict wrapper that frees internally |

A mixed program, which is built and run natively in CI:

<<< @/samples/memory/mixed_modes.kt{kotlin}

<<< @/samples/memory/mixed_modes.out{text}

`Counter` is Managed, so it has no obligations; `Vec` is a Strict class, but it never escapes and is only passed to a borrowed parameter, so no allocator is needed.

Putting tracked values into collections is rejected today:

<<< @/samples/memory/collection_boundary.kt{kotlin}

This is [M0110](/reference/diagnostics/M0110). Collections of owned values are not supported yet; see [Fields and containers](./fields).

## Strict code that is not allocator-heavy

In Strict mode a function that never needs a heap allocation is just Kotlin. Allocation sites that do not escape need no allocator at all, which is why the `Vec` above compiles without a `context`.

Next: [Ownership](./ownership).
