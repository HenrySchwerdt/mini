#!/usr/bin/env bash
# Builds libminirt with clang + sanitizers and runs all tests.
#   ASan/UBSan pass over every test, then a TSan pass over the threading test.
#   The threading test uses libuv: set LIBUV_ROOT to a prefix with include/uv.h and
#   lib/libuv.a (or libuv.so) if libuv is not on the default compiler paths.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
CC="${CC:-clang}"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

# Some clang installs lack the sanitizer runtimes; fall back to gcc then.
probe() { echo 'int main(void){return 0;}' >"$out/probe.c"
          "$CC" -fsanitize="$1" "$out/probe.c" -o "$out/probe" >/dev/null 2>&1; }
if [ -z "${CC_FORCED:-}" ] && { ! probe address,undefined || ! probe thread; }; then
    echo "note: $CC cannot link sanitizer runtimes; falling back to gcc" >&2
    CC=gcc
fi

cflags=(-std=c17 -g -O1 -Wall -Wextra -Werror -I"$here/include" -I"$here/src")
lib_srcs=()
for f in "$here"/src/*.c; do
    [ "$(basename "$f")" = entry.c ] || lib_srcs+=("$f")
done
entry_src="$here/src/entry.c"
fail=0

build_run() { # name sanitizer extra-sources...
    local name="$1" san="$2"
    shift 2
    "$CC" "${cflags[@]}" -fsanitize="$san" -fno-omit-frame-pointer \
        "$here/tests/$name.c" "${lib_srcs[@]}" "$@" -lm -lpthread -o "$out/$name.$san"
}

compare() { # name actual expected-file
    if ! diff -u "$3" "$2" >&2; then echo "FAIL: $1 output mismatch" >&2; fail=1; fi
}

san="address,undefined"
for t in "$here"/tests/test_*.c; do
    name="$(basename "$t" .c)"
    case "$name" in
    test_threads) continue ;; # handled below (ASan + TSan)
    test_entry) build_run "$name" "$san" "$entry_src" ;;
    *) build_run "$name" "$san" ;;
    esac
    echo "== $name ($san)"
    "$out/$name.$san" >"$out/$name.stdout" || { echo "FAIL: $name" >&2; fail=1; }
    [ -f "$here/tests/expected/$name.out" ] && compare "$name" "$out/$name.stdout" "$here/tests/expected/$name.out"
done

uv_flags=()
if [ -n "${LIBUV_ROOT:-}" ]; then uv_flags=(-I"$LIBUV_ROOT/include" -L"$LIBUV_ROOT/lib"); fi
for s in "$san" thread; do
    echo "== test_threads ($s)"
    build_run test_threads "$s" "${uv_flags[@]}" -luv
    "$out/test_threads.$s" || { echo "FAIL: test_threads ($s)" >&2; fail=1; }
done

echo "== check_panic"
build_run check_panic "$san" "$entry_src"
set +e
(exec "$out/check_panic.$san" >"$out/panic.out" 2>"$out/panic.err") 2>/dev/null
status=$?
set -e
[ "$status" -ne 0 ] || { echo "FAIL: panic did not abort" >&2; fail=1; }
printf 'null\n' | diff -u - "$out/panic.out" >&2 || { echo "FAIL: panic stdout not flushed" >&2; fail=1; }
grep -qx 'fatal: boom' "$out/panic.err" || { echo "FAIL: panic stderr" >&2; fail=1; }

if [ "$fail" -ne 0 ]; then echo "minirt: TESTS FAILED" >&2; exit 1; fi
echo "minirt: all tests passed"
