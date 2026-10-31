#!/bin/sh
# Regression test for macOS dead-code stripping in native minic builds.
set -eu

if [ "$(uname -s)" != Darwin ]; then
    echo "skip: dead-strip executable regression requires macOS"
    exit 0
fi

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
install_dir=${1:-/tmp/mini-size-optimized}
minic="$install_dir/bin/minic"
hello="$repo_dir/examples/hello.kt"

if [ ! -x "$minic" ]; then
    echo "staged minic not found: $minic" >&2
    echo "usage: $0 [staged-install-directory]" >&2
    exit 1
fi

tmp=$(mktemp -d "${TMPDIR:-/tmp}/mini-dead-strip-test.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

cat >"$tmp/expected" <<'EOF'
Hello, Mini!
1 * 1 = 1
2 * 2 = 4
3 * 3 = 9
EOF

check_build() {
    name=$1
    platform=$2
    backend=$3
    opt=$4
    expects_flag=$5
    shift 5

    out="$tmp/$name"
    log="$tmp/$name.log"
    if [ "$backend" = c ]; then
        "$@" "$minic" build --verbose --backend=c "$opt" "$hello" -o "$out" >"$tmp/$name.stdout" 2>"$log"
    else
        "$@" "$minic" build --verbose "$opt" "$hello" -o "$out" >"$tmp/$name.stdout" 2>"$log"
    fi

    if [ "$expects_flag" = yes ]; then
        if ! grep -F -- '-Wl,-dead_strip' "$log" >/dev/null; then
            echo "$name: expected -Wl,-dead_strip in verbose link command" >&2
            cat "$log" >&2
            exit 1
        fi
    elif grep -F -- '-Wl,-dead_strip' "$log" >/dev/null; then
        echo "$name: unexpected -Wl,-dead_strip in verbose link command" >&2
        cat "$log" >&2
        exit 1
    fi

    "$out" >"$tmp/$name.output"
    if ! cmp -s "$tmp/expected" "$tmp/$name.output"; then
        echo "$name: hello output did not match" >&2
        diff -u "$tmp/expected" "$tmp/$name.output" >&2 || true
        exit 1
    fi
    echo "ok: $name ($platform, $backend, $opt)"
}

# Exercise the real host platform for optimized API-default and explicit C builds.
check_build darwin-api optimized api -O2 yes env
check_build darwin-c optimized c -O2 yes env

# Debug and explicit O0 builds keep the normal linker behavior on macOS.
check_build darwin-api-debug debug api -g no env
check_build darwin-c-debug debug c -g no env
check_build darwin-api-o0 o0 api -O0 no env
check_build darwin-c-o0 o0 c -O0 no env

# Pretend uname reports Linux; keep the actual compiler/linker available on PATH.
mkdir "$tmp/mock-bin"
cat >"$tmp/mock-bin/uname" <<'EOF'
#!/bin/sh
if [ "${1:-}" = -s ]; then
    echo Linux
else
    exec /usr/bin/uname "$@"
fi
EOF
chmod +x "$tmp/mock-bin/uname"
PATH="$tmp/mock-bin:$PATH"
export PATH
check_build linux-api linux api -O2 no env
check_build linux-c linux c -O2 no env

echo "dead-strip regression checks passed"
