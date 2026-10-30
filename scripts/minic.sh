#!/bin/sh
# minic: the Mini compiler driver (Kotlin to a native binary).
#
# This is bin/minic of a release (scripts/package-release.sh copies it). It runs the self-hosted compiler
# bin/minic-native (Kotlin to C), then the system C compiler with the bundled runtime lib/libminirt.a.
#
#   minic build foo.kt            minic run foo.kt [-- args]       minic check foo.kt
#   minic emit-c foo.kt -o foo.c  minic --help                     minic --version
#
# Exit status: 0 success, 1 compile error (or an unusable environment), 2 usage error, 3 internal compiler error.
# `minic run` ends with the exit status of the program.
set -u

self="$0"
while [ -h "$self" ]; do
    dir="$(cd "$(dirname "$self")" && pwd)"
    self="$(readlink "$self")"
    case "$self" in /*) ;; *) self="$dir/$self" ;; esac
done
HOME_DIR="${MINI_HOME:-$(cd "$(dirname "$self")/.." && pwd)}"
NATIVE="$HOME_DIR/bin/minic-native"
REPORT_URL="https://github.com/HenrySchwerdt/mini/issues"
DOCS_URL="https://henryschwerdt.github.io/mini/"
VERSION="$(cat "$HOME_DIR/VERSION" 2>/dev/null || echo unknown)"
target() { cat "$HOME_DIR/TARGET" 2>/dev/null || echo "$(uname -s | tr '[:upper:]' '[:lower:]')-$(uname -m)"; }

# ---- colors (stderr), NO_COLOR and --color=auto|always|never ------------------------------------------------------

color=auto
use_color=0
RED=""; YEL=""; CYA=""; BLD=""; RST=""
setup_color() {
    case "$color" in
        always) use_color=1 ;;
        never) use_color=0 ;;
        *) if [ -t 2 ] && [ -z "${NO_COLOR:-}" ] && [ "${TERM:-}" != dumb ]; then use_color=1; else use_color=0; fi ;;
    esac
    if [ "$use_color" = 1 ]; then
        RED="$(printf '\033[1;31m')"; YEL="$(printf '\033[1;33m')"; CYA="$(printf '\033[1;36m')"
        BLD="$(printf '\033[1m')"; RST="$(printf '\033[0m')"
    else
        RED=""; YEL=""; CYA=""; BLD=""; RST=""
    fi
}
setup_color

error_format=human
json_escape() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | awk 'BEGIN { ORS = "" } { if (NR > 1) print "\\n"; print }'; }

# error <message>: one error line (a JSON object with --error-format=json)
error() {
    if [ "$error_format" = json ]; then
        printf '{"severity":"error","code":null,"message":"%s","file":null,"line":0,"col":0,"endCol":0,"label":"","secondary":[],"help":[],"docs":null}\n' "$(json_escape "$1")" >&2
    else
        printf '%serror%s%s: %s%s\n' "$RED" "$RST" "$BLD" "$1" "$RST" >&2
    fi
}
note() { [ "$error_format" = json ] || printf '%s\n' "$1" >&2; }
die() { error "$1"; shift; for l in "$@"; do note "  $l"; done; exit 1; }
usage_error() { error "$1"; shift; for l in "$@"; do note "  $l"; done; note "  run '${BLD}minic --help${RST}' for usage"; exit 2; }

# suggest <word> <candidate>...: the closest candidate (edit distance, or prefix), empty when nothing is close
suggest() {
    w="$1"; shift
    printf '%s\n' "$@" | awk -v w="$w" '
        function lev(a, b,    la, lb, i, j, c, m, d) {
            split("", d)
            la = length(a); lb = length(b)
            for (i = 0; i <= la; i++) d[i, 0] = i
            for (j = 0; j <= lb; j++) d[0, j] = j
            for (i = 1; i <= la; i++) for (j = 1; j <= lb; j++) {
                c = (substr(a, i, 1) == substr(b, j, 1)) ? 0 : 1
                m = d[i - 1, j] + 1
                if (d[i, j - 1] + 1 < m) m = d[i, j - 1] + 1
                if (d[i - 1, j - 1] + c < m) m = d[i - 1, j - 1] + c
                d[i, j] = m
            }
            return d[la, lb]
        }
        BEGIN { best = ""; bd = 100 }
        {
            dd = lev(w, $0)
            if (length(w) >= 3 && index($0, w) == 1) dd = 0.5
            if (dd < bd) { bd = dd; best = $0 }
        }
        END { lim = int(length(w) / 3); if (lim < 2) lim = 2; if (bd <= lim) print best }'
}

# ---- help ---------------------------------------------------------------------------------------------------------

DIAG_HELP="    --color=<when>          auto (default), always or never; NO_COLOR is respected with auto
    --error-format=<format> human (default) or json: one JSON object per diagnostic on stderr
    --error-limit=<n>       stop printing after n diagnostics (default 20, 0 = no limit)"

help_main() {
    cat <<EOF
minic $VERSION: Kotlin to a native binary

Usage: minic <command> [options] <file.kt>...

Commands:
    build      compile to an executable (default output: the file name without .kt)
    run        build into a temporary directory and run the program: minic run foo.kt [-- args]
    check      parse, type-check and run the memory checker only (no C compiler needed)
    emit-c     write the generated C file
    version    print the version
    help       print help for a command: minic help build

Options:
    -h, --help       print help (also: minic <command> --help)
    -V, --version    print the version

Examples:
    minic run hello.kt
    minic build -O3 app.kt util.kt -o app
    minic check --error-format=json app.kt

Exit status: 0 success, 1 compile error, 2 usage error, 3 internal compiler error.
Environment: CC (C compiler, default: cc, clang or gcc), MINI_HOME (default: the directory above bin/),
             NO_COLOR (disable colors), TMPDIR.
Documentation: $DOCS_URL
EOF
}

help_build() {
    cat <<EOF
Usage: minic build [options] <file.kt>... [-o <out>]

Compiles the Kotlin files (exactly one has fun main) to a native executable. Without -o the executable is
named after the first file (foo.kt gives ./foo).

Options:
    -o <out>                output executable
    -O0 -O1 -O2 -O3         C optimization level (default -O2)
    -g, --debug             debug build: -O0 with debug information
    --emit-c <out.c>        keep the generated C file there
    --cc <compiler>         C compiler (default: \$CC, then cc, clang, gcc)
    -Werror                 memory-checker warnings fail the build
    --no-rc-opt             skip the reference-count optimization pass
    -v, --verbose           print the commands that are run
    --time                  print how long the compiler, the C compiler and the linker took
$DIAG_HELP
    -h, --help              print this help

Exit status: 0 success, 1 compile error, 2 usage error, 3 internal compiler error.
EOF
}

help_run() {
    cat <<EOF
Usage: minic run [options] <file.kt>... [-- <program args>]

Builds the program into a temporary directory, runs it and removes it. The exit status is the program's.

Options:
    -O0 -O1 -O2 -O3         C optimization level (default -O1: shorter build, the program is usually short-lived)
    -g, --debug             debug build: -O0 with debug information
    --cc <compiler>         C compiler (default: \$CC, then cc, clang, gcc)
    -Werror                 memory-checker warnings fail the build
    --no-rc-opt             skip the reference-count optimization pass
    -v, --verbose           print the commands that are run
    --time                  print how long the compiler, the C compiler and the linker took
$DIAG_HELP
    -h, --help              print this help
EOF
}

help_check() {
    cat <<EOF
Usage: minic check [options] <file.kt>...

Runs the parser, the type checker and the Strict-mode memory checker; no C is generated and no C compiler is needed.
Exit status 1 when there is an error (warnings fail only with -Werror).

Options:
    -Werror                 warnings are errors
    --brief                 print only 'line:col: CODE' per memory diagnostic on stdout
$DIAG_HELP
    -h, --help              print this help

Memory diagnostics (M01xx, M02xx) are explained at ${DOCS_URL}reference/diagnostics/
EOF
}

help_emit_c() {
    cat <<EOF
Usage: minic emit-c [options] <file.kt>... [-o <out.c>]

Writes the generated C file (default: the first file's name with .c) and stops.

Options:
    -o <out.c>              output file
    -Werror                 memory-checker warnings fail the build
    --no-rc-opt             skip the reference-count optimization pass
$DIAG_HELP
    -h, --help              print this help
EOF
}

# ---- command line -------------------------------------------------------------------------------------------------

cmd="${1:-}"
case "$cmd" in
    "") help_main >&2; exit 2 ;;
    -h|--help) help_main; exit 0 ;;
    -V|--version|version) echo "minic $VERSION ($(target))"; exit 0 ;;
    help)
        case "${2:-}" in
            "") help_main ;;
            build) help_build ;;
            run) help_run ;;
            check) help_check ;;
            emit-c) help_emit_c ;;
            *) usage_error "no help for '$2'" "commands: build run check emit-c version" ;;
        esac
        exit 0 ;;
    build|run|check|emit-c) shift ;;
    -*)
        hint="$(suggest "$cmd" --help --version)"
        if [ -n "$hint" ]; then usage_error "unknown option '$cmd'" "did you mean '$hint'?"; fi
        usage_error "unknown option '$cmd'" ;;
    *.kt)
        usage_error "unknown command '$cmd'" "did you mean 'minic run $cmd' or 'minic build $cmd'?" ;;
    *)
        hint="$(suggest "$cmd" build run check emit-c version help)"
        if [ -n "$hint" ]; then usage_error "unknown command '$cmd'" "did you mean '$hint'?"; fi
        usage_error "unknown command '$cmd'" "commands: build run check emit-c version help" ;;
esac

case "$cmd" in
    build) valid="-o -O0 -O1 -O2 -O3 -g --debug --emit-c --cc -Werror --no-rc-opt -v --verbose --time --color --error-format --error-limit --help -h" ;;
    run) valid="-O0 -O1 -O2 -O3 -g --debug --cc -Werror --no-rc-opt -v --verbose --time --color --error-format --error-limit --help -h" ;;
    check) valid="-Werror --brief --color --error-format --error-limit --help -h" ;;
    emit-c) valid="-o -Werror --no-rc-opt --color --error-format --error-limit --help -h" ;;
esac

out=""; emit=""; opt=""; debug=0; cc_arg=""; werror=0; norc=0; verbose=0; timing=0; brief=0
limit=""
SRCLIST=""
nargs=$#
while [ "$nargs" -gt 0 ]; do
    a="$1"; shift; nargs=$((nargs - 1))
    name="$a"; val=""; has_val=0
    case "$a" in
        --*=*) name="${a%%=*}"; val="${a#*=}"; has_val=1 ;;
        -o?*) name="-o"; val="${a#-o}"; has_val=1 ;;
    esac
    case "$a" in
        --) break ;;
        -*)
            case " $valid " in
                *" $name "*) ;;
                *)
                    hint="$(suggest "$name" $valid)"
                    all_valid="$(printf '%s ' $valid)"
                    if [ -n "$hint" ]; then usage_error "unknown option '$name' for 'minic $cmd'" "did you mean '$hint'?"; fi
                    usage_error "unknown option '$name' for 'minic $cmd'" "options: $all_valid" ;;
            esac ;;
    esac
    # options that take a value: --opt=value or --opt value
    case "$name" in
        -o|--emit-c|--cc|--color|--error-format|--error-limit)
            if [ "$has_val" = 0 ]; then
                [ "$nargs" -gt 0 ] || usage_error "option '$name' needs a value"
                val="$1"; shift; nargs=$((nargs - 1))
            fi ;;
        -*)
            if [ "$has_val" = 1 ]; then usage_error "option '$name' takes no value"; fi ;;
    esac
    case "$name" in
        -h|--help) "help_$(printf '%s' "$cmd" | tr '-' '_')"; exit 0 ;;
        -o) out="$val" ;;
        --emit-c) emit="$val" ;;
        --cc) cc_arg="$val" ;;
        -O0|-O1|-O2|-O3) opt="$name" ;;
        -g|--debug) debug=1 ;;
        -Werror) werror=1 ;;
        --no-rc-opt) norc=1 ;;
        --brief) brief=1 ;;
        -v|--verbose) verbose=1 ;;
        --time) timing=1 ;;
        --color)
            case "$val" in auto|always|never) color="$val"; setup_color ;; *) usage_error "--color takes auto, always or never (got '$val')" ;; esac ;;
        --error-format)
            case "$val" in human|json) error_format="$val" ;; *) usage_error "--error-format takes human or json (got '$val')" ;; esac ;;
        --error-limit)
            case "$val" in ''|*[!0-9]*) usage_error "--error-limit takes a number, 0 for no limit (got '$val')" ;; *) limit="$val" ;; esac ;;
        -*) usage_error "unknown option '$a'" ;;
        *) SRCLIST="$SRCLIST$a
" ;;
    esac
done
# what is left in "$@" after `--` is for the program (minic run)
if [ "$nargs" -gt 0 ] && [ "$cmd" != run ]; then usage_error "unexpected arguments after '--' (they are only passed on by 'minic run')"; fi

[ -n "$SRCLIST" ] || usage_error "no input files" "usage: minic $cmd <file.kt>..."
first=""
oldifs="$IFS"; IFS='
'
for f in $SRCLIST; do
    [ -n "$first" ] || first="$f"
    if [ -d "$f" ]; then IFS="$oldifs"; die "'$f' is a directory, not a Kotlin file"; fi
    if [ ! -f "$f" ]; then
        IFS="$oldifs"
        if [ -f "$f.kt" ]; then die "file '$f' not found" "did you mean '$f.kt'?"; fi
        die "file '$f' not found"
    fi
    case "$f" in *.kt) ;; *) IFS="$oldifs"; die "'$f' is not a Kotlin source file (expected the extension .kt)" ;; esac
done
IFS="$oldifs"
base="$(basename "$first" .kt)"

# ---- the compiler -------------------------------------------------------------------------------------------------

if [ ! -x "$NATIVE" ]; then die "the compiler $NATIVE is missing" "MINI_HOME is '$HOME_DIR'; reinstall minic or set MINI_HOME to an unpacked release"; fi
if [ ! -d "$HOME_DIR/stdlib" ]; then die "the standard library $HOME_DIR/stdlib is missing" "reinstall minic or set MINI_HOME to an unpacked release"; fi

tmp=""
cleanup() { if [ -n "$tmp" ]; then rm -rf "$tmp"; fi; }
trap cleanup EXIT
trap 'cleanup; exit 130' INT
trap 'cleanup; exit 143' TERM
tmp="$(mktemp -d "${TMPDIR:-/tmp}/minic.XXXXXX")" || die "cannot create a temporary directory in ${TMPDIR:-/tmp}"

now() {
    if [ "$timing" = 0 ]; then echo 0; return; fi
    t="$(date +%s%N 2>/dev/null)"
    case "$t" in *N*|"") t="$(( $(date +%s) * 1000000000 ))" ;; esac
    echo "$t"
}
ms_since() { if [ "$timing" = 0 ]; then echo 0; else echo $(( ($(now) - $1) / 1000000 )); fi; }
phase_times=""
add_time() { phase_times="$phase_times  $1: $2 ms
"; }
show() { if [ "$verbose" = 1 ]; then printf '+ %s\n' "$*" | tr '\n' ' ' | sed 's/ $//' >&2; echo >&2; fi; }

# ice <what happened> [<file with the C compiler output>] [<extra line>]: an internal compiler error, never the user's mistake
ice() {
    error "internal compiler error: $1"
    if [ -n "${2:-}" ] && [ "$error_format" != json ]; then head -n 12 "$2" | sed 's/^/    | /' >&2; fi
    note "  This is a bug in minic, not in your program. Please report it at"
    note "    $REPORT_URL"
    note "  with this message, 'minic --version' ($VERSION, $(target)) and the source file."
    if [ -n "${3:-}" ]; then note "  $3"; fi
    exit 3
}

# runs the compiler front end: Kotlin to C (or only the checks)
frontend() { # <mode: check|build> [<c file>]
    t0="$(now)"
    oldifs="$IFS"; IFS='
'
    set -f
    if [ "$use_color" = 1 ]; then cmode=always; else cmode=never; fi
    NATIVE_ARGS="--color=$cmode
--error-format=$error_format
"
    if [ -n "$limit" ]; then NATIVE_ARGS="$NATIVE_ARGS--error-limit=$limit
"; fi
    if [ "$werror" = 1 ]; then NATIVE_ARGS="$NATIVE_ARGS-Werror
"; fi
    if [ "$norc" = 1 ]; then NATIVE_ARGS="$NATIVE_ARGS--no-rc-opt
"; fi
    # debug builds: #line directives, so the debugger, sanitizers and crash reports point to the .kt file
    if [ "$debug" = 1 ] && [ "$1" = build ]; then NATIVE_ARGS="$NATIVE_ARGS--line-directives
"; fi
    if [ "$1" = check ]; then
        NATIVE_ARGS="$NATIVE_ARGS--check
"
        if [ "$brief" = 1 ]; then NATIVE_ARGS="$NATIVE_ARGS--brief
"; fi
    else
        NATIVE_ARGS="$NATIVE_ARGS-o
$2
"
    fi
    STDFILES="$(find "$HOME_DIR/stdlib" -name '*.kt' | LC_ALL=C sort)"
    # shellcheck disable=SC2086
    set -- $NATIVE_ARGS $SRCLIST --stdlib $STDFILES
    show "$NATIVE" "$@"
    "$NATIVE" "$@"
    st=$?
    set +f
    IFS="$oldifs"
    add_time "compile (parse, check, lower, emit C)" "$(ms_since "$t0")"
    case "$st" in
        0) ;;
        1) exit 1 ;;
        3) exit 3 ;;
        2) ice "minic-native rejected the command line it was given" ;;
        *) ice "minic-native stopped unexpectedly (exit status $st)" ;;
    esac
}

if [ "$cmd" = check ]; then
    frontend check
    exit 0
fi

if [ "$cmd" = emit-c ]; then
    [ -n "$out" ] || out="$base.c"
    frontend build "$out"
    exit 0
fi

# ---- the C compiler -----------------------------------------------------------------------------------------------

find_cc() {
    if [ -n "$cc_arg" ]; then cc="$cc_arg"; src="--cc"; elif [ -n "${CC:-}" ]; then cc="$CC"; src="\$CC"; else cc=""; src=""; fi
    if [ -n "$cc" ]; then
        # the value may carry flags (CC="ccache clang"): only the first word has to exist
        first_word="${cc%% *}"
        if ! command -v "$first_word" >/dev/null 2>&1; then
            die "the C compiler '$first_word' (from $src) was not found" \
                "check the spelling, or install it; leave CC unset to let minic pick cc, clang or gcc"
        fi
        return
    fi
    for c in cc clang gcc; do
        if command -v "$c" >/dev/null 2>&1; then cc="$c"; return; fi
    done
    case "$(uname -s)" in
        Darwin) how="xcode-select --install     (the Xcode command line tools: clang)" ;;
        *)
            if command -v apt-get >/dev/null 2>&1; then how="sudo apt install clang      (or: build-essential for gcc)"
            elif command -v dnf >/dev/null 2>&1; then how="sudo dnf install clang"
            elif command -v pacman >/dev/null 2>&1; then how="sudo pacman -S clang"
            elif command -v apk >/dev/null 2>&1; then how="apk add clang musl-dev"
            else how="install clang or gcc with your package manager"
            fi ;;
    esac
    die "no C compiler found (looked for cc, clang and gcc)" \
        "minic translates Kotlin to C and needs a C compiler to produce the executable." \
        "install one:  $how" \
        "or point minic at yours:  CC=/path/to/compiler minic $cmd ...   (or --cc <compiler>)"
}
find_cc

if [ "$cmd" = run ]; then
    exe="$tmp/$base"
    [ -n "$opt" ] || opt="-O1"
else
    [ -n "$out" ] || out="./$base"
    if [ -d "$out" ]; then die "the output '$out' is a directory" "choose another name with -o"; fi
    exe="$out"
    [ -n "$opt" ] || opt="-O2"
fi
if [ "$debug" = 1 ]; then opt="-O0"; fi
if [ -n "$emit" ]; then cfile="$emit"; else cfile="$tmp/$base.c"; fi

frontend build "$cfile"

cflags="-std=c17 $opt -w"
if [ "$debug" = 1 ]; then cflags="$cflags -g -fno-omit-frame-pointer"; fi

# libraries asked for by @file:CLink("name") travel in the generated C as `mini:link name`
libs="$(sed -n 's|^/\* mini:link \(.*\) \*/$|-l\1|p' "$cfile" | tr '\n' ' ')"

