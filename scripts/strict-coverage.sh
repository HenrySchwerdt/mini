#!/usr/bin/env bash
# Strict-mode coverage: how much ordinary Kotlin does the Strict memory checker accept?
#
#   scripts/strict-coverage.sh                      e2e testdata + samples + 300 conformance box tests; prints the tables
#   scripts/strict-coverage.sh --set testdata       one set: testdata | samples | conformance | all
#   scripts/strict-coverage.sh --sample 500         size of the conformance sample (the first N passing tests by name hash)
#   scripts/strict-coverage.sh --list               also one line per program (name, verdict, class, cause)
#   scripts/strict-coverage.sh --json out.json      machine-readable results
#
# Every program is first checked unchanged (a program that does not compile standalone is not counted), then with
# `@file:MemoryMode(Mode.Strict)` added to each user file. Classes: (a) correct rejection, (b) mini limitation, (c) ICE;
# see tools/conformance/strict_coverage.py and docs/memory-model.md ("Strict-mode coverage").
# The conformance sample needs the Kotlin box tests (tools/conformance/run.sh fetches them into ~/.cache/kotlin-box).
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NATIVE="${MINI_NATIVE_DIR:-$ROOT/build/native}"
exec python3 -I "$ROOT/tools/conformance/strict_coverage.py" --minic "${MINIC_NATIVE:-$NATIVE/bin/minic-native}" \
    --mlib "$NATIVE/lib/stdlib.mlib" --id "$(cat "$NATIVE/VERSION")" "$@"
