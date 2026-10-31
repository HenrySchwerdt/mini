#!/usr/bin/env bash
# Verify that a staged macOS release compiler loses only local symbols.
# Usage: test-package-release-symbols.sh <unstripped-stage3> <stripped-staged-copy>
set -euo pipefail

if [ "$(uname -s)" != Darwin ]; then
    echo "skip: Mach-O release symbol test requires macOS"
    exit 0
fi

BASELINE="${1:?usage: test-package-release-symbols.sh <unstripped-stage3> <stripped-staged-copy>}"
STAGED="${2:?usage: test-package-release-symbols.sh <unstripped-stage3> <stripped-staged-copy>}"
[ -f "$BASELINE" ] || { echo "missing unstripped compiler: $BASELINE" >&2; exit 1; }
[ -f "$STAGED" ] || { echo "missing staged compiler: $STAGED" >&2; exit 1; }
for tool in nm dyld_info otool; do
    command -v "$tool" >/dev/null 2>&1 || { echo "required macOS tool not found: $tool" >&2; exit 1; }
done

WORK="$(mktemp -d "${TMPDIR:-/tmp}/mini-release-symbol-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT HUP INT TERM

nm_symbols() {
    nm -gU "$1" | awk '{ print $NF }' | LC_ALL=C sort -u
}

dyld_exports() {
    dyld_info -exports "$1" | awk '$1 ~ /^0x[[:xdigit:]]+$/ { print $2 }' | LC_ALL=C sort -u
}

local_symbol_count() {
    otool -l "$1" | awk '
        $1 == "cmd" && $2 == "LC_DYSYMTAB" { in_dysymtab = 1; next }
        in_dysymtab && $1 == "nlocalsym" { print $2; found = 1; exit }
        in_dysymtab && $1 == "cmd" { in_dysymtab = 0 }
        END { if (!found) exit 1 }
    '
}

nm_symbols "$BASELINE" > "$WORK/nm.before"
nm_symbols "$STAGED" > "$WORK/nm.after"
cmp "$WORK/nm.before" "$WORK/nm.after" || {
    echo "staged compiler's externally defined Mach-O symbols differ from the bootstrap compiler" >&2
    exit 1
}

dyld_exports "$BASELINE" > "$WORK/exports.before"
dyld_exports "$STAGED" > "$WORK/exports.after"
cmp "$WORK/exports.before" "$WORK/exports.after" || {
    echo "staged compiler's dyld export trie differs from the bootstrap compiler" >&2
    exit 1
}

BEFORE_LOCAL="$(local_symbol_count "$BASELINE")"
AFTER_LOCAL="$(local_symbol_count "$STAGED")"
[ "$AFTER_LOCAL" -lt "$BEFORE_LOCAL" ] || {
    echo "strip -x did not reduce local symbols ($BEFORE_LOCAL -> $AFTER_LOCAL)" >&2
    exit 1
}
BEFORE_BYTES="$(stat -f '%z' "$BASELINE")"
AFTER_BYTES="$(stat -f '%z' "$STAGED")"
[ "$AFTER_BYTES" -lt "$BEFORE_BYTES" ] || {
    echo "stripped staged compiler is not smaller ($BEFORE_BYTES -> $AFTER_BYTES bytes)" >&2
    exit 1
}

printf 'macOS staged compiler strip: %s -> %s bytes; local symbols %s -> %s\n' \
    "$BEFORE_BYTES" "$AFTER_BYTES" "$BEFORE_LOCAL" "$AFTER_LOCAL"
printf 'external symbols: %s names, SHA-256 ' "$(wc -l < "$WORK/nm.before" | tr -d ' ')"
shasum -a 256 "$WORK/nm.before" | awk '{ print $1 }'
printf 'dyld exports: %s names, SHA-256 ' "$(wc -l < "$WORK/exports.before" | tr -d ' ')"
shasum -a 256 "$WORK/exports.before" | awk '{ print $1 }'
