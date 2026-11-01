# Guarded type-family cache pilot

## Result

The bounded classifier improved the JSON Mini API runtime by about 4.9% against the same compiler before the pass. The candidate remains about 1.23× the hand-written C reference, so it does not meet the 1.2× target on this workload.

The measured medians over 11 rotated rounds were:

| Variant | Median runtime |
| --- | ---: |
| Mini baseline | 0.41094 s |
| Type cache only | 0.39810 s |
| Type cache plus `mk_is_instance` read-only attribute | 0.39313 s |
| C, O3, generic CPU | 0.31891 s |
| C, O3, native CPU | 0.31939 s |

Paired median ratios were 0.951× candidate/baseline, 0.978× candidate/cache-only, 1.231× candidate/C-native, and 1.233× candidate/C-generic. Every run produced the expected `7055506 1072916 885982430` output.

## Correctness and cost

The classifier recognizes only canonical type-info globals emitted in the current module. NULL returns false before loading the object header. Unknown type-info pointers use the existing `mk_is_instance` fallback. A C interop regression fixture creates a foreign descriptor whose superclass is a final Mini class and checks that fallback preserves subclass behavior.

`mk_is_instance` receives LLVM `memory(read)` only for the exact runtime `bool(ptr, ptr)` signature, unless a module or imported signature defines that symbol. LLVM 18’s memory-effect encoding was checked in its local `ModRef.h`; the API attribute value is 21. The classifier remains `memory(none)`.

The family planner indexes superclass children once and stops after nine visited family nodes, so over-budget families fall back without scanning every descendant. A single compiler self-build run was 30.38 s with the baseline compiler and 30.60 s with the candidate. This is essentially flat, but has only one run per compiler. I omitted the macOS `time -lp` instruction counters because their units and provenance were not comparable to the earlier 47B profiler count.

Validation: backend tests passed 95/95. The C and LLVM API end-to-end fixture passed with source stdlib; LLVM API also passed with precompiled `.mlib` stdlib. The suites were run separately because the test harness uses the same temporary basename for each backend.

The five-way, 55-run timing matrix, hashes, flags, and provenance are in [campaign-typecheck-family.json](../../bench/results/campaign-typecheck-family.json). The candidate compiler is `build/campaign-validation/typecheck-candidate/stage3`; the candidate O3 JSON object is `build/campaign-validation/typecheck-candidate/json-candidate.o`. This remains a prototype pending lead review and broader workload evidence.
