#!/usr/bin/env python3
"""Strict-mode coverage: how much ordinary Kotlin does the memory checker accept (docs/memory-model.md, "Strict-mode coverage")?

Every program is checked twice with `minic-native --check`: unchanged (Managed; a program that does not even compile there, for
example because it needs a C library, is not counted) and with `@file:MemoryMode(Mode.Strict)` as the first line of every
user file. The Strict outcome is classified:

  accepted   no diagnostic
  (a)        correct rejection: the program really has no ownership discipline (an escaping allocation without allocator or
             release, M0204/M0102 on code that never frees) or really breaks a rule (M0101/M0103/M0104/...)
  (b)        a mini limitation: a false positive, a missing summary or an unsupported construct (M0110 for a value that library
             code only reads, M0112 ...)
  (c)        ICE: the compiler crashed or the checker could not analyse Strict code

The split between (a) and (b) is by diagnostic code and message (RULES below), refined by reading the biggest clusters.

  strict_coverage.py --minic build/native/bin/minic-native --mlib build/native/lib/stdlib.mlib --id <compiler id>
                     [--set testdata|samples|conformance|all] [--sample N] [--jobs N] [--list] [--json out.json]
"""
import argparse, collections, hashlib, importlib.util, json, os, re, shutil, subprocess, sys, tempfile
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
STRICT = '@file:mini.memory.MemoryMode(mini.memory.Mode.Strict)\n'

# (code, message regex) -> (class, cause). First match wins; the cause names the group in the table.
RULES = [
    ('M0204', None, 'a', 'escaping allocation without allocator (class never opted into an allocator)'),
    ('M0102', r'owned value|from `', 'a', 'allocation never released (no free/use on the path)'),
    ('M0102', None, 'a', 'allocation never released (no free/use on the path)'),
    ('M0101', None, 'a', 'use after free'),
    ('M0103', None, 'a', 'double free'),
    ('M0104', None, 'a', 'use after move'),
    ('M0105', None, 'a', 'escape from a Scoped allocator'),
    ('M0106', None, 'a', 'individual free of a Scoped object'),
    ('M0107', None, 'a', 'release of a non-owned value'),
    ('M0108', None, 'a', 'wrong allocator when freeing'),
    ('M0109', r'closure', 'b', 'closure over a loan is stored or returned'),
    ('M0109', None, 'a', 'loan stored or returned as owned (needs @Owned or @Borrowed, spec 4.2)'),
    ('M0110', None, 'b', 'Strict value handed to Managed code that keeps it'),
    ('M0111', None, 'b', 'invalid ownership annotation'),
    ('M0112', None, 'b', 'owning value in escaping lambda'),
    ('M0113', None, 'a', 'owned field stays moved'),
    ('M0114', None, 'a', 'ambiguous allocator'),
    ('M0115', None, 'a', 'arena object owns a foreign reference'),
    ('M0205', None, 'a', 'allocation in @NoAlloc'),
    ('M0206', None, 'b', 'object from a non-ThreadSafe allocator leaves the thread'),
]


def load_run():
    spec = importlib.util.spec_from_file_location('conf_run', os.path.join(HERE, 'run.py'))
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


def norm(s):
    s = re.sub(r'/[^\s:]+\.kt(:\d+(:\d+)?)?', '<file>', s)
    s = re.sub(r'`[^`]*`', '`_`', s)
    s = re.sub(r'\b\d+\b', 'N', s)
    return s.strip()[:140]


def run_minic(a, srcs, cwd):
    cmd = [a.minic, '--check', '--error-format=json', '--color=never', '--error-limit=0'] + srcs + ['--lib', a.mlib, '--compiler-id', a.id]
    try:
        p = subprocess.run(cmd, capture_output=True, timeout=a.timeout, cwd=cwd)
    except subprocess.TimeoutExpired:
        return 'timeout', [], ''
    err = (p.stderr + p.stdout).decode('utf-8', 'replace')
    diags = []
    for line in err.splitlines():
        if line.startswith('{'):
            try:
                diags.append(json.loads(line))
            except ValueError:
                pass
    return p.returncode, diags, err


