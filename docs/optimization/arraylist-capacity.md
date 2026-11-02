# Default ArrayList capacity: 10 to 4

The no-argument `ArrayList` constructor now allocates a backing reference array for four elements. This is a generic collection default: it applies uniformly to all default-constructed lists and depends only on the existing container API. `ArrayList(initialCapacity)` and `ArrayList(elements)` are unchanged. The rationale is the profile’s many short-lived, small containers and their unused backing slots.

## Correctness checks

The expanded `stdlib_collections` test creates a nullable default list, inserts five elements to cross the initial capacity boundary, inserts and removes at an index, traverses through `joinToString`, and constructs `ArrayList<Int>(0)`. Baseline and candidate produced the same expected output. The JSON benchmark and a mixed collection workload also produced identical output.

## Matched measurement

Both variants used the same stage3 compiler (`f3c1d043…`) with `--backend=llvm-api --opt=O3` and the same O2 runtime archive (`782e8077…`). Runtime objects were compiled with `clang -std=c17 -O2 -I runtime/include -c <runtime/src file> -o <object>` and archived with `ar rcs`; executable links used `clang <app.o> <same runtime archive> -lm -Wl,-dead_strip -o <executable>`. Every stdlib source was identical except `ArrayList.kt`; hashes of the two source versions and all generated objects are recorded in [campaign-arraylist-capacity.json](../../bench/results/campaign-arraylist-capacity.json). Disassembly of `_kc_kotlin_Dcollections_DArrayList_C` confirms the baseline passes 10 and the candidate passes 4 to `ref_array_new`.

Ten paired runs used `/usr/bin/time -lp` for retired instructions and maximum RSS, with Python `perf_counter` around each invocation for higher-resolution wall time. The JSON workload’s median wall time changed from 0.49065 s to 0.46747 s, retired instructions from 7.890B to 7.653B, and maximum RSS from 166.1 MB to 145.5 MB. The additional mixed workload creates 50,000 three-element lists and one 100,000-element list; its median wall time changed from 18.96 ms to 18.17 ms, retired instructions from 300.8M to 288.3M, and maximum RSS from 24.95 MB to 21.84 MB. The short collection correctness test’s instruction count was effectively flat; its wall-time difference is below useful resolution.

## Allocation counter scope

The matched `MK_STATS` runs report five additional managed allocations / 240 bytes for JSON and two / 96 bytes for the mixed workload with capacity 4, consistent with the large list’s extra growth steps. Managed-object peak bytes stayed equal. `MK_STATS` counts managed Mini objects, but does not count the external `calloc` buffers used for `MkRefArray` backing slots; its peak-byte counter therefore cannot represent the backing-buffer memory change measured by maximum RSS. The stats executables were linked from the corresponding baseline and candidate app objects, and disassembly confirms those objects pass 10 and 4 respectively to `ref_array_new`. The raw stats output, variant object mapping, runtime/compiler/source hashes, commands, and per-run data are in the JSON report.
