#!/usr/bin/env python3
"""Runs the official Kotlin "box" codegen tests (JetBrains/kotlin, compiler/testData/codegen/box + boxInline) through minic.

Each applicable test defines `fun box(): String`; the harness compiles the test (all `// FILE:` parts, plus a generated
`main` wrapper that prints `box()`), runs the binary and expects the output `OK`.

Usage (normally through tools/conformance/run.sh):
  run.py [--minic PATH] [--tests DIR] [--jobs N] [--timeout S] [--filter SUBSTR] [--limit N] [--out DIR] [--list-skips]

Outcomes: pass, compile-error (minic exit 1), ice (minic crashed / exit >= 2 / signal), runtime-fail (wrong output or non-zero
exit), crash (the program died from a signal), timeout (compile or run), skipped (not applicable, see SKIP RULES below).
"""
import threading, argparse, json, os, re, shutil, subprocess, sys, tempfile, time, collections
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))

# ---------------------------------------------------------------------------------------------------------------------
# SKIP RULES (documented in docs/conformance.md). A test is "not applicable" when it exercises the JVM rather than the
# language: it is excluded from the denominator of the pass rate. Every rule has a short reason id used in the report.
# ---------------------------------------------------------------------------------------------------------------------
SKIP_DIRS = {  # first path components below box/ or boxInline/ that are JVM/other-platform only
    'reflection', 'javaInterop', 'fullJdk', 'invokedynamic', 'compileKotlinAgainstKotlin', 'multiplatform',
    'synthetic', 'jdk', 'java8', 'jvm8', 'jvm9', 'jvmStatic', 'jvmField', 'jvmName', 'jvmOverloads', 'jvmDefault',
    'jvmRecord', 'jvmPackageName', 'bytecodeListing', 'bytecodeText', 'serialization', 'properties/jvm',
    'wasm-new-exception-handling', 'js', 'ranges/javaInterop', 'annotations/kClass', 'enum/javaEnum',
}
JVM_TEXT = [  # regexes over the whole source; a hit means a JVM-only API
    (re.compile(r'\bjava(x)?\.[a-z]'), 'jvm-api'),
    (re.compile(r'\.javaClass\b|::class\.java\b|\.java\b\s*[.)]|\bjavaObjectType|\bjavaPrimitiveType'), 'jvm-api'),
    (re.compile(r'\bkotlin\.reflect\b|\bkotlin\.jvm\.'), 'jvm-api'),
    (re.compile(r'@(JvmStatic|JvmField|JvmName|JvmOverloads|JvmDefault|JvmRecord|JvmInline\s+value\s+class\s+\w+\s*<)|@file:JvmName|@Throws\b|@Volatile\b|@Synchronized\b|@Strictfp\b'), None),
    (re.compile(r'\bClass\.forName\b|\bSystem\.(getProperty|getenv|identityHashCode|setOut|setErr|gc)\b|\bThread\b|\bThreadLocal\b|\bRuntime\.getRuntime'), 'jvm-api'),
    (re.compile(r'\bclass\.java\b|\bMethod\b|\bgetDeclared|\bcheckcast\b|\binvokevirtual\b'), None),
]
JVM_TEXT = [(r, n) for r, n in JVM_TEXT if n]
DIRECTIVE_SKIPS = [  # (regex over the directive lines, reason)
    (re.compile(r'^// (WITH_REFLECT|FULL_JDK|FULL_JDK_WITH_REFLECT|WITH_JDK|JVM_TARGET|JVM_DEFAULT_MODE|JVM_ABI_K1_K2_DIFF|SKIP_JDK6|CHECK_BYTECODE_TEXT|'
                r'CHECK_BYTECODE_LISTING|CHECK_BYTECODE|ASSERTIONS_MODE|SAM_CONVERSIONS|LAMBDAS|SKIP_DCE_DRIVEN|ENABLE_JVM_PREVIEW|'
                r'FIR_DUMP|DUMP_IR|WITH_SIGNATURES|IGNORE_DEXING|CHECK_CALLED_IN_SCOPE|CHECK_NOT_CALLED|CHECK_CONTAINS_NO_CALLS|'
                r'TARGET_PLATFORM: JVM|USE_PSI_CLASS_FILES_READING|WITH_STDLIB_JDK8)\b', re.M), 'jvm-directive'),
    (re.compile(r'^// (MODULE|DEPENDENCY|FRIENDS|DEPENDS_ON):', re.M), 'multi-module'),
    (re.compile(r'^// (DIAGNOSTICS|RENDER_DIAGNOSTICS_FULL_TEXT|RENDER_ALL_DIAGNOSTICS_FULL_TEXT|API_VERSION|NO_NEW_JAVA_ANNOTATIONS|EXPECT_ACTUAL_LINKER|ENABLE_MULTIPLATFORM|WITH_FIR_TEST_COMPILER_PLUGIN):', re.M), 'compiler-config'),
]


