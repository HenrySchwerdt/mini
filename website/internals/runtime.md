# The runtime: libminirt

`libminirt` is a small C17 runtime in `runtime/`. `include/minirt.h` is the ABI contract with the C emitter; generated code includes only that header. There is no separate library build: `minic` compiles `runtime/src/*.c` together with each program.

| File | Contents |
|---|---|
| `src/alloc.c` | `Rc` (default) and `Heap` allocators, traits, `mk_alloc*`, `mk_free`, the hidden allocator prefix |
| `src/alloc_arena.c` | `Arena` (bump allocator, `Scoped`) |
| `src/alloc_pool.c` | `Pool` (fixed-size free list) |
| `src/alloc_debug.c` | `DebugAllocator` (leak and double-free reports) |
| `src/object.c` | retain, release, share, `mk_is_instance`, the `Any` type |
| `src/string.c` | `kotlin.String`, conversions, hash, equals |
| `src/numfmt.c` | `Double.toString` compatible formatting |
| `src/utf.c` | UTF-8 decoding, UTF-16 to UTF-8 encoding |
| `src/console.c` | buffered stdout, `mk_print_*`, `mk_panic*` |
| `src/entry.c` | the C `main` |
| `src/stats.c` | reference-counting statistics (only with `-DMK_STATS`) |

## Memory layout and the hidden prefix

Every heap object is one block:

```text
block                       object (= MkObject *)
|<----- MK_MAX_ALIGN ------>|
[ MkAllocator *owner | pad  ][ MkObject header | fields ... ]
```

`mk_alloc_with` asks the allocator for `size + MK_MAX_ALIGN` bytes, zeroes the whole block, stores the allocator in the prefix and fills the header (`refcount = 1`). `mk_release` reads the owner back and calls its `free`. A `NULL` free (a `Scoped` allocator) means: `dispose` runs, memory is not freed.

## Allocators and traits

`MkAllocator` is `{allocate, free, traits}`. The trait bits are `MK_ALLOCATOR_DEALLOCATING`, `_SCOPED`, `_MANAGED` and `_THREAD_SAFE`, the same traits the compiler's checker reasons about.

| Allocator | Traits | Notes |
|---|---|---|
| Rc (default) | MANAGED, THREAD_SAFE | malloc underneath; the compiler may assume release-at-zero |
| Heap | DEALLOCATING, THREAD_SAFE | the same malloc, but manual ownership: `mk_free` exactly once |
| Arena | SCOPED | chunks start at 4096 bytes and double up to 4 MiB; oversized requests get a dedicated chunk; not thread-safe |
| Pool | DEALLOCATING | one block size, LIFO free list, not thread-safe |
| Debug | inner's | books, a quarantine of freed blocks, locked |

**The arena never runs `dispose`**, neither at reset nor at destroy. It keeps no per-object records, which makes it free of overhead, and the compiler's checker (M0115) makes sure that nothing needing cleanup lives inside.

**`mk_free(object)`** is the manual-mode counterpart of the last `release`: it runs the dispose chain and returns the block to the owner regardless of the refcount. It panics for static or stack objects, for objects of `Scoped` allocators and for already freed objects.

**`DebugAllocator`** records where each object was allocated (`file:line:col`) and reports `leak: <Type> allocated at <site>` for every live block, and `double free of T (allocated at A, first freed at B), freed again at C`.

## Strings, numbers, console

- `MkString` is UTF-16, not NUL-terminated. Decoding from UTF-8 is strict (invalid subparts become U+FFFD), `mk_string_hash` is Java's `String.hashCode`.
- `Double.toString` follows Kotlin/JVM: `NaN`, `Infinity`, `1.0E10`, shortest digits that round-trip.
- stdout is fully buffered (64 KiB) and flushed by `main` and by `mk_panic`. `mk_panic` prints `fatal: <message>` to stderr and aborts.

## Statistics

`minic build --stats` compiles with `-DMK_STATS`: allocations, frees, live and peak objects and bytes, and retain and release calls split into non-atomic, atomic and no-ops are printed to stderr at exit as `mk-stats:` lines. Without the flag the runtime contains no counting code. The [benchmarks](/benchmarks) use this.

## Tests

`runtime/run_tests.sh` builds the C tests with `-std=c17 -Wall -Wextra -Werror` and runs each under ASan and UBSan, and the threading test additionally under ThreadSanitizer. The compiler's end-to-end tests additionally run every program under AddressSanitizer with leak checking on.

## Planned

Rewriting the runtime in Kotlin (Strict mode plus `@NoAlloc`, with only libc and libuv as external libraries) is on the [roadmap](/roadmap) and not started.
