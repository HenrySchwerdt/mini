# LLVM String fast paths

Commit `3d4b3f6` adds signature-gated LLVM fast paths for `mk_string_length` and `mk_string_char_at`. The latter checks null before loading length, uses an unsigned bounds check (covering negative indexes), and performs a UTF-16 load at the target-layout offset. Null and out-of-range paths call the original runtime function, preserving panic behavior. Nullable `String?` calls remain on the runtime path.

The backend suite passed 88/88. A same-toolchain JSON pilot built both compilers at API O1, then built the application at API O3 against the same `stdlib.mlib` and linked both against the shared O2 runtime archive. Outputs matched. Across 11 alternating samples after two warmups, median runtime was 0.50111 s baseline and 0.47699 s candidate (0.952 ratio). The application object grew from 66,752 to 67,760 bytes; linked executable size stayed 148,872 bytes.

Source and edge-case details, full argv, raw samples, hashes, exit statuses, and artifact provenance are in [campaign-string-fastpaths.json](../../bench/results/campaign-string-fastpaths.json). The baseline object hash matches the previous `bench/json/json.kt` O3 artifact exactly. The earlier 63,968/64,912-byte sizes were from a separate O1 pilot and are superseded. Profile objects compiled from the 50-repetition profiling source are separate workload artifacts.
