#!/usr/bin/env bash
# Cross-language benchmark suite; see bench/run.py (options) and docs/benchmarks.md.
#   bench/run.sh [--reps N] [--only binarytrees,json] [--langs c,rust,mini-managed] [--stats] [--out DIR]
# Tools are taken from PATH (clang, rustc, go, java, kotlinc, kotlinc-native) or from CC / RUSTC / GO / JAVA / KOTLINC / KOTLINC_NATIVE.
exec python3 "$(dirname "$0")/run.py" "$@"
