# libminirt

C17 runtime for Kotlin/Mini. `include/minirt.h` is the ABI contract with the
IR-to-C backend; generated code includes only that header.

## Layout

| File | Contents |
| --- | --- |
| `src/alloc.c` | default allocator, `mk_alloc*`, hidden allocator prefix |
| `src/object.c` | retain/release/share, `mk_is_instance`, `mk_type_Any` |
| `src/string.c` | `kotlin.String`, number/boolean/char conversion, hash, equals |
| `src/numfmt.c` | `Double.toString` compatible formatting |
| `src/utf.c` | UTF-8 decoding / UTF-16 to UTF-8 encoding |
| `src/console.c` | buffered stdout, `mk_println_*`, `mk_panic` |
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

The default allocator is `malloc`/`free` and stateless, hence thread-safe.
`mk_alloc_with(NULL, ...)` uses it. Variable-sized objects (strings) use the
internal `mk_alloc_sized`.

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
2-digit decimal is used, as the JDK does (`4.9E-324`). Assumes the C locale
(the runtime never calls `setlocale`).

## Console and panic

stdout is fully buffered (64 KiB, set on first use or in `main`), flushed by
`main` before returning and by `mk_panic`. `mk_panic` prints `fatal: <message>`
to stderr after flushing stdout, then `abort()`.

## Tests

```
runtime/minirt/run_tests.sh
```

Builds with `-std=c17 -Wall -Wextra -Werror`, runs every `tests/test_*.c` under
ASan+UBSan and the threading test additionally under TSan. Console tests are
compared with `tests/expected/<name>.out`; `tests/check_panic.c` checks the
panic path. If the selected compiler (`CC`, default clang) cannot link the
sanitizer runtimes, the script falls back to gcc. xmake: `xmake build minirt`,
tests as `minirt_test_*` targets (`xmake test`).
