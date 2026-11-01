# Continued runtime and escape analysis

This continues the [first campaign phase](campaign.md). Work stays on the campaign branch; it is not merged or released. The target is at most 1.2 times the runtime of a tuned, semantically equivalent C reference on representative complex workloads. Passing a finite suite cannot establish a universal bound.

## What escapes

The JSON tree returned by the parser remains live during the subsequent walk. Its nodes cannot safely be placed on the parser's stack. The parser and statistics wrapper are created only five times in the measured workload, so promoting those wrappers would not address the dominant allocation cost. General managed-object stack promotion needs parameter escape and returned-alias summaries, cleanup on exceptional exits, loop lifetime handling and ownership/identity proofs; it is not implemented here.

Two private runtime buffers have a simpler proof: their addresses stay inside an opaque owner and their lifetimes match that owner. Reference-array slots now share an allocation with their container. A StringBuilder inline-buffer experiment is in progress; immutable string snapshots must still copy their contents. Neither transformation changes the public object header or reference-counting ABI.

## Reviewed and integrated changes

- Skip reference releases for NULL slots during private array disposal.
- Start default ArrayList storage at four slots instead of ten; explicit capacities and logical behavior remain unchanged.
- Inline exact LLVM string length and UTF-16 character access signatures, preserving NULL behavior, bounds checks and the existing panic fallback.
- Coallocate private reference-array backing storage with its owner, with overflow checks and the existing allocation/free accounting.

See the individual reports for [disposal](ref-array-dispose.md), [capacity](arraylist-capacity.md), [string access](string-fastpaths.md) and [storage fusion](ref-array-storage.md).

## Measurements so far

Applications use LLVM API O3, runtime C O2 and matched link flags. C references use strict floating-point semantics and include generic and native O3 builds. Each comparison checks complete output and runs rotated samples under an exclusive CPU lease. Separate sessions have different absolute timings; compare matched pairs within each session.

| Matched session | Before | Candidate | Fastest C | Candidate / C |
|---|---:|---:|---:|---:|
| First three changes, 11 blocks | 0.49201 s | 0.44018 s | 0.32341 s | 1.361 |
| Reference-array coallocation, 11 blocks | 0.44243 s | 0.43243 s | 0.32487 s | 1.331 |

The storage fusion reduced retired instructions from 7.159B to 6.199B (13.4%), while the wall-time change was only 2.3% with overlapping ranges. A large sparse-array control found effectively flat instruction counts and RSS. Sanitizer, pooled-allocator, plain-malloc and statistics configurations passed focused ownership tests. Fresh full integrated validation remains pending.

The current type-classification prototype recognizes canonical local type-info addresses and falls back to the original runtime traversal for unknown or foreign types. It does not assume source-final classes can never have foreign subclasses. Its latest five-way pilot measured 0.41094 s baseline, 0.39810 s classifier only, and 0.39313 s classifier plus an exact read-only runtime declaration, against C at 0.31891 s: **1.233 times C, still above target**. Review and integration are pending; these are pilot results, not a completed outcome.

## Accounting limits

RC counters in the combined runtime experiment count out-of-line runtime hook calls. LLVM-inlined retain/release operations are not included, so those deltas are not total logical RC reductions. Managed allocation statistics formerly excluded separately allocated libc backing buffers; coallocation makes those bytes visible. Counter scope must be considered before inferring memory changes.

No new claim is made yet for complete self-compilation, the broader workload suite or platform coverage. The next decision depends on reviewed type-classification and StringBuilder results, followed by fresh integrated benchmarks and correctness checks.