def classify(rc, diags, err):
    """-> (verdict, class, cause)"""
    if rc == 'timeout':
        return 'ice', 'c', 'timeout'
    if rc is not None and rc < 0 or (isinstance(rc, int) and rc >= 2):
        return 'ice', 'c', 'compiler crash (exit %s)' % rc
    if any('could not analyse Strict code' in (d.get('message') or '') for d in diags):
        msg = next(d['message'] for d in diags if 'could not analyse Strict code' in (d.get('message') or ''))
        return 'ice', 'c', 'checker could not analyse: ' + norm(msg.split('code:', 1)[-1])
    codes = [d for d in diags if d.get('severity') == 'error' and (d.get('code') or '').startswith('M0')]
    other = [d for d in diags if d.get('severity') == 'error' and not (d.get('code') or '').startswith('M0')]
    if rc == 0 and not codes:
        return 'accepted', '', ''
    if not codes and other:
        return 'other', 'x', 'frontend error in Strict only: ' + norm(other[0].get('message') or '')
    if not codes:
        return 'ice', 'c', 'failure without diagnostic'
    # a program with a correct (a) rejection must change anyway, so the (a) diagnostic decides; (b) only when every diagnostic is a limitation
    best = None
    for d in codes:
        for code, rx, cls, cause in RULES:
            if d['code'] == code and (rx is None or re.search(rx, d.get('message') or '') or re.search(rx, d.get('label') or '')):
                cand = (cls, cause, d)
                if best is None or (cls == 'a' and best[0] == 'b'):
                    best = cand
                break
    if best is None:
        d = codes[0]
        return 'rejected', 'b', d['code'] + ' ' + norm(d.get('message') or '')
    cls, cause, d = best
    detail = cause
    if d['code'] in ('M0110', 'M0109', 'M0112'):
        detail = '%s: %s' % (d['code'], norm((d.get('label') or d.get('message') or '')))
    return 'rejected', cls, detail


def programs_testdata():
    d = os.path.join(ROOT, 'compiler', 'testdata')
    return [('testdata/' + f, [os.path.join(d, f)]) for f in sorted(os.listdir(d)) if f.endswith('.kt')]


def programs_samples():
    out = []
    for base in ('examples', 'website/samples'):
        for dp, dn, fn in os.walk(os.path.join(ROOT, base)):
            dn.sort()
            for f in sorted(fn):
                if f.endswith('.kt'):
                    p = os.path.join(dp, f)
                    out.append((os.path.relpath(p, ROOT), [p]))
    # the memory samples are Strict by design (they are the checker's own showcase): they are not "ordinary" code
    return [x for x in out if '/samples/memory/' not in x[0] and 'memory_leak' not in x[0]]


def programs_conformance(a, run):
    res = json.load(open(os.path.join(HERE, 'results', 'results.json')))['results']
    passing = sorted(r['test'] for r in res if r['status'] == 'pass')
    # deterministic, diverse: order by hash of the name, take the first N
    passing.sort(key=lambda t: hashlib.sha1(t.encode()).hexdigest())
    root = os.path.join(a.box, 'compiler', 'testData', 'codegen') if not os.path.isdir(os.path.join(a.box, 'box')) else a.box
    out = []
    for t in passing[:a.sample]:
        out.append(('conformance/' + t, [os.path.join(root, t)]))
    return out


def prepare_conformance(run, path, work):
    """The files of a box test as the harness builds them (the generated main is the only non-Strict file)."""
    src = open(path, encoding='utf-8', errors='replace').read()
    files = run.split_files(src)
    pkg = run.box_package(files)
    declares_main = re.search(r'^\s*fun main\(\)', src, re.M) is not None
    srcs = []
    for i, (name, text) in enumerate(files):
        if not name.endswith('.kt'):
            continue
        text = run.DIAG_MARKUP.sub('', text).replace('OPTIONAL_JVM_INLINE_ANNOTATION', '@kotlin.jvm.JvmInline')
        if declares_main:
            text = re.sub(r'\bmain\(', 'main_(', text)
        fp = os.path.join(work, '%d_%s' % (i, os.path.basename(name)))
        open(fp, 'w').write(text)
        srcs.append(fp)
    extra = []
    if re.search(r'^import helpers\.|\bhelpers\.', src) or 'WITH_COROUTINES' in src:
        extra.append(run.HELPERS)
    if re.search(r'\bkotlin\.test\b', src):
        for j, txt in enumerate(run.kotlin_test_sources()):
            fp = os.path.join(work, 'kt_test%d.kt' % j)
            open(fp, 'w').write(txt)
            extra.append(fp)
    call = (pkg + '.box()') if pkg else 'box()'
    main = os.path.join(work, '00_main.kt')
    open(main, 'w').write('fun main() {\n    kotlin.io.println(%s)\n}\n' % call)
    return srcs, [main] + extra


