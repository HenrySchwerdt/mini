# Rejected StringBuilder 16-unit inline buffer pilot

## Decision

Do not integrate the private `MkSb` inline buffer. The matched JSON workload improved only 1.1% in parent-measured wall time, while a repeatable workload that grows a builder one UTF-16 character at a time regressed 33.8%. The experiment is preserved on `codex/escape-builder-storage`; this is evidence for rejection, not a proposed default.

## Prototype and correctness

The prototype added 16 trailing `MkChar` units to the private `MkSb` allocation. `MkSb`'s existing fields and `sb_type.instance_size` stayed unchanged. `mk_sb_new` requested `sizeof(MkSb) + 16 * sizeof(MkChar)` through `mk_alloc_sized`; growth past 16 used `malloc` plus a copy, later growth used `realloc`, and disposal freed only external storage. `toString()` continued to copy into an immutable `MkString`.

Four focused test builds passed, each with 54 checks and zero failures: ASan+UBSan; UBSan with the small allocator; `MK_STATS` with the small allocator; and `MK_PLAIN_MALLOC` plus `MK_STATS`. Coverage includes empty, 16/17-unit boundary, zero-fill growth, immutable snapshots, surrogate units, existing long growth, and `INT64_MIN`/`INT64_MAX` append. Full command and output records are in [campaign-stringbuilder-inline16.json](../../bench/results/campaign-stringbuilder-inline16.json).

## Matched JSON result

Both variants linked the same typecache-candidate API O3 JSON object (SHA-256 `5f0eb17a27567058628e3d2ecc571efc706e07f44d688c31848e2d1c43e38534`) with O2 FAM-coallocation runtime archives. The archives contain identical objects except `v1s.o`; only that source changed. Link command for both variants:

```text
clang <same-json-object> <runtime-archive> -lm -Wl,-dead_strip -o <binary>
```

Both printed `7055506 1072916 885982430`. Across 11 rotated pairs, with a warmup and parent-side `time.perf_counter_ns` around each child process, median wall time was 399.22 ms baseline and 394.88 ms candidate (−1.09%). The seven-pair `time -lp` sample had median retired instructions of 5.696B baseline and 5.571B candidate (−2.2%); that counter is secondary to the parent-side wall measurement.

`MK_STATS` reported equal allocation/free counts (11,224,988 / 11,224,986), `peak_live` (2,027,260), and `bytes_peak` (122,625,922). `bytes_total` increased from 615,030,474 to 626,642,506 because each builder object requested 32 more managed bytes. External character buffers allocated by libc are outside these counters.

## Builder controls and regression cause

The same O2 C control object and same FAM runtime archives were used for both variants. It exercises empty, 8-unit, 16-unit, 17-unit, 1024-unit incremental append, and numeric `Long` builders. Each result was checked for matching stdout. For the 1024-unit case, both print `l 100000 113000000`; 11 rotated parent-timed pairs measured median wall time of 168.92 ms baseline and 226.06 ms candidate (+33.83%). Retired-instruction samples independently showed +24.8% for this case.

The large control calls `append(Char)` 1024 times per builder. In the baseline O2 object, `sb_reserve` is inlined into `mk_sb_append_char`. In the candidate O2 object, the larger reserve routine is out of line and `mk_sb_append_char` calls it on every append: 102.4 million helper calls for this workload. Disassembly of the two `v1s.o` objects confirms the difference. The candidate saves one external growth request per builder (six growth requests rather than seven) but copies the initial 16 UTF-16 units when moving to heap storage; this saving does not offset the per-append call overhead here. No alternate capacity or tuning experiment was run.

The small controls show where the idea helps: 8-unit builders improved 34.8% and 16-unit builders 8.1%; crossing to 17 units improved 23.3%. Empty and numeric controls were near noise (+1% for numeric). These isolated wins do not outweigh the large-builder regression for a generic runtime change.

## Reproduction and artifacts

Raw paired timings, exact commands, stdout/stats, source and object hashes, and generated archive hashes are recorded in [campaign-stringbuilder-inline16.json](../../bench/results/campaign-stringbuilder-inline16.json). The C control source is embedded there and its SHA-256 is `5f1e28d4f1c8c0acd42033f569a7ae00eb6a529a65d2bea3618b5ae030739990`. The baseline plain archive SHA-256 is `3f4d10731cfba62ac8133796f89b7f18f1d112c30fa822d3858e32a758f2da3c`; the candidate archive SHA-256 is `a2b96fb89669f8bef7d926258267010002a1612ac2f0ad10b57567203d3fd6ac`.
