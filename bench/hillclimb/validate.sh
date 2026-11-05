#!/usr/bin/env bash
# The validation list of the hillclimb (a change counts only when all of it passes); logs go to $LOGDIR (default build/hc/validate):
#   runtime unit tests (ASan/UBSan/TSan), scripts/test-e2e.sh (c leak memcheck memrun strict strict-leak strict-llvm-leak multifile
#   mlib + llvm llvm-leak), the LLVM-API suite, `mini test` of compiler/, scripts/strict-corpus.sh --run.
# The compiler bootstraps (compiler/bootstrap.sh, C and --backend=llvm-api) are separate: bench/hillclimb/bootstrap.sh.
#   bench/hillclimb/validate.sh [quick]      quick: only the runtime tests and the c + llvm-api suites
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
LOGDIR="${LOGDIR:-$ROOT/build/hc/validate}"
mkdir -p "$LOGDIR"
cd "$ROOT" || exit 2
fail=0
step() { # name command...
    local name="$1"; shift
    echo "== $name"
    if "$@" > "$LOGDIR/$name.log" 2>&1; then echo "   ok"; else echo "   FAILED (see $LOGDIR/$name.log)"; tail -5 "$LOGDIR/$name.log"; fail=1; fi
}
step runtime runtime/run_tests.sh
if [ "${1:-}" = quick ]; then
    step e2e-c scripts/test-e2e.sh --suite c
else
    step e2e scripts/test-e2e.sh --suite c --suite leak --suite memcheck --suite memrun --suite strict --suite strict-leak \
        --suite strict-llvm-leak --suite multifile --suite mlib --suite llvm --suite llvm-leak
fi
MINIC_API="$ROOT/build/api/bin/minic-native" step e2e-llvm-api scripts/test-e2e.sh --suite llvm-api
if [ "${1:-}" != quick ]; then
    step strict-corpus scripts/strict-corpus.sh --run
fi
exit $fail