def directives(src):
    return re.findall(r'^// ([A-Z][A-Z0-9_]*)(?::\s*(.*))?$', src, re.M)


def skip_reason(path, rel, src):
    """None when applicable, else a short reason id."""
    parts = rel.split('/')
    for p in parts[1:-1]:
        if p in SKIP_DIRS or p.startswith('jvm') or p.startswith('java') or p.startswith('wasm') or p.startswith('js'):
            return 'jvm-dir'
    ds = directives(src)
    for name, val in ds:
        val = val or ''
        toks = {t.strip() for t in re.split(r'[,\s]+', val) if t.strip()}
        if name == 'TARGET_BACKEND' and 'NATIVE' not in toks:
            return 'target-backend'
        if name in ('IGNORE_BACKEND', 'IGNORE_BACKEND_K2', 'IGNORE_BACKEND_K1') and ('NATIVE' in toks or 'ANY' in toks) and name != 'IGNORE_BACKEND_K1':
            return 'ignored-on-native'
        if name == 'DONT_TARGET_EXACT_BACKEND' and 'NATIVE' in toks:
            return 'ignored-on-native'
        if name == 'TARGET_PLATFORM' and 'JVM' in toks and 'Common' not in toks:
            return 'target-backend'
    for rx, why in DIRECTIVE_SKIPS:
        if rx.search(src):
            return why
    # opt-in compiler mode: Kotlin/Native does not check unchecked generic casts (`x as T`) unless built with this flag
    if re.search(r'^// FREE_COMPILER_ARGS:.*-Xbinary=genericSafeCasts=true', src, re.M):
        return 'non-default-flag'
    if re.search(r'^// FILE: .*\.(java|js|c|h|m|mm|def|xml|json|txt|properties)\s*$', src, re.M):
        return 'foreign-source'
    for rx, why in JVM_TEXT:
        if rx.search(src):
            return why
    if not re.search(r'\bfun\s+box\s*\(\s*\)', src):
        return 'no-box'
    return None


# ---------------------------------------------------------------------------------------------------------------------
# NATIVE-PARITY RULES. A second, stricter report (the main pass rate is unchanged): it also leaves out the applicable tests
# that JetBrains itself documents as failing / not run on Kotlin/Native 2.0.21 (K2). The skip rules above already cover
# `TARGET_BACKEND` without NATIVE, `IGNORE_BACKEND(_K2): NATIVE|ANY` and `DONT_TARGET_EXACT_BACKEND: NATIVE`; the K1-only forms
# (`IGNORE_BACKEND_K1: NATIVE`, `IGNORE_NATIVE_K1`) do not apply to K2 and are not excluded.
# ---------------------------------------------------------------------------------------------------------------------
PARITY_RULES = [
    # unconditional `// IGNORE_NATIVE` / `// DISABLE_NATIVE` (the conditional forms name a cache / optimisation mode or a target,
    # e.g. `IGNORE_NATIVE: cacheMode=STATIC_EVERYWHERE`, and do not apply to a plain build)
    (re.compile(r'^// (IGNORE_NATIVE|DISABLE_NATIVE)\s*$', re.M), 'native-ignored'),
    # the test's author notes that Kotlin/Native fails the test (`// NATIVE error: ...`, `// K2/NATIVE fail: KT-...`)
    (re.compile(r'^// (NATIVE error\b|K2/NATIVE fail\b|NATIVE fail\b)', re.M), 'native-known-failure'),
]


def parity_exclusion(src):
    for rx, why in PARITY_RULES:
        if rx.search(src):
            return why
    return None


