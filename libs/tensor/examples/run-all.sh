#!/usr/bin/env bash
# Runs every example with `mini run` and compares the output with expected.txt (use --update to rewrite them).
set -u
cd "$(dirname "$0")"
MINI="${MINI:-$(cd ../../.. && pwd)/mini}"
status=0
for d in */; do
    d="${d%/}"
    [ -f "$d/mini.toml" ] || continue
    out="$( (cd "$d" && "$MINI" run -q 2>&1) )"
    if [ "${1:-}" = "--update" ]; then printf '%s\n' "$out" > "$d/expected.txt"; echo "updated $d"; continue; fi
    if [ "$out" = "$(cat "$d/expected.txt")" ]; then echo "ok   $d"; else echo "FAIL $d"; status=1; fi
done
exit $status
