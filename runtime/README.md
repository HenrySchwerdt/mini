# libminirt

C17 runtime for Kotlin/Mini. `include/minirt.h` is the ABI contract with the
IR-to-C backend; generated code includes only that header.

## Layout

| File | Contents |
| --- | --- |
| `src/alloc.c` | Rc (default) and Heap allocators, traits, `mk_alloc*`, `mk_free`, hidden allocator prefix |
| `src/alloc_small.{h,c}` | thread-local size-class free lists behind Rc and Heap (off under ASan / `-DMK_PLAIN_MALLOC`) |
| `src/alloc_arena.{h,c}` | Arena (Scoped bump allocator, inline fast path, chunk cache) |
| `src/alloc_pool.c` | Pool (fixed-size free list) |
| `src/alloc_debug.c` | DebugAllocator (leak and double-free diagnostics) |
| `src/object.c` | retain/release/share, `mk_is_instance`, `mk_type_Any` |
| `src/stats.c` | D1 runtime statistics (`-DMK_STATS` only, otherwise empty) |
| `src/string.c` | `kotlin.String`, number/boolean/char conversion, hash, equals |
| `src/numfmt.c` | `Double.toString` compatible formatting |
| `src/utf.c` | UTF-8 decoding / UTF-16 to UTF-8 encoding |
| `src/console.c` | buffered stdout, `mk_print_*`, `mk_println_*`, `mk_flush`, `mk_panic*` |
| `src/entry.c` | C `main` (own file so tests can link without it) |
| `src/internal.h` | private helpers (not ABI) |

## Memory layout and the hidden allocator prefix

Every heap object is one block:

```
block                       object (= MkObject *)
|<----- MK_MAX_ALIGN ------>|
[ MkAllocator *owner | pad  ][ MkObject header | fields ... ]
```

`MkObject` itself is unchanged. The prefix is exactly `MK_MAX_ALIGN` bytes
(16 on common targets; a union of the widest scalar types, because MSVC in C
mode lacks `max_align_t`), so the object stays max-aligned. `mk_alloc_with`
asks the allocator for `size + MK_MAX_ALIGN` bytes at that alignment, zeroes
the whole block, stores the allocator in the prefix and fills the header
(`refcount = 1`, `flags = 0`). `mk_release` reads the owner back and calls
`owner->free(owner, block)` (the pointer `allocate` returned). A `NULL` free
(Scoped allocators) means: dispose runs, memory is not freed. Static and stack
objects have no prefix and never reach the free path (flags).

`mk_alloc_with(NULL, ...)` uses the default allocator. A NULL type or a type
with `instance_size < sizeof(MkObject)` (interfaces) yields NULL. Variable-sized
objects (strings) use the internal `mk_alloc_sized`. `mk_object_block_size(type)`
is what a custom allocator is asked for (instance size + prefix).

## Allocators and traits

`MkAllocator` is `{allocate, free, traits}`. `traits` was appended at the end
of the struct so the old layout is a prefix of the new one; allocators written
as `{allocate, free}` (traits 0) keep working, but strict `-Wextra` warns about
the missing initializer: write `{allocate, free, 0}`. Always read traits via
`mk_allocator_traits(a)`, which maps a 0 field to DEALLOCATING (`free != NULL`)
or SCOPED (`free == NULL`). Trait bits: `MK_ALLOCATOR_DEALLOCATING`, `_SCOPED`,
`_MANAGED`, `_THREAD_SAFE`.

| Allocator | Constructor | Traits | Notes |
| --- | --- | --- | --- |
| Rc (default) | `mk_default_allocator()` / `mk_rc_allocator()` | MANAGED, THREAD_SAFE | size-class free lists (<= 256 B) over malloc; the compiler may assume release-at-zero |
| Heap | `mk_heap_allocator()` | DEALLOCATING, THREAD_SAFE | the same memory source, but manual ownership: `mk_free` once |
| Arena | `mk_arena_create(initial)`, `_reset`, `_destroy`, `_used`, `_reserved` | SCOPED | bump allocation (inline fast path, cached chunks), not thread-safe |
| Pool | `mk_pool_create(block_size, blocks_per_slab)`, `_destroy`, `_live_count` | DEALLOCATING | one block size, free list, not thread-safe |
| Debug | `mk_debug_allocator_create(inner)`, `_destroy`, `mk_debug_stats`, `mk_debug_report_leaks` | inner's | books, quarantine, locked |