def split_files(src):
    """-> list of (name, text). Text before the first `// FILE:` is the file `test.kt`."""
    parts = re.split(r'^// FILE: (.+?)\s*$', src, flags=re.M)
    files = []
    if parts[0].strip():
        files.append(('test.kt', parts[0]))
    for i in range(1, len(parts), 2):
        files.append((parts[i], parts[i + 1]))
    return files


def box_package(files):
    # a top-level `fun box` (no indentation) wins over a member `fun box` of a class in an earlier file
    for indent in (r'', r'[ \t]*'):
        for _, text in files:
            if re.search(r'^' + indent + r'(?:(?:public|internal|private)\s+)?(?:(?:inline|suspend|tailrec|operator)\s+)*fun\s+box\s*\(\s*\)', text, re.M):
                m = re.search(r'^\s*package\s+([\w.`]+)', text, re.M)
                return m.group(1) if m else ''
    return None


HELPERS = os.path.join(HERE, 'helpers.kt')
_KT = {}


def kotlin_test_sources():
    """The `kotlin.test` subset that `mini test` ships (compiler/mini-tool/src/TestCommand.kt): (annotations, api) texts."""
    if not _KT:
        t = open(os.path.join(HERE, '..', '..', 'compiler', 'mini-tool', 'src', 'TestCommand.kt'), encoding='utf-8').read()
        for k in ('ANNOTATIONS', 'SOURCE'):
            m = re.search(r'const val %s = """(.*?)"""' % k, t, re.S)
            _KT[k] = m.group(1).replace("${'$'}", '$')
    return _KT['ANNOTATIONS'], _KT['SOURCE']


DIAG_MARKUP = re.compile(r'<!(?:[A-Z_0-9]+(?:\([^)]*\))?(?:,\s*)?)+!>|<!>')


def classify_ice(err, rc):
    return 'ice'


def norm_msg(line):
    """Normalise a diagnostic line so that clusters group by the real cause."""
    line = re.sub(r'^.*?(error|warning)(\[[A-Z0-9]+\])?:\s*', r'', line)
    line = re.sub(r'/[^\s:]+\.kt(:\d+(:\d+)?)?', '<file>', line)
    line = re.sub(r'`[^`]*`', '`_`', line)
    line = re.sub(r"'[^']*'", "'_'", line)
    line = re.sub(r'"[^"]*"', '"_"', line)
    line = re.sub(r'\b\d+\b', 'N', line)
    return line.strip()[:160]


def first_error(err):
    for line in err.splitlines():
        if re.search(r'\berror\b', line, re.I) and not line.strip().startswith('-->'):
            return line
    for line in err.splitlines():
        if line.strip():
            return line
    return '(no output)'


def run_test(a, tc):
    path, rel = tc
    src = open(path, encoding='utf-8', errors='replace').read()
    res = {'test': rel, 'status': None}
    why = skip_reason(path, rel, src)
    if why:
        res.update(status='skipped', reason=why)
        return res
    files = split_files(src)
    pkg = box_package(files)
    if pkg is None:
        res.update(status='skipped', reason='no-box')
        return res
    work = tempfile.mkdtemp(prefix='kb-', dir=a.tmp)
    try:
        srcs = []
        for i, (name, text) in enumerate(files):
            if not name.endswith('.kt'):
                continue
            text = DIAG_MARKUP.sub('', text).replace('OPTIONAL_JVM_INLINE_ANNOTATION', '@kotlin.jvm.JvmInline')
            fp = os.path.join(work, '%d_%s' % (i, os.path.basename(name)))
            open(fp, 'w').write(text)
            srcs.append(fp)
        if re.search(r'^import helpers\.|\bhelpers\.', src) or 'WITH_COROUTINES' in src:
            srcs.append(HELPERS)
        if re.search(r'\bkotlin\.test\b', src):
            for j, txt in enumerate(kotlin_test_sources()):
                fp = os.path.join(work, 'kt_test%d.kt' % j)
                open(fp, 'w').write(txt)
                srcs.append(fp)
        call = (pkg + '.box()') if pkg else 'box()'
        main = os.path.join(work, '00_main.kt')
        open(main, 'w').write('fun main() {\n    kotlin.io.println(%s)\n}\n' % call)
        srcs.insert(0, main)  # sorts and comes first: it is the program's entry even when the test declares its own `fun main(args: Array<String>)`
        exe = os.path.join(work, 'a.out')
        t0 = time.time()
        try:
            p = subprocess.run([a.minic, 'build'] + srcs + ['-o', exe], capture_output=True, timeout=a.compile_timeout, cwd=work)
        except subprocess.TimeoutExpired:
            res.update(status='timeout', phase='compile', msg='compile timeout')
            return res
        res['compile_s'] = round(time.time() - t0, 2)
        err = (p.stderr + p.stdout).decode('utf-8', 'replace')
        if p.returncode != 0:
            fe = first_error(err)
            res.update(msg=norm_msg(fe), raw=fe[:300])
            res['status'] = 'compile-error' if p.returncode == 1 else 'ice'
            return res
        try:
            r = subprocess.run([exe], capture_output=True, timeout=a.timeout, cwd=work, stdin=subprocess.DEVNULL)
        except subprocess.TimeoutExpired:
            res.update(status='timeout', phase='run', msg='run timeout')
            return res
        out = r.stdout.decode('utf-8', 'replace').strip()
        if r.returncode == 0 and out == 'OK':
            res['status'] = 'pass'
        elif r.returncode < 0:
            res.update(status='crash', msg='signal %d' % -r.returncode, raw=(out + r.stderr.decode('utf-8', 'replace'))[:300])
        else:
            tail = (out or r.stderr.decode('utf-8', 'replace').strip()).splitlines()
            m = tail[-1] if tail else '(no output)'
            res.update(status='runtime-fail', msg=norm_msg(m) if r.returncode else ('box() returned: ' + m[:80]), raw=m[:300])
        return res
    finally:
        shutil.rmtree(work, ignore_errors=True)


