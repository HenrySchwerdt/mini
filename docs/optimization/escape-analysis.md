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

The current type-classification prototype recognizes canonical local type-info addresses and falls back to the original runtime traversal for unknown or foreign types. It does not assume source-final classes can never have foreign subclasses. Its latest five-way pilot measured 0.41094 s baseline, 0.39810 s classifier only, and 0.39313 s classifier plus an exact read-only runtime declaration, against C at 0.31891 s: **1.233 times C, still above target**. The source and foreign-override fix have passed independent review and 95 backend tests and are integrated on the campaign branch. Broader integrated validation remains pending; these are pilot results, not a completed outcome.

## Accounting limits

RC counters in the combined runtime experiment count out-of-line runtime hook calls. LLVM-inlined retain/release operations are not included, so those deltas are not total logical RC reductions. Managed allocation statistics formerly excluded separately allocated libc backing buffers; coallocation makes those bytes visible. Counter scope must be considered before inferring memory changes.

No new claim is made yet for complete self-compilation, the broader workload suite or platform coverage. The next decision depends on reviewed type-classification and StringBuilder results, followed by fresh integrated benchmarks and correctness checks.

## Follow-on evidence

The optimized JSON object contains no checked-cast runtime call. Its tree walk calls the type-family classifier once, dispatches known tags, and retains six generic type checks only in the unknown-descriptor fallback. The earlier baseline profile cannot establish the frequency of this fallback in the candidate. A speculative checked-cast pass was therefore not implemented.

A first 16-unit StringBuilder inline-buffer prototype was rejected: it improved JSON only 1.1% and regressed repeated 1024-character builder growth by 33.8%. Disassembly identified an extra out-of-line reserve call on every character append. Splitting the capacity guard from slow growth removed that wall-time regression. The follow-on measured 2.08% lower JSON time in eleven matched rotated rounds and 1.07% lower C/MIR-emission self-compilation time in five pairs, with identical outputs. These are local measurements, not universal guarantees.

The follow-on still adds 32 managed bytes per builder and increases retired instructions by 7.84% in the large-builder control, despite slightly improved wall time there. On the fixed 153-input compiler workload, instructions instead fell 1.08% and peak RSS rose about 1.25 MB. Both sides of this tradeoff matter. In the latest same-round JSON comparison, Mini fell from 1.246 to **1.220 times the fastest C reference**. The goal is still unmet. Final integration and fresh broad validation are pending.

A further NULL-slot setter guard eliminated 5,692,235 out-of-line no-op releases but did not improve JSON time (400.13 ms baseline, 401.19 ms candidate across eleven matched rounds). It was rejected; only its evidence is retained here. This illustrates why removing calls or instructions alone is insufficient to claim a runtime gain. The adopted disposal guard and the setter experiment have different measured outcomes.

The fresh profile of the accepted type-family/FAM/hot-cold combination used a profiling-only REPS=50 copy with the same output, not a new comparative timing run. Of 3,228 main-thread samples, the leading exclusive frames were parser work (16.9%), release (10.9%), the type-tag helper (9.0%), tree walking (7.9%) and allocation (7.6%). Recursive inclusive rows are non-additive. This supports testing classifier-specific inlining; it does not justify a checked-cast pass or a general uninitialized allocator change. Public zero-initialized string allocation remains unchanged.

## Integrated validation in progress

At accepted optimization source `5097406`, fresh C stage2/stage3 C+MIR and LLVM API stage2/stage3 C+MIR+LLVM+object fixpoints passed. Compiler modules passed 129 tests (95 backend, 32 frontend, 2 native driver); C end-to-end passed 331 with one platform skip, API end-to-end passed 75, and REPL/ORC smoke tests passed.

Strict runtime validation exposed a pre-existing NULL-plus-zero pointer operation in `mk_string_from_utf8`. The separately reviewed fix in `713f0fa` uses a static empty-byte sentinel and preserves NULL-as-empty behavior for all supplied lengths. The full runtime suite then passed with `UBSAN_OPTIONS=halt_on_error=1` and the installed libuv prefix, including ASan/UBSan, pooled allocator, plain malloc and TSan thread checks. The UTF-8 test passed 73 checks in both ASan/UBSan and UBSan configurations. Earlier compiler fixpoints predate this runtime-only fix; refreshed linked artifacts and WASM checks remain in progress.

Classifier-specific inlining is also retained after general and Kotlin review. It improved 9 of 11 pairs, reduced retired instructions 0.63%, and reduced the matched family object's size by 176 bytes and linked executable by 32 bytes. The latest pilot remains **1.207 times the fastest C median**, rather than within 1.2. The older 67 KB object measurements used a packaged compiler and installed `.mlib`; they are not a causal size comparator for the current source-stdlib objects.