Alignment: every allocator returns max-aligned blocks (the Arena honours any
power of two, Heap/Pool up to the fundamental alignment, else NULL).

**Arena.** Chunks start at `initial` bytes (default 4096) and double up to
4 MiB. A request larger than half of the next chunk gets a dedicated chunk (kept
on a separate list, so the current chunk keeps serving small requests). `reset`
rewinds regular chunks for reuse and frees dedicated ones. Under ASan the unused
and reset memory is poisoned. **The arena never runs `dispose`**, neither at
reset nor at destroy: it keeps no per-object records (zero overhead), the point
of a Scoped allocator is that everything dies together, and the compiler's
escape analysis makes sure nothing needing cleanup is left inside; arena objects
must not own refcounted fields (or must release them explicitly). `mk_release`
on an arena object still runs `dispose` but not free.

**Pool.** Free-list reuse is LIFO. The free-list link lives at
`block + sizeof(void*)` so the owner pointer and the object header of a freed
block stay readable and `mk_free` can report a double free. Use
`mk_pool_create(mk_object_block_size(type), n)` for objects of one type.

**DebugAllocator.** Where the object was allocated is communicated through a
thread-local context set by `mk_alloc_with_site(allocator, type, "file:line:col")`
(type name comes from the same context; plain `mk_alloc_with` records
`<unknown>`; raw `allocate` calls record `<raw>`). No change of the allocator
vtable is needed and other allocators ignore the site. It keeps a pointer hash
table plus an allocation-ordered list of live blocks, and a bounded quarantine of
freed blocks (256 blocks / 16 MiB) that are only handed back to `inner` on
eviction or destroy. Hence: double free -> `mk_panic("double free of T
(allocated at A, first freed at B), freed again at C")` (`mk_free_with_site`
supplies C); free of a pointer it never returned -> `free of unknown pointer`.
`mk_debug_report_leaks` prints `leak: <TypeName> allocated at <site>` per live
block to stderr in allocation order and returns the count. `destroy` returns all
still-live blocks to `inner` without dispose. A debug allocator over an Arena
keeps books on release only (no real free).

**`mk_free(object)`.** Manual-mode counterpart of the last `mk_release`: runs the
dispose chain (most derived first) and returns the block to the owner
regardless of the refcount (manual-mode objects have refcount 1 and generated
code never retains them). NULL is a no-op. It panics for static/stack objects,
for objects of SCOPED allocators, and for already freed objects
(`MK_FLAG_FREED`, set by the runtime when memory is returned; reliable for Pool
and DebugAllocator memory, which keep freed headers readable; for the malloc
based allocators the read is itself a use-after-free that ASan reports). Mixing
`mk_free` with outstanding RC references leaves them dangling; a later
`mk_release` of a freed Pool/Debug object panics with "underflow".

## Reference counting

* `mk_retain`/`mk_release`/`mk_share` accept NULL and are no-ops for
  `MK_FLAG_STATIC` / `MK_FLAG_STACK`.
* Plain (non-atomic) updates normally; with `MK_FLAG_SHARED` atomic
  (`__atomic_*`, `_Interlocked*` on MSVC; acq_rel ordering).
* At zero: `dispose` of each type along the `super` chain, most derived first
  (NULL entries skipped), then the memory is freed.
* Releasing at refcount 0, retaining a dead object or counter overflow calls
  `mk_panic`.
* `mk_share` must be called while the caller still has exclusive access (before
  the object is published to another thread); it does not touch fields.

## Statistics (`-DMK_STATS`, block D1 of `minirt.h`)

