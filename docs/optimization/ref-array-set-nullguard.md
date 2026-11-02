# Rejected reference-array setter NULL-release guard

## Decision

Do not integrate this setter guard. The focused stats check proves it skips no-op releases of empty slots, but the matched JSON run showed no wall-time improvement: median time moved from 400.13 ms to 401.18 ms (+0.26%). The Mini/C ratio also moved slightly in the wrong direction, 1.22688 to 1.23012. This is below meaningful measurement resolution and does not support a speedup claim.

## Prototype and tests

The prototype changed only `mk_ref_array_set_o` and `mk_ref_array_set` in the private runtime. It skipped `mk_release(old)` when `old == NULL`; stores, retain order, and every non-null release retained their existing order and behavior. The new focused test covers borrowed and owned stores, replacing a slot with the same alias, clearing live slots, exactly-once disposal, and zero no-op releases for empty slots under `MK_STATS`.

Before the guard, the stats assertion failed with four no-op release calls during the test; after the guard, it passed. ASan+UBSan, UBSan with the small allocator, and plain malloc with stats each passed 12 checks. The full raw compile/test output is embedded in [campaign-ref-array-set-nullguard.json](../../bench/results/campaign-ref-array-set-nullguard.json).

## Matched JSON measurement

Both Mini binaries used the same API O3 JSON object and O2 FAM plus StringBuilder hot/cold runtime objects; only `v1s.o` differed. Both printed `7055506 1072916 885982430`. Eleven rotated rounds compared C O2, C O3, C O3 native, Mini hot/cold baseline, and Mini with the setter guard. Parent-side `time.perf_counter_ns` surrounded each process run.

`MK_STATS` showed `releases_noop` drop from 5,692,235 to zero. Allocation/free counts and managed bytes stayed the same. That confirms the targeted work was removed, but it did not yield a measurable workload gain. Exact samples, flags, object hashes, and stats output are in [campaign-ref-array-set-nullguard.json](../../bench/results/campaign-ref-array-set-nullguard.json).
