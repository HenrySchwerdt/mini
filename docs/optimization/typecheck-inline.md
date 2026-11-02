# Type classifier inlining prototype

The type-family classifier is a pure helper that compares a runtime type-info pointer against a bounded set of canonical, locally emitted globals. The O3 JSON profile attributed 291 of 3,228 exclusive samples (9%) to `mkh_type_tag`. This prototype gives only those generated helper declarations and definitions `alwaysinline nounwind memory(none)`. Other pure helpers keep their existing attributes; the type classifier gets no sanitizer instrumentation or new memory effect.

The backend suite passed 95/95. The API-generated IR shows `attributes #2 = { alwaysinline nounwind memory(none) }` on the classifier. The O3 object has no classifier helper symbol or call relocation, so the helper call is inlined into the caller. Unknown-type `mk_is_instance` fallback calls remain.

An 11-round rotated comparison used the same accepted O2 hot/cold runtime archive (SHA-256 `0aedbd0b22020138a8c79c9fb14dcf387d25f3ab9397df5e973c483c166a31b7`) for baseline, pre-inline family, and pure-inline Mini executables. C references were compiled with O3 and `-fno-fast-math`, with generic and native host-tuned variants. Every executable produced `7055506 1072916 885982430`.

| Variant | Median seconds |
| --- | ---: |
| Baseline Mini | 0.41443 |
| Type-family Mini, before inlining | 0.39842 |
| Type-family Mini, inlined | 0.39586 |
| C O3 native | 0.32796 |
| C O3 generic | 0.33017 |

Inlining improved the family variant by about 0.54% on the paired median ratio: it was faster in 9 of 11 rounds, with a 95% bootstrap interval of 0.9871–0.9986 for inline/family. Five alternating direct-child runs also show lower median retired instructions (5.501B vs 5.536B, -0.63%) and cycles (1.596B vs 1.618B, -1.38%). The app object shrank by 176 bytes (90,560 to 90,384); the executable shrank by 32 bytes (102,464 to 102,432). The baseline object/executable were 90,096/102,440 bytes, making the inlined build +288/-8 bytes versus baseline.

The inlined variant's median runtime is 1.207x native C and 1.199x generic C; paired median ratios are 1.210x and 1.204x. The result is near the 1.2x target, but does not meet it consistently across both references. The 90 KB objects here use the current local API compiler and current stdlib Kotlin sources. The earlier 66,752-byte object in the published runtime baseline was built with the packaged Mini 0.7 compiler and installed `stdlib.mlib`; its size is not a comparable baseline for this source-mode experiment.

Raw round timings, hashes, commands, output checks, and object sizes are recorded in [campaign-typecheck-inline.json](../../bench/results/campaign-typecheck-inline.json). The build-directory IR/object/executable artifacts are ignored build outputs and remain available in this worktree for review.
