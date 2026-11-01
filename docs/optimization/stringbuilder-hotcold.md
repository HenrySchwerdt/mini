# StringBuilder inline storage hot/cold pilot

## Change under measurement

This follow-up keeps the 16-unit private `MkSb` inline buffer from the rejected pilot, but splits its reserve path. `sb_reserve` is now `static inline` and only checks length overflow and available capacity. `sb_grow` retains the allocation, copy, and reallocation work and runs only when the required length exceeds capacity. No `always_inline` attribute or global compiler flag was used. The runtime API, existing field offsets, `sb_type.instance_size`, UTF-16 indexing, and immutable `toString()` copy are unchanged.

The O2 candidate has no `sb_reserve` or `sb_grow` symbol. Disassembly shows the append fast path contains the null/overflow/capacity guards and enters growth code only on a capacity miss. This addresses the previous prototype’s measured regression, where an out-of-line `sb_reserve` call occurred on every character append.

## Validation

ASan+UBSan, UBSan with the small allocator, `MK_STATS` with the small allocator, and `MK_PLAIN_MALLOC` plus `MK_STATS` each passed 54 focused checks. Coverage includes empty builders, the 16/17-unit boundary, zero-fill growth, immutable snapshots, surrogate units, large growth, and min/max `Long` append.

The matched JSON O3 object produced `7055506 1072916 885982430` in every run. Across 11 rotated parent-timed rounds with C O2, C O3, C O3 native, Mini FAM baseline, and Mini hot/cold, median times were:

- C O2: 326.80 ms
- C O3: 319.66 ms
- C O3 native: 318.29 ms
- Mini FAM baseline: 396.59 ms
- Mini hot/cold: 388.34 ms

The Mini/FAM ratio versus the fastest C run moved from 1.24598 to 1.22008, a 2.08% Mini wall-time improvement. It remains above the 1.2 target. Five additional `time -lp` rounds measured JSON retired instructions of 5.677B baseline and 5.530B hot/cold; max RSS was 145,768,448 bytes for both.

In 11 rotated parent-timed builder controls, the 1024-unit incremental append workload improved from 173.33 ms to 169.93 ms (−1.96%). The 8-unit, exact-16, and 17-unit controls improved 37.4%, 35.1%, and 32.5%. Numeric min/max `Long` append was effectively flat (+0.56%). Seven `time -lp` rounds measured 1024-unit retired instructions +7.84% (4.220B to 4.551B), while cycles were flat and wall time improved slightly. RSS fell by about 1.1% in that control. The `v1s.o` text section grew by 356 bytes, from 4,303 to 4,659.

The exact clean 8ce selfcompiler workload used the root-verified 153-input manifest (91 compiler sources, 62 stdlib sources). Both variants matched C SHA-256 `a882afa376e862b647d5abf512b327849f4c4653b113442c956b93d5f500d33e` and MIR SHA-256 `7abf6027e765fedd96ffac6e9b995968d53de676d1ca539ccc0d3d209d22a943` in five rotated pairs. Median wall moved from 2,316.6 ms to 2,291.8 ms (−1.07%); instructions fell 1.08%; cycles fell 0.90%; RSS increased about 1.25 MB.

## Review state and artifacts

The JSON result records exact flags, runtime and app hashes, paired samples, stats, focused test outputs, object-size data, selfcompiler link commands, and all C/MIR hashes in [campaign-stringbuilder-hotcold.json](../../bench/results/campaign-stringbuilder-hotcold.json). The first inline prototype remains separately documented as rejected in [stringbuilder-inline16.md](stringbuilder-inline16.md).

This pilot improves the matched JSON ratio and removes the large wall-time regression, but still leaves the overall Mini/C ratio above 1.2 and has a larger retired-instruction count in the long incremental append control. Keep the benchmark evidence attached to any integration decision.
