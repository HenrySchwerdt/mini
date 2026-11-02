# Private reference-array backing storage fusion

The `MkRefArray` slot buffer never escapes its opaque container and is released with it. All layout uses are private to `runtime/src/v1s.c`; public APIs accept opaque `MkObject*`. A flexible array member after the existing fields permits one allocation instead of an object allocation plus a separate `calloc` buffer. Existing field offsets, base `sizeof`, runtime type information and public API are retained.

`mk_alloc_sized` already zeroes the requested block and records its actual allocation size in the hidden prefix used by the small allocator, freeing and statistics. Construction checks negative sizes, size_t multiply/add overflow and allocation failure before setting length/items. Empty arrays keep `items == NULL`. Disposal releases children once and leaves inline storage to the owning allocation; it never passes it to `free`.

## Validation

Independent code review found no correctness issues. Two focused tests passed in each of four configurations: ASan+UBSan, UBSan alone (small allocator enabled), `MK_PLAIN_MALLOC`+UBSan, and `MK_STATS`+UBSan. They cover empty/sparse/static/owned/borrowed elements, zero initialization, negative size and a 128-slot allocation crossing the small-allocation threshold. The 32-bit overflow case is conditionally included; it was not executed on this 64-bit host.

The review requested a large-array control because calloc can avoid eager page writes. Five alternating pairs repeatedly created and released 1,048,576-slot sparse arrays. Median instructions were 231.66M versus 232.05M (effectively flat, +0.17%), RSS was identical at 9,748,480 B, and wall time was 14.69 versus 14.15 ms (too short/noisy for a performance claim). No large-array regression was demonstrated on this host.

## Matched JSON pilot

Both runtime variants used exactly the same already-compiled combined JSON API O3 object (string fast paths and four-slot ArrayList default), with runtime C compiled at O2. Eleven rotated blocks also ran the same strict hand-C O3 and native-O3 binaries and verified complete stdout.

| Metric | Separate buffer | Coallocated buffer |
|---|---:|---:|
| JSON median wall | 0.44243 s | 0.43243 s |
| Retired instructions | 7,159,262,382 | 6,199,291,432 |
| Median max RSS | 145,506,304 B | 145,801,216 B |

Instructions fell 13.4%; median wall time fell 2.3% with overlapping ranges. This is a strong instruction reduction and a small/noisy runtime result. The candidate remained **1.331x** the fastest matched C median (0.32487 s), above the 1.2x target. No goal-completion claim is made.

`MK_STATS` byte accounting changes scope: formerly separate libc slot buffers were excluded; coallocated payload bytes are now included. An increase in that managed byte counter is not itself a memory regression. Raw commands, samples, stderr counters, output hashes and test results are in [campaign-refarray-fusion.json](../../bench/results/campaign-refarray-fusion.json). Full integrated validation and broader platform tests remain pending.
