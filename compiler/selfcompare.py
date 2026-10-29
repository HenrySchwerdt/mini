#!/usr/bin/env python3
"""B3 self-compile check: MIR of the compiler sources from K2 vs from the own frontend (--frontend=mini).

Local ids `(l N` and temporary numbering `tmpN_` are normalised (the two paths number them differently inside lambdas;
that is semantically irrelevant). Everything else must match, except the allowlisted flow-fact casts below. The number of
remaining differing lines is checked against selfcompare-baseline.txt: it must not grow (MINI_SELF_UPDATE=1 rewrites it).

Known, listed difference (not hidden by the normaliser): K2 keeps smart casts from flow facts the own sema does not model
(`val b = x != null && ...; if (b)`, `x as? T ?: error()`): K2 has `(cast (l N) T Unchecked)` where we have `(l N)`.
"""
import difflib, glob, os, re, subprocess, sys, tempfile

here = os.path.dirname(os.path.abspath(__file__))
root = os.path.dirname(here)
minic = os.path.join(here, 'jvm-bridge/build/install/minic/bin/minic')
srcs = []
for m in ['minic-native', 'lower', 'frontend', 'backend']:
    srcs += sorted(glob.glob(os.path.join(here, m, 'src/main/kotlin/**/*.kt'), recursive=True))
work = tempfile.mkdtemp(prefix='selfcompare-')
env = dict(os.environ, JAVA_OPTS='-Xss512m -Xmx6g')
for name, extra in [('k2', ['--frontend=k2']), ('mini', ['--frontend=mini'])]:
    p = subprocess.run([minic, 'emit-mir'] + srcs + ['-o', f'{work}/{name}.mir'] + extra, capture_output=True, text=True, env=env)
    if p.returncode != 0:
        sys.exit(f'{name}: minic emit-mir failed\n{p.stderr[-1500:]}')


def norm(path):
    out = []
    for l in open(path).read().split('\n'):
        l = re.sub(r'\(l \d+', '(l N', l)
        l = re.sub(r'tmp\d+_', 'tmp_', l)
        out.append(l)
    return out


a, b = norm(f'{work}/k2.mir'), norm(f'{work}/mini.mir')
sm = difflib.SequenceMatcher(None, a, b, autojunk=False)
total = 0
casts = 0
for tag, i1, i2, j1, j2 in sm.get_opcodes():
    if tag == 'equal':
        continue
    n = max(i2 - i1, j2 - j1)
    k2 = ' '.join(a[i1:i2])
    mini = ' '.join(b[j1:j2])
    # allowlisted: K2 has extra `(cast (l N) T Unchecked)` wrappers (flow-fact smart casts)
    if re.sub(r'\(cast \(l N\) [^()]*(\([^()]*\))*[^()]* Unchecked\)', '(l N)', k2) == mini:
        casts += n
        continue
    total += n
base_file = os.path.join(here, 'selfcompare-baseline.txt')
print(f'differing lines after normalisation: {total} (allowlisted flow-fact casts: {casts}); of {len(a)} MIR lines')
if os.environ.get('MINI_SELF_UPDATE'):
    open(base_file, 'w').write(f'{total}\n')
    print('baseline updated')
    sys.exit(0)
base = int(open(base_file).read().strip())
if total > base:
    sys.exit(f'the self-compile MIR diff grew: {total} > baseline {base}')
print(f'ok (baseline {base})')
