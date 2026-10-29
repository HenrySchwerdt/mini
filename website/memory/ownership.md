# 5. Ownership

Every tracked value has exactly one **owner**: a local variable, a parameter or a field. The owner is responsible for releasing it. Everyone else holds a **borrow** that is valid only while the owner is.

## Defaults

| Position | Default | To change it |
|---|---|---|
| Function parameter | borrowed | `p: @Owned T` takes over ownership |
| Function return | owning | `: @Borrowed T` returns a loan |
| Property parameter of a constructor, and fields | owning | `@Borrowed` makes a field a non-owning back reference |
| Local initialized with a fresh value (`Foo(...)`, a call with owning return) | owner | |
| Local initialized from an existing owner (`val b = a`) | alias (a loan) | `a.move()` makes `b` an owner |
| `this` and extension receivers | borrowed | |

## Lend or give

<<< @/samples/memory/borrowed_param.kt{kotlin}

`peek` borrows: it may read `b` but not free it, store it or return it as owned. `consume` is declared `@Owned`, so the call `consume(b)` **moves** `b`: afterwards `b` is gone, and `consume` has to free it. The checker accepts the program.

Freeing something you only borrowed is an error:

<<< @/samples/memory/free_borrowed.kt{kotlin}

[M0107](/reference/diagnostics/M0107). The fix is `fun drop(b: @Owned Box)`. Storing or returning a borrowed value as owned is [M0109](/reference/diagnostics/M0109).

## Ending ownership: `free()`, `use {}`, `close()`

- `x.free()` is the one main notation. It calls `close()` if `x` is `AutoCloseable`, then releases all fields that `x` owns (recursively), then returns the memory to the allocator it came from. It works on nullable values (`null` is a no-op). `alloc.free(x)` is the same with the allocator named, and a mismatch is [M0108](/reference/diagnostics/M0108).
- `x.use { }` calls `close()` at the end of the block on every path (also `throw`, `return`, `break`) and ends the ownership of `x`. Inside the block the value is borrowed.
- `x.close()` on an owner counts like `free()`.

<<< @/samples/memory/use_closeable.kt{kotlin}

A `close()` body must not free owned fields itself: the compiler releases them after `close()` returns. The checker accepts the program; on the JVM it prints `using a`, `close a`, `open b`, `close b`.

## Moves

A plain assignment only borrows. Ownership moves **implicitly** only at: `return a` from a function with an owning return, an argument for an `@Owned` parameter (including constructor property parameters), assignment to an owning field, capture in a thread lambda, and `use`/`close`. Everywhere else you write `a.move()`.

<<< @/samples/memory/move.kt{kotlin}

Using the old name after a move is an error:

<<< @/samples/memory/use_after_move.kt{kotlin}

[M0104](/reference/diagnostics/M0104). If no transfer was intended, write `val b = a` (an alias) instead. An alias used after its owner was freed or moved is reported as [M0101](/reference/diagnostics/M0101) or M0104 at the use of the alias.

## Borrowed results

`fun first(h: Holder): @Borrowed Box?` returns a loan that is valid until the owners it derives from (the receiver and all borrowed arguments) are freed, moved or overwritten. The checker has no lifetime variables beyond that: a loan hangs on the receiver and all borrowed arguments.

## Nullable owners

`T?` is owning like `T`. `null` carries no obligation, and `free()` on `null` does nothing. A branch that leaves `Null` and another that leaves `Owned` join into a state in which `free()` is allowed and `if (x != null)` removes the obligation. Discarding the result of a function that returns `@Owned T?` is a leak even though the value might be `null` at run time.

Next: [Fields and containers](./fields).