# one C compiler call (compile and link). When it fails, a second call that only compiles tells the two apart: the C
# compiler rejecting the generated C is never the user's mistake (internal compiler error); a failing link is reported as such.
t1="$(now)"
show "$cc $cflags -I $HOME_DIR/include $cfile $HOME_DIR/lib/libminirt.a $libs -lm -o $exe"
# shellcheck disable=SC2086
if ! $cc $cflags -I "$HOME_DIR/include" "$cfile" "$HOME_DIR/lib/libminirt.a" $libs -lm -o "$exe" 2>"$tmp/cc.err"; then
    # shellcheck disable=SC2086
    if ! $cc $cflags -I "$HOME_DIR/include" -c "$cfile" -o "$tmp/prog.o" 2>"$tmp/cc2.err"; then
        kept="minic-ice-$base.c"
        cp "$cfile" "$kept" 2>/dev/null || kept=""
        ice "the C compiler ($cc) rejected the C code that minic generated" "$tmp/cc2.err" "${kept:+Please attach $kept (the generated C) as well.}"
    fi
    error "linking $exe failed"
    if [ "$error_format" != json ]; then head -n 12 "$tmp/cc.err" | sed 's/^/    | /' >&2; fi
    if [ -n "$libs" ]; then note "  the program links against: $libs (@file:CLink); install those libraries and their development files"; fi
    exit 1
fi
add_time "C compiler ($opt) and link" "$(ms_since "$t1")"

if [ "$timing" = 1 ]; then printf 'minic timings:\n%s' "$phase_times" >&2; fi

if [ "$cmd" = run ]; then
    "$exe" "$@"
    exit $?
fi
exit 0
