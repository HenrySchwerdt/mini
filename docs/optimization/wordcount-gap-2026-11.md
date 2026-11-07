# wordcount: closing part of the gap to Rust (`claude/perf-7`)

Same host and method as [rust-gap-2026-11.md](rust-gap-2026-11.md) (C backend, `-O2`; wall = median of 7 interleaved runs, instructions =
callgrind totals). The baseline is `origin/main` (c817f9f) built with the same stage0.

## Changes (all general, none benchmark specific)

* **`StringBuilder.append(Char)` inline** (C prelude `mkh_sb_append_char`, `MkSb` moved to `minirt.h`): room and a fitting character
  are two stores; growth, widening and a builder that shares a string's buffer take `mk_sb_append_char`. (The LLVM backend still calls it.)
* **`String.substring`**: the Latin-1 block comes from the small allocator inline (`mk_small_new_plain`, no zeroing of bytes that are
  overwritten), short texts are copied with two overlapping loads instead of a `memcpy` call.
* **`mk_string_equals`** compares short Latin-1 strings with 1 to 2 word loads; `==` on `String` / `String?` operands is `mk_string_equals`
  (accepts null) instead of the `mk_equals` type dispatch (C and LLVM emitters, `Ir2MirCalls.refEquality`).
* **`is String` / `is <box>`** is one type-word compare (`mkh_is_exact`), not a walk of the superclass chain.
* **`HashMap`**: String keys take `findStringNode` (direct `hashCode`, direct string compare).
* **Boxing small Ints** (-128..1023) is inline in the C prelude (`mkh_box_int`, the table is exported as `mk_int_box_cache`).
* **`mk_release`** frees the last reference of a String or boxed Int without the dispose chain.

## Result

| benchmark | Rust s | Mini before s | Mini after s | after / Rust | Rust Ir (G) | Mini Ir before (G) | Mini Ir after (G) |
|---|---:|---:|---:|---:|---:|---:|---:|
| binarytrees | 1.43 | 1.16 | 1.21 | 0.85 | 19.64 | 12.85 | 12.85 |
| nbody | 0.98 | 1.18 | 1.23 | 1.25 | 9.03 | 16.45 | 16.45 |
| fannkuch | 2.24 | 2.29 | 2.42 | 1.08 | 9.81 | 22.60 | 22.60 |
| spectralnorm | 1.27 | 1.51 | 1.47 | 1.16 | 12.50 | 18.50 | 18.50 |
| mandelbrot | 1.00 | 1.02 | 1.00 | 0.99 | 6.70 | 6.95 | 6.95 |
| wordcount | 1.17 | 2.84 | 2.49 | 2.12 | 9.51 | 16.96 | 14.49 |
| strbuild | 0.64 | 0.82 | 0.68 | 1.07 | 9.86 | 11.18 | 9.81 |
| json | 0.78 | 1.00 | 1.00 | 1.28 | 3.10 | 4.31 | 4.17 |

The programs whose instruction count did not change (binarytrees, nbody, fannkuch, spectralnorm, mandelbrot) show +-5 percent wall
noise on this shared VM, not a regression. wordcount: -15 percent instructions, about -12 percent wall; strbuild -12 percent
instructions, -17 percent wall.

## Why wordcount stays above 1.3x

The target (<= 1.3x) is **not reached**: 1.52x Rust in instructions, 2.1x in wall time. The wall gap is not instructions but memory:
with 5 000 instead of 50 000 distinct words the program runs in 1.3 s (Rust 0.6 s); with 50 000 it takes 2.5 s (Rust 1.3 s). Each lookup
touches three dependent cache lines that miss L2 (2 MB here): the table slot (1 MB), the 64-byte `HashNode`, the key `String`; Rust's table
(ctrl bytes plus 24-byte buckets, 1.5 MB) mostly stays in L2. Dropping the two unused `before`/`after` links of the node (48 bytes)
measured about -8 percent wall in a hand-patched C file, but needs a `LinkedHashMap` node subclass and checked casts on the hot slot
read, so it is not included. What remains per word is the temporary `substring` (about 130 instructions with its allocation, plus
about 90 to free it), which only a slice representation of strings or escape-analysis-based elision of a non-escaping key can remove; both
touch the string ABI shared by the emitters and the runtime.