def collect(root, flt):
    tests = []
    for top in ('box', 'boxInline'):
        base = os.path.join(root, top)
        for dp, dn, fn in os.walk(base):
            dn.sort()
            for f in sorted(fn):
                if f.endswith('.kt'):
                    p = os.path.join(dp, f)
                    rel = os.path.relpath(p, root)
                    if flt and not any(s in rel for s in flt):
                        continue
                    tests.append((p, rel))
    return tests


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--minic', default=os.environ.get('MINIC'))
    ap.add_argument('--tests', default=os.environ.get('KOTLIN_BOX'))
    ap.add_argument('--jobs', type=int, default=os.cpu_count() or 2)
    ap.add_argument('--timeout', type=int, default=10, help='seconds to run one binary')
    ap.add_argument('--compile-timeout', type=int, default=90)
    ap.add_argument('--filter', action='append', help='only tests whose path contains this (repeatable)')
    ap.add_argument('--retest', help='results.json of an earlier run: only run the tests that did not pass there')
    ap.add_argument('--limit', type=int, default=0)
    ap.add_argument('--out', default=os.path.join(HERE, 'results'))
    ap.add_argument('--tmp', default=os.environ.get('TMPDIR', '/tmp'))
    ap.add_argument('--tag', default='')
    ap.add_argument('--bucket-top', type=int, default=40)
    a = ap.parse_args()
    if not a.minic or not a.tests:
        sys.exit('need --minic and --tests (see run.sh)')
    tests = collect(a.tests, a.filter)
    prev = None
    if a.retest:
        prev = json.load(open(a.retest))['results']
        prev_by = {r['test']: r for r in prev}
        redo = {r['test'] for r in prev if r['status'] not in ('pass', 'skipped')}
        tests = [t for t in tests if t[1] in redo]
    if a.limit:
        tests = tests[:a.limit]
    t0 = time.time()
    results = []
    done = [0]
    lock = threading.Lock()

    def job(tc):
        r = run_test(a, tc)
        with lock:
            done[0] += 1
            if done[0] % 200 == 0:
                print('  %d/%d (%ds)' % (done[0], len(tests), time.time() - t0), file=sys.stderr, flush=True)
        return r

    with ThreadPoolExecutor(a.jobs) as ex:
        results = list(ex.map(job, tests))
    if a.retest:  # merge: everything that was not re-run keeps its earlier result
        new = {r['test']: r for r in results}
        results = [new.get(r['test'], r) for r in prev]
    report(a, results, time.time() - t0)


