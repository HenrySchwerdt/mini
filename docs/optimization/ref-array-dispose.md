# Ref array disposal: skip NULL releases

`ref_array_dispose` visits every slot to release live references. JSON parsing and collection growth leave many slots NULL, and `mk_release(NULL)` only increments a no-op counter before returning. The change tests each slot and calls `mk_release` only for non-NULL values. It preserves array layout, public ABI, reference ownership, and destruction of populated slots. The `MK_STATS` no-op-release counter changes as expected because skipped calls are no longer counted.

## Focused correctness evidence

`runtime/tests/test_ref_array_dispose.c` creates an eight-slot array with six NULL holes, one tracked managed object, and one static object. Releasing the array disposes the tracked object once. Baseline and candidate passed AddressSanitizer + UndefinedBehaviorSanitizer (3 checks, 0 failures). Separate `MK_STATS` builds passed 8 checks each: array release alloc delta 0, frees delta +2, live delta -2; NULL/static no-op release calls fell from 7 to 1.

## JSON runtime pilot

The exact same existing `bench/json/json.kt` LLVM C API O3 object was linked to baseline and candidate runtime archives. Runtime C sources used `clang -std=c17 -O2 -w -Iruntime/include -Iruntime/src -c`; only `v1s.c` differed. Linking used `clang <object> <libminirt.a> -lm -Wl,-dead_strip`. Both binaries produced the expected output (SHA-256 `0f3864e5576c5693037e942ef5f7730e0bba174981fa79dcbc639539db3f9ef6`). The input object's SHA-256 was `b1853e3131d2a4b4c917c76ebefc08e4270b729078616a1e788d0c56a651e1d4`.

After two warmups, 15 paired runs were measured with `perf_counter`, alternating deterministic randomized order. Median baseline wall time was 0.48706 s; candidate was 0.47829 s, a 1.80% decrease. The result is small relative to run-to-run variability; it is directional evidence alongside the measured elimination of six NULL releases per sparse-array disposal (the seventh baseline no-op, for a static object, remains). These are local matched-runtime results and should not be compared with the historical C-runtime timing. Raw per-run values, hashes, and commands are in [campaign-refarray-dispose.json](../../bench/results/campaign-refarray-dispose.json).
