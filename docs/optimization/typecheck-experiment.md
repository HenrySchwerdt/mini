# `mk_is_instance` early superclass scan: rejected experiment

## Change tested

The experiment added a superclass pointer-identity pass at the start of `mk_is_instance`; on a miss it called the existing recursive `type_is` unchanged. This preserved the public ABI and interface fallback. It was removed after measurement because the work did not pay off.

## Pilot and reproducibility

This was a selfcompiler workload: one generated C file compiled from 153 Kotlin inputs, relinked once with the baseline runtime and once with the candidate runtime. The C and MIR artifacts matched byte-for-byte for all ten executions (`a882afa3…` C; `7abf6027…` MIR), and the 153 input paths were SHA-256 checked before and after the pilot.

The compiler workload came from `/Users/henryschwerdtner/.codex/worktrees/campaign-benchmark/mini` at commit `bef08b56dbd5128959356a36b3cac66be8d9f431`. The generated C was reused from the earlier core pilot, whose compiler variants were built with `(cd compiler && ../mini build -p minic-native --release --backend c)`. The two runtime-pilot executables themselves were built by the direct relink command below. The exact generator argv and per-input hashes are recorded in [campaign-typecheck-experiment.json](../../bench/results/campaign-typecheck-experiment.json).

Both runtime variants used the same `/tmp/campaign-core-pilot/baseline.c`, the same runtime translation units, and `clang -std=c17 -O2 -w -Iruntime/include -Iruntime/src`. The baseline `object.c` was materialized from runtime commit `8ce0e5c61ddf7fe2992777b9e6b5c753070e555e` with `git show HEAD:runtime/src/object.c`; the candidate was the same source plus the early superclass pass. The JSON stores the exact argv, compiler paths, source hashes, and all five alternating paired runs.

Focused validation used `clang -std=c17 -g -O1 -Wall -Wextra -Werror -I runtime/include -I runtime/src -fsanitize=address,undefined -fno-omit-frame-pointer`, linking each focused test with the runtime sources except `entry.c`. `test_object`, `test_classes`, and `test_v1l` passed (41, 42, and 63 checks respectively).

## Result

| Metric | Baseline median | Candidate median | Paired median change |
|---|---:|---:|---:|
| Retired instructions | 43.515B | 44.645B | **+2.59%** |
| Wall time | 2.807 s | 2.719 s | **+1.26%** |
| Cycles | 10.538B | 10.669B | +1.24% |
| Maximum RSS | 688.0 MB | 689.3 MB | +0.20% |

Retired instructions increased in every pair (+2.33% to +2.68%). Wall time was noisy (paired changes: +1.86%, −11.78%, −3.13%, +1.26%, +1.34%); the paired median regressed. The early scan duplicates superclass work on interface and negative checks, which outweighed its class-target wins in this workload. The optimization was rejected and no runtime change remains.

These absolute instruction counts must not be compared with either the separate core shortcut pilot or the published hand-C / Mini C / API runtime baseline. That benchmark uses different generated programs and build/link recipes; this pilot specifically measures a selfcompiler C binary relinked against two runtime versions.