def one(a, run, tc):
    name, paths = tc
    work = tempfile.mkdtemp(prefix='sc-', dir=a.tmp)
    try:
        if name.startswith('conformance/'):
            user, extra = prepare_conformance(run, paths[0], work)
        else:
            user, extra = list(paths), []
        base_rc, base_d, _ = run_minic(a, user + extra, work)
        if base_rc != 0:
            return {'name': name, 'verdict': 'excluded', 'cls': '', 'cause': 'does not compile standalone in Managed'}
        strict = []
        for i, p in enumerate(user):
            text = open(p, encoding='utf-8', errors='replace').read()
            if '@file:MemoryMode' in text:
                strict.append(p)
                continue
            q = os.path.join(work, 's%d_%s' % (i, os.path.basename(p)))
            open(q, 'w').write(STRICT + text)
            strict.append(q)
        rc, diags, err = run_minic(a, strict + extra, work)
        v, c, cause = classify(rc, diags, err)
        r = {'name': name, 'verdict': v, 'cls': c, 'cause': cause}
        if v != 'accepted':
            r['codes'] = sorted({d.get('code') or '-' for d in diags if d.get('severity') == 'error'})
            first = next((d for d in diags if d.get('severity') == 'error'), None)
            if first:
                r['first'] = '%s:%s %s | %s' % (os.path.basename(first.get('file') or ''), first.get('line'), first.get('message'), first.get('label'))
        return r
    finally:
        shutil.rmtree(work, ignore_errors=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--minic', required=True)
    ap.add_argument('--mlib', required=True)
    ap.add_argument('--id', required=True)
    ap.add_argument('--box', default=os.path.join(os.path.expanduser('~'), '.cache', 'kotlin-box', 'src', 'compiler', 'testData', 'codegen'))
    ap.add_argument('--set', default='all')
    ap.add_argument('--sample', type=int, default=300)
    ap.add_argument('--jobs', type=int, default=os.cpu_count() or 2)
    ap.add_argument('--timeout', type=int, default=120)
    ap.add_argument('--tmp', default=os.environ.get('TMPDIR', '/tmp'))
    ap.add_argument('--list', action='store_true')
    ap.add_argument('--only', action='append', help='only programs whose name contains this (repeatable)')
    ap.add_argument('--json')
    a = ap.parse_args()
    run = load_run()
    progs = []
    if a.set in ('testdata', 'all'):
        progs += programs_testdata()
    if a.set in ('samples', 'all'):
        progs += programs_samples()
    if a.set in ('conformance', 'all'):
        progs += programs_conformance(a, run)
    if a.only:
        progs = [p for p in progs if any(o in p[0] for o in a.only)]
    with ThreadPoolExecutor(a.jobs) as ex:
        results = list(ex.map(lambda tc: one(a, run, tc), progs))
    if a.json:
        json.dump(results, open(a.json, 'w'), indent=1)
    sets = collections.OrderedDict()
    for r in results:
        s = r['name'].split('/')[0] if not r['name'].startswith('website') else 'samples'
        s = 'samples' if s in ('examples', 'website') else s
        sets.setdefault(s, []).append(r)
    sets['all'] = results
    print('| set | programs | accepted | (a) correct rejection | (b) limitation | (c) ICE | other | not counted |')
    print('|---|---:|---:|---:|---:|---:|---:|---:|')
    for s, rs in sets.items():
        cnt = lambda f: sum(1 for r in rs if f(r))
        n = cnt(lambda r: r['verdict'] != 'excluded')
        acc = cnt(lambda r: r['verdict'] == 'accepted')
        print('| %s | %d | %d (%.1f%%) | %d | %d | %d | %d | %d |' % (
            s, n, acc, 100.0 * acc / max(n, 1), cnt(lambda r: r['cls'] == 'a'), cnt(lambda r: r['cls'] == 'b'),
            cnt(lambda r: r['cls'] == 'c'), cnt(lambda r: r['cls'] == 'x'), cnt(lambda r: r['verdict'] == 'excluded')))
    print()
    print('| class | cause | programs |')
    print('|---|---|---:|')
    causes = collections.Counter((r['cls'], r['cause']) for r in results if r['verdict'] not in ('accepted', 'excluded'))
    for (c, cause), n in sorted(causes.items(), key=lambda kv: (kv[0][0], -kv[1])):
        print('| (%s) | %s | %d |' % (c, cause.replace('|', '/'), n))
    if a.list:
        print()
        for r in results:
            print('%s\t%s\t%s\t%s\t%s' % (r['name'], r['verdict'], r['cls'], r['cause'], r.get('first', '')))


if __name__ == '__main__':
    main()
