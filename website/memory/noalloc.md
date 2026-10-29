# 9. `@NoAlloc`

`@NoAlloc` on a function (or a property getter or setter) says that its body must not allocate.

<<< @/samples/memory/noalloc.kt{kotlin}

This is a Strict file, but it needs no allocator: `Vec` does not escape. The program builds and runs natively in CI:

<<< @/samples/memory/noalloc.out{text}

An allocation that escapes, or a call of `alloc.allocate(...)`, is an error:

<<< @/samples/memory/noalloc_error.kt{kotlin}

[M0205](/reference/diagnostics/M0205).

## Limits

- Only the body of the annotated function is checked. Calls to other functions are not followed yet (that needs function summaries and is planned).
- Only allocations that actually are not on the stack count; non-escaping objects are fine.
- A warning for allocations in hot paths outside `@NoAlloc` is planned.

This is the last page of the guide. Look up any code in the [diagnostics reference](/reference/diagnostics/), or read how the checker works in [Memory checker](/internals/checker).