Compile the runtime and the generated code with `-DMK_STATS` (`minic build --stats`) to count, with relaxed atomics,
allocations and frees (all allocators), bytes requested, the live and peak number of objects / bytes, and the calls of
`mk_retain` / `mk_release` split into non-atomic, atomic (`MK_FLAG_SHARED`) and no-ops (NULL / static / stack
objects). `mk_stats_get`, `mk_stats_reset` and `mk_stats_dump` are available to programs; at exit (registered with
`atexit` on first use) four `mk-stats: key=value ...` lines go to stderr. The block size for the byte counts is
remembered in the padding of the hidden allocator prefix. Without the macro `object.c` / `alloc.c` / `stats.c` contain
no statistics code or data (`run_tests.sh` checks the object files for `mk_stats` symbols); `tests/test_stats.c` is built
with `-DMK_STATS` by `run_tests.sh`. `docs/benchmarks.md` uses the counters to compare code generations.

## Strings

`MkString` is UTF-16 (`MkChar`), not NUL-terminated.
`mk_string_from_utf8` decodes strictly (no overlongs, no surrogates, max
U+10FFFF); each maximal invalid subpart becomes one U+FFFD; 4-byte sequences
become surrogate pairs. Output encodes UTF-16 to UTF-8, unpaired surrogates
become U+FFFD. `mk_string_hash` is Java `String.hashCode`. `NULL` is printed and
concatenated as `null` (Kotlin `String?` semantics). `MK_STRING_LITERAL` needs
`u"..."` literals whose element type is `uint16_t`-compatible (true for
clang/gcc).

## Number formatting

Int/Long decimal. Double follows Kotlin/JVM `toString`: `NaN`, `Infinity`,
`-Infinity`, `0.0`, `-0.0`; plain decimal for `1e-3 <= |x| < 1e7`, else
`d.dddE<exp>`; always at least one fractional digit. Digits are the shortest
that round-trip (`%.{0..16}e` + `strtod`); when one digit suffices the closest
2-digit decimal is used, as the JDK does (`4.9E-324`). Float
(`mk_string_from_float`, `mk_print_float`) uses the same rules with the shortest
digits that round-trip as a *float* (`0.1f` -> `0.1`, `1.4E-45`, `3.4028235E38`;
JDK 19+ behaviour, e.g. `Float.MIN_NORMAL` prints `1.1754944E-38`). Byte and
Short print as Int. Assumes the C locale (the runtime never calls `setlocale`).

## Console and panic

`mk_print_int/long/float/double/boolean/char` format into a stack buffer and
write straight to stdout (no allocation, no newline); `mk_print_char` encodes
the UTF-16 unit as UTF-8 (a lone surrogate becomes U+FFFD). `mk_flush` flushes
stdout. `mk_panic_string` is `mk_panic` with a Kotlin string. A NULL string prints
as `null`. `mk_string_alloc(MkInt)` returns a string whose characters the caller
fills in (currently zeroed, but unspecified by contract); `mk_string_length`
returns 0 for NULL.

stdout is fully buffered (64 KiB, set on first use or in `main`), flushed by
`main` before returning and by `mk_panic`. `mk_panic` prints `fatal: <message>`
to stderr after flushing stdout, then `abort()`.

## Tests

```
runtime/run_tests.sh
```

Builds with `-std=c17 -Wall -Wextra -Werror`, runs every `tests/test_*.c` under
ASan+UBSan and the threading test additionally under TSan. Console tests are
compared with `tests/expected/<name>.out`; `tests/check_panic.c` checks the
panic path. `CHECK_ABORTS` in `tests/harness.h` forks and asserts that a call
panics with a given stderr message (POSIX only; a no-op on Windows). Allocator
tests are `tests/test_alloc_{basic,arena,pool,debug}.c`; the threading test also
hammers a shared DebugAllocator under TSan. If the selected compiler (`CC`, default clang) cannot link the
sanitizer runtimes, the script falls back to gcc. There is no separate library build: `minic`
compiles `runtime/src/*.c` together with each program.