def annotate_parity(a, results):
    """Marks applicable tests that Kotlin/Native itself does not pass or run (`parity` = reason); computed from the sources."""
    for r in results:
        if r['status'] == 'skipped' or 'parity' in r:
            continue
        try:
            src = open(os.path.join(a.tests, r['test']), encoding='utf-8', errors='replace').read()
        except OSError:
            continue
        r['parity'] = parity_exclusion(src)


def report(a, results, secs):
    annotate_parity(a, results)
    os.makedirs(a.out, exist_ok=True)
    cnt = collections.Counter(r['status'] for r in results)
    skips = collections.Counter(r.get('reason') for r in results if r['status'] == 'skipped')
    applicable = len(results) - cnt['skipped']
    passed = cnt['pass']
    buckets = collections.defaultdict(list)
    for r in results:
        if r['status'] not in ('pass', 'skipped'):
            buckets[(r['status'], r.get('msg', ''))].append(r['test'])
    par_ex = [r for r in results if r['status'] != 'skipped' and r.get('parity')]
    par_app = applicable - len(par_ex)
    par_pass = sum(1 for r in results if r['status'] == 'pass' and not r.get('parity'))
    par_reasons = collections.Counter(r['parity'] for r in par_ex)
    summ = {'native_parity': {'applicable': par_app, 'pass': par_pass, 'excluded': len(par_ex), 'excluded_reasons': dict(par_reasons),
                              'pass_rate': round(100.0 * par_pass / par_app, 2) if par_app else 0}, 'total': len(results), 'applicable': applicable, 'pass': passed, 'skipped': cnt['skipped'],
            'compile-error': cnt['compile-error'], 'ice': cnt['ice'], 'runtime-fail': cnt['runtime-fail'],
            'crash': cnt['crash'], 'timeout': cnt['timeout'], 'pass_rate': round(100.0 * passed / applicable, 2) if applicable else 0,
            'seconds': round(secs), 'skip_reasons': dict(skips)}
    bl = sorted(buckets.items(), key=lambda kv: -len(kv[1]))
    data = {'summary': summ, 'buckets': [{'status': k[0], 'message': k[1], 'count': len(v), 'tests': v} for k, v in bl],
            'results': results}
    tag = ('-' + a.tag) if a.tag else ''
    json.dump(data, open(os.path.join(a.out, 'results%s.json' % tag), 'w'), indent=1)
    md = ['# Kotlin box conformance results', '',
          '| | count |', '|---|---:|',
          '| total test files | %d |' % summ['total'], '| skipped (not applicable) | %d |' % summ['skipped'],
          '| **applicable** | **%d** |' % applicable, '| **pass** | **%d (%.2f%%)** |' % (passed, summ['pass_rate']),
          '| compile-error | %d |' % cnt['compile-error'], '| ice (compiler crash) | %d |' % cnt['ice'],
          '| runtime-fail | %d |' % cnt['runtime-fail'], '| crash (signal) | %d |' % cnt['crash'],
          '| timeout | %d |' % cnt['timeout'], '',
          '## Native parity (also excludes tests Kotlin/Native itself does not pass or run)', '',
          '| | count |', '|---|---:|', '| applicable (above) | %d |' % applicable]
    md += ['| excluded: %s | %d |' % kv for kv in sorted(par_reasons.items())]
    md += ['| **native-parity applicable** | **%d** |' % par_app,
           '| **native-parity pass** | **%d (%.2f%%)** |' % (par_pass, summ['native_parity']['pass_rate']), '',
           'Wall time: %ds.' % secs, '', '## Skips', '', '| reason | count |', '|---|---:|']
    md += ['| %s | %d |' % kv for kv in skips.most_common()]
    md += ['', '## Failure buckets (top %d)' % a.bucket_top, '', '| count | status | first diagnostic | example |', '|---:|---|---|---|']
    for (st, msg), ts in bl[:a.bucket_top]:
        md.append('| %d | %s | `%s` | %s |' % (len(ts), st, msg.replace('|', '\\|'), ts[0]))
    open(os.path.join(a.out, 'summary%s.md' % tag), 'w').write('\n'.join(md) + '\n')
    print('\n'.join(md[:20]))
    print('pass %d / %d applicable (%.2f%%)' % (passed, applicable, summ['pass_rate']))
    print('native-parity: pass %d / %d (%.2f%%)' % (par_pass, par_app, summ['native_parity']['pass_rate']))


if __name__ == '__main__':
    main()
