# 3. Arenas and Scoped allocators

An `Arena` hands out memory by bumping a pointer and releases **all** of it when it is closed. There is no per-object free, so there is nothing to forget and nothing to double-free. In exchange the compiler enforces a **lifetime rule**: an object allocated in an arena must not leave the arena's scope.

## Using an arena

<<< @/samples/memory/arena_copy_out.kt{kotlin}

`Arena().use { arena -> ... }` closes the arena at the end of the block. `context(arena) { ... }` makes it the allocator for everything inside. The `Int` result is plain data, so it may leave; the `Point` objects stay inside. The checker accepts the program, which prints `12` on the JVM.

An arena is a resource itself: forgetting to close it (no `use {}` and no `close()`) is a leak, [M0102](/reference/diagnostics/M0102).

## What may not leave

<<< @/samples/memory/arena_escape.kt{kotlin}

This is rejected with [M0105](/reference/diagnostics/M0105): the `Box` lives in `arena`, which is gone once `build` returns. The same error fires for:

- the result of the `use {}` block or of `context(a) { }` when it is used outside,
- storing an arena object in a field of a longer-lived object or in a global,
- capturing it in a lambda that outlives the arena.

What is allowed: passing arena objects **inward** (as arguments to borrowed parameters), copying out values that need no allocation, and a function declared with `context(a: Arena)` returning arena objects to its caller. In that case the region is the caller's, and the check continues there.

## Arena objects cannot be freed one by one

`b.free()` on an arena object is [M0106](/reference/diagnostics/M0106), and so is passing it to an `@Owned` parameter, because the callee could free it.

## Arenas never run destructors

On close, the arena releases its memory and nothing else: it does not call `close()` on the objects and does not release their fields, which is what makes it cheap. Therefore an arena object may only own primitives, string literals and objects of the same arena. If a reference-counted `String`, a Managed object or a heap object flows into an owning field, the compiler reports [M0115](/reference/diagnostics/M0115). A field marked `@Borrowed` is non-owning and allowed; you then guarantee its lifetime yourself.

## Allocation in a loop

<<< @/samples/memory/arena_loop.kt{kotlin}

This is accepted but produces warning [M0202](/reference/diagnostics/M0202): every iteration allocates a new object that is only reclaimed when the arena closes. Hoist the allocation out of the loop or recreate the arena per iteration. Warnings do not fail the build unless you pass `-Werror`.

Next: [Strict mode](./strict).
