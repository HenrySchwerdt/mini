#!/usr/bin/env python3
"""Stdlib API coverage: which public declarations of the Kotlin common/native stdlib does mini's stdlib/ lack?

  python3 -I tools/stdlib-coverage/coverage.py [--ref DIR] [--mini DIR] [--freq DIR] [--md FILE] [--list PKG] [--top N]

--ref   unpacked kotlin-stdlib-sources.jar (commonMain/, jvmMain/). Default: $KOTLIN_STDLIB_SOURCES or
        ~/.cache/kotlin-stdlib-src (fetch.sh puts it there).
--mini  mini's stdlib (default: <repo>/stdlib).
--freq  directory of Kotlin sources; missing declarations are ranked by how often their name occurs there
        (default: ~/.cache/kotlin-box/src/compiler/testData, the conformance harness checkout).

A declaration is keyed by  package / owner / name / arity  (top-level functions and properties by their extension
receiver, members by their class).  Reference side = commonMain + generated + the builtins of jvmMain (the part Kotlin/Native
shares with common).  Skipped: private/internal/@PublishedApi, hidden/error-deprecated, @SinceKotlin newer than 2.1.
A reference declaration is present when mini declares the same package/owner/name with a compatible arity (the reference
arity lies within [required, total] of one of mini's overloads); members are also looked up in mini's supertypes of the
class.  The parser is lexical (no type resolution); receivers that are concrete instantiations (Iterable<Int>.sum) keep
their type argument, so per-primitive overloads count separately.
"""
import argparse, os, re, sys, collections, json

def strip_source(t):
    """Blank comments and string/char literal contents (newlines kept) so braces and parens are reliable."""
    out = []
    i, n = 0, len(t)
    while i < n:
        c = t[i]
        if t.startswith('//', i):
            j = t.find('\n', i)
            i = n if j < 0 else j
        elif t.startswith('/*', i):
            depth, j = 1, i + 2
            while j < n and depth:
                if t.startswith('/*', j): depth += 1; j += 2
                elif t.startswith('*/', j): depth -= 1; j += 2
                else: j += 1
            out.append(''.join('\n' if ch == '\n' else ' ' for ch in t[i:j]))
            i = j
        elif t.startswith('"""', i):
            j = t.find('"""', i + 3)
            j = n if j < 0 else j + 3
            while j < n and t[j] == '"': j += 1
            out.append('""' + ''.join('\n' if ch == '\n' else ' ' for ch in t[i + 2:j - 2]))
            i = j
        elif c == '"':
            j = i + 1
            while j < n and t[j] != '"' and t[j] != '\n':
                if t[j] == '\\': j += 1
                elif t[j] == '$' and j + 1 < n and t[j + 1] == '{':
                    d = 1; j += 2
                    while j < n and d:
                        if t[j] == '{': d += 1
                        elif t[j] == '}': d -= 1
                        j += 1
                    continue
                j += 1
            out.append('""')
            i = j + 1
        elif c == "'" and i + 2 < n and (t[i + 1] == '\\' or t[i + 2] == "'"):
            j = i + 1
            if t[j] == '\\':
                j += 2
                while j < n and t[j] != "'": j += 1
            else:
                j += 1
            out.append("'x'")
            i = j + 1
        else:
            out.append(c); i += 1
    return ''.join(out)

def balanced(s, i, op, cl):
    d = 0
    for j in range(i, len(s)):
        ch = s[j]
        if ch == op: d += 1
        elif ch == cl and not (cl == '>' and s[j - 1] == '-'):
            d -= 1
            if d == 0: return j + 1
    return len(s)

def is_close(s, k):
    return s[k] in ')]}' or (s[k] == '>' and s[k - 1] != '-')

def split_top(s, sep=','):
    parts, d, cur = [], 0, []
    for k, ch in enumerate(s):
        if ch in '([{<': d += 1
        elif is_close(s, k): d -= 1
        if ch == sep and d == 0:
            parts.append(''.join(cur)); cur = []
        else:
            cur.append(ch)
    parts.append(''.join(cur))
    return [p.strip() for p in parts if p.strip()]

MODS = ['public', 'private', 'internal', 'protected', 'open', 'final', 'abstract', 'sealed', 'override', 'inline', 'infix',
        'operator', 'tailrec', 'external', 'suspend', 'const', 'lateinit', 'data', 'inner', 'enum', 'annotation', 'value',
        'expect', 'actual', 'companion']
KW = re.compile(r'(?m)^[ \t]*((?:@[\w.:]+(?:\([^\n]*\))?[ \t]*\n?[ \t]*)*(?:(?:' + '|'.join(MODS) + r')\s+)*)(fun|val|var|class|interface|object|typealias|constructor)\b')
ANN = re.compile(r'@(?:file:)?[\w.]+')

def split_annotations(s):
    anns = []
    s = s.lstrip()
    while s.startswith('@'):
        m = ANN.match(s)
        if not m: break
        j = m.end()
        if j < len(s) and s[j] == '(': j = balanced(s, j, '(', ')')
        anns.append(s[:j]); s = s[j:].lstrip()
    return anns, s

def simple_type(t):
    t = t.strip()
    if t.startswith('(') or '->' in t.split('<')[0]: return 'Function'
    return t.rstrip('?').strip().split('<')[0].strip().split('.')[-1]

def receiver_key(t, tparams):
    t = re.sub(r'@\w+\s*', '', t).strip()
    if t.rstrip('?') in tparams: return '*'     # receiver is a bare type parameter (`C.onEach` with C : Iterable<T>)
    if t.startswith('(') and '->' in t: return 'Function'
    t = t.rstrip('?').strip()
    if '<' not in t: return t.split('.')[-1]
    base = t[:t.index('<')].strip().split('.')[-1]
    keep = []
    for a in split_top(t[t.index('<') + 1:t.rindex('>')]):
        a = re.sub(r'^(out|in)\s+', '', a.strip().rstrip('?'))
        nm = simple_type(a)
        if a == '*' or nm in tparams or (len(nm) <= 2 and nm.isalpha() and nm.isupper()): keep.append('_')
        else: keep.append(nm)
    return base if all(k == '_' for k in keep) else base + '<' + ','.join(keep) + '>'

def parse_params(ptxt):
    ps = split_top(ptxt)
    req = 0
    for p in ps:
        d, has = 0, False
        for k, ch in enumerate(p):
            if ch in '([{<': d += 1
            elif is_close(p, k): d -= 1
            elif ch == '=' and d == 0 and p[k + 1:k + 2] != '=': has = True; break
        if not has and not re.match(r'(?:@[\w.]+(?:\([^)]*\))?\s*)*(?:(?:noinline|crossinline)\s+)*vararg\b', p): req += 1
    return req, len(ps)

class Decl:
    __slots__ = ('kind', 'pkg', 'owner', 'name', 'req', 'total', 'file', 'line', 'since', 'opt', 'sig', 'hidden')
    def __init__(s, kind, pkg, owner, name, req, total, file, line, sig=None):
        s.kind, s.pkg, s.owner, s.name, s.req, s.total, s.file, s.line, s.sig = kind, pkg, owner, name, req, total, file, line, sig
        s.since = None; s.opt = False; s.hidden = False

def head_split(head, tparams):
    cut, d = -1, 0
    for k, c in enumerate(head):
        if c in '(<[': d += 1
        elif is_close(head, k): d -= 1
        elif c == '.' and d == 0: cut = k
    if cut >= 0: return receiver_key(head[:cut], tparams), head[cut + 1:].strip().strip('`')
    return None, head.strip().strip('`')

def type_params(r):
    tps = set()
    if r.startswith('<'):
        e = balanced(r, 0, '<', '>')
        for p in split_top(r[1:e - 1]):
            m = re.match(r'(?:@[\w.]+\s*)*(?:reified\s+|in\s+|out\s+)*(\w+)', p.strip())
            if m: tps.add(m.group(1))
        r = r[e:].lstrip()
    return tps, r

def scan_file(path, text):
    src = strip_source(text)
    pkg = ''
    m = re.search(r'^\s*package\s+([\w.]+)', src, re.M)
    if m: pkg = m.group(1)
    n = len(src)
    braces = [(j, c) for j, c in enumerate(src) if c in '{}']
    bi = 0
    scope = [('file', '', True)]
    opens = {}
    out, classes = [], {}
    skip_to = 0
    for mt in KW.finditer(src):
        if mt.start() < skip_to: continue
        while bi < len(braces) and braces[bi][0] < mt.start():
            p, b = braces[bi]
            if b == '{': scope.append(opens.pop(p, ('body', '', False)))
            elif len(scope) > 1: scope.pop()
            bi += 1
        sc = scope[-1]
        if sc[0] == 'body': continue
        kwd = mt.group(2)
        anns_l, mods_txt = split_annotations(mt.group(1))
        mods = set(re.findall(r'\w+', mods_txt))
        anns = ' '.join(anns_l)
        start = mt.end()
        owner = sc[1]
        line = src.count('\n', 0, mt.start(2)) + 1
        vis = sc[2] and not ({'private', 'internal', 'protected'} & mods) and 'PublishedApi' not in anns \
            and not re.search(r'DeprecationLevel\.(HIDDEN|ERROR)', anns)
        def add(kind, own, name, req=0, tot=0, sig=None):
            out.append(Decl(kind, pkg, own, name, req, tot, path, line, sig))
        if kwd in ('class', 'interface', 'object'):
            if kwd == 'object' and 'companion' in mods and not re.match(r'\s*\w', src[start:start + 40]): name = 'Companion'
            else:
                m2 = re.match(r'\s*(\w+)', src[start:start + 200]); name = m2.group(1) if m2 else ''
            if 'companion' in mods and name in ('object', ''): name = 'Companion'
            if not name or name == 'object': continue      # anonymous object expression at the start of a line
            if kwd == 'class' and mods_txt.strip().endswith('companion') and False: pass
            # header up to '{' at depth 0 (or a statement end)
            d, k = 0, start
            while k < n:
                c = src[k]
                if c in '(<[': d += 1
                elif is_close(src, k): d -= 1
                elif c == '{' and d <= 0: break
                elif c == '\n' and d <= 0:
                    nxt = src[k + 1:k + 200].lstrip()
                    if nxt.startswith((':', '{', 'where', ')')): k += 1; continue
                    break
                k += 1
            header = src[start:k]
            skip_to = k
            brace = k < n and src[k] == '{'
            full = (owner + '.' if owner else '') + name
            is_ann = 'annotation' in mods
            hh = re.sub(r'^\s*\w+', '', header, count=1).lstrip()
            if hh.startswith('<'): hh = hh[balanced(hh, 0, '<', '>'):].lstrip()
            pm = re.match(r'((?:@[\w.]+(?:\([^)]*\))?\s*)*(?:(?:private|internal|protected|public)\s+)?)\s*(?:constructor\s*)?\(', hh)
            params = None; cpriv = False; rest_h = hh
            if pm and kwd == 'class':
                ps = hh[pm.end() - 1:]; ps = ps[:balanced(ps, 0, '(', ')')]
                params = ps[1:-1]; rest_h = hh[pm.end() - 1 + len(ps):]
                cpriv = bool(re.search(r'private|internal|protected', pm.group(1)))
            sup = []
            if ':' in rest_h:
                for p in split_top(rest_h.split(':', 1)[1].split(' where ')[0]):
                    p = re.sub(r'\bby\b.*', '', p, flags=re.S).strip()
                    p = re.sub(r'\(.*\)$', '', p, flags=re.S).strip()
                    if p: sup.append(simple_type(p))
            classes[(pkg, full)] = sup
            pub = vis and not ('expect' in mods and False)
            if brace: opens[k] = ('class', full, pub and not is_ann)
            if pub:
                add('class', owner, name, sig=kwd)
                if params is not None and not is_ann:
                    req, tot = parse_params(params)
                    if not cpriv: add('ctor', full, '<init>', req, tot)
                    for p in split_top(params):
                        q = re.match(r'(?:@[\w.]+(?:\([^)]*\))?\s*)*((?:(?:public|override|open|private|internal|protected)\s+)*)(val|var)\s+(\w+)', p)
                        if q and not re.search(r'private|internal|protected', q.group(1)): add('val', full, q.group(3))
                if 'enum' in mods and brace:
                    body = src[k + 1:balanced(src, k, '{', '}') - 1]
                    ent = body.split(';')[0] if ';' in body else ('' if re.search(r'\b(fun|val|var)\b', body) else body)
                    ent = re.sub(r'\{[^{}]*\}', '', ent)
                    for e in split_top(ent):
                        em = re.match(r'(?:@[\w.]+(?:\([^)]*\))?\s*)*(\w+)', e)
                        if em: add('val', full, em.group(1), sig='entry')
            continue
        if kwd == 'typealias':
            m2 = re.match(r'\s*(\w+)', src[start:start + 200])
            if vis and m2: add('class', owner, m2.group(1), sig='typealias')
            continue
        if kwd == 'constructor':
            m2 = re.match(r'\s*\(', src[start:])
            if m2 and vis and owner:
                ps = src[start + m2.end() - 1:]; ps = ps[:balanced(ps, 0, '(', ')')][1:-1]
                req, tot = parse_params(ps); add('ctor', owner, '<init>', req, tot)
            continue
        if kwd == 'fun':
            if re.match(r'\s*interface\b', src[start:start + 20]): continue
            tps, r = type_params(src[start:start + 3000].lstrip())
            po = r.find('(')
            if po < 0: continue
            recv, name = head_split(r[:po].strip(), tps)
            if not re.match(r'^\w+$', name): continue
            pe = balanced(r, po, '(', ')')
            req, tot = parse_params(r[po + 1:pe - 1])
            if vis: add('fun', 'ext:' + recv if recv else owner, name, req, tot)
            continue
        # val / var
        tps, r = type_params(src[start:start + 600].lstrip())
        head = re.match(r'[^:=\n]*', r).group(0).strip()
        if head.startswith('('): continue
        recv, name = head_split(head, tps)
        if not re.match(r'^\w+$', name): continue
        if vis: add('val', 'ext:' + recv if recv else owner, name)
    return out, classes

def annotate(text, decls):
    lines = text.split('\n')
    for d in decls:
        ln = d.line
        buf = []
        for k in range(ln - 1, max(-1, ln - 30), -1):
            L = lines[k]
            if k < ln - 1:
                s = L.strip()
                if s.endswith(('}', ';')) or s.startswith(('package', 'import')): break
            buf.append(L)
        j = '\n'.join(reversed(buf))
        m = re.search(r'@SinceKotlin\("(\d+)\.(\d+)', j)
        d.since = (int(m.group(1)), int(m.group(2))) if m else None
        d.opt = bool(re.search(r'@(?:kotlin\.)?Experimental\w+|@(?:kotlin\.)?(?:OptIn)', j))
        d.hidden = bool(re.search(r'DeprecationLevel\.(HIDDEN|ERROR)|level\s*=\s*DeprecationLevel', j) and re.search(r'DeprecationLevel\.(HIDDEN|ERROR)', j))

def scan_tree(root, rels, skip=(), top_only=(), only_files=None):
    decls, classes = [], {}
    for rel in rels:
        base = os.path.join(root, rel)
        for dp, dn, fn in os.walk(base):
            if rel in top_only and dp != base: continue
            for f in sorted(fn):
                if not f.endswith('.kt') or (only_files and f not in only_files): continue
                p = os.path.join(dp, f)
                relp = os.path.relpath(p, root)
                if any(s in relp for s in skip): continue
                if rel in top_only and f in ('Library.kt', 'Coroutines.kt'): continue
                text = open(p, encoding='utf-8').read()
                ds, cs = scan_file(relp, text)
                annotate(text, ds)
                decls += ds; classes.update(cs)
    return decls, classes

def arity_ok(ref, cands):
    return any(m.req <= ref.total <= m.total or (m.req <= ref.req and ref.total <= m.total) for m in cands)

DEFAULT_PKGS = ('kotlin', 'kotlin.collections', 'kotlin.text', 'kotlin.ranges', 'kotlin.sequences', 'kotlin.io',
                'kotlin.comparisons', 'kotlin.annotation', 'kotlin.jvm')

def pkg_family(pkg):
    """A declaration in a default-imported package also counts when mini keeps it in another default-imported package."""
    return (pkg,) + tuple(p for p in DEFAULT_PKGS if p != pkg) if pkg in DEFAULT_PKGS else (pkg,)

def owner_candidates(d, supers):
    """Exact owner, then the receiver without type arguments, then its supertypes (List.first is served by Iterable.first)."""
    o = d.owner
    yield o
    if o.startswith('ext:'):
        base = o[4:].split('<')[0]
        if base != o[4:]: yield 'ext:' + base
        for s in supers(base, set()): yield 'ext:' + s
        yield base                       # a member of the class (StringBuilder.set is a member in mini)
    elif '.' in o:
        yield 'ext:' + o      # Duration.Companion.parse may be an extension on Duration.Companion
        yield 'ext:' + o.split('.')[-1]

def fmt(d):
    if d.kind == 'class': return '%s %s%s' % (d.sig or 'class', d.owner + '.' if d.owner else '', d.name)
    ar = d.total if d.req == d.total else '%d..%d' % (d.req, d.total)
    if d.kind == 'ctor': return 'constructor %s(%s)' % (d.owner, ar)
    o = d.owner[4:] + '.' if d.owner.startswith('ext:') else (d.owner + '.' if d.owner else '')
    return '%s %s%s%s' % ('fun' if d.kind == 'fun' else 'val', o, d.name, '' if d.kind == 'val' else '(%s)' % ar)

def main():
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.abspath(os.path.join(here, '..', '..'))
    ap = argparse.ArgumentParser()
    ap.add_argument('--ref', default=os.environ.get('KOTLIN_STDLIB_SOURCES') or os.path.expanduser('~/.cache/kotlin-stdlib-src'))
    ap.add_argument('--mini', default=os.path.join(root, 'stdlib'))
    ap.add_argument('--freq', default=os.path.expanduser('~/.cache/kotlin-box/src/compiler/testData'))
    ap.add_argument('--md', help='write the summary table (markdown) here')
    ap.add_argument('--json', help='write the missing list as JSON here')
    ap.add_argument('--list', action='append', help='print the missing declarations of this package ("all" for every package)')
    ap.add_argument('--top', type=int, default=0, help='print the N most used missing declarations')
    ap.add_argument('--no-experimental', action='store_true', help='drop opt-in (experimental) reference declarations')
    a = ap.parse_args()
    if not os.path.isdir(os.path.join(a.ref, 'commonMain')):
        sys.exit('reference sources not found at %s (run tools/stdlib-coverage/fetch.sh)' % a.ref)
    skip = ['/annotations/', 'coroutines/jvm', 'kotlin/js/', 'kotlin/internal/']
    ref, ref_cls = scan_tree(a.ref, ['commonMain/kotlin', 'commonMain/generated', 'jvmMain/kotlin'], skip,
                             top_only=('jvmMain/kotlin',))
    ref += scan_tree(a.ref, ['jvmMain/kotlin/io'], skip, only_files=('Console.kt',))[0]   # print/println/readln (native has them too)
    mini, mini_cls = scan_tree(a.mini, ['kotlin', 'kotlinx', 'mini', 'java'])
    ref = [d for d in ref if d.pkg != 'kotlin.js' and not (d.since and d.since > (2, 1)) and not (a.no_experimental and d.opt) and not d.hidden]
    seen, u = set(), []
    for d in ref:
        k = (d.kind, d.pkg, d.owner, d.name, d.req, d.total)
        if k not in seen: seen.add(k); u.append(d)
    ref = u

    midx = collections.defaultdict(list)
    for d in mini: midx[(d.kind, d.pkg, d.owner, d.name)].append(d)
    by_name = collections.defaultdict(list)
    for d in mini: by_name[(d.kind, d.name)].append(d)
    by_simple = collections.defaultdict(list)
    for (p, full), sup in mini_cls.items(): by_simple[full.split('.')[-1]].append(sup)
    class_pkgs = collections.defaultdict(set)
    for d in mini:
        if d.kind == 'class': class_pkgs[d.name].add(d.pkg)
    mini_class_set = {(d.pkg, (d.owner + '.' if d.owner else '') + d.name) for d in mini if d.kind == 'class'}

    def supers(simple, seen):
        res = []
        for sup in by_simple.get(simple, []):
            for s in sup:
                if s not in seen: seen.add(s); res.append(s); res += supers(s, seen)
        return res

    missing, present = [], []
    for d in ref:
        if d.kind == 'ctor' and d.owner == 'Any' or (d.kind == 'fun' and d.name in ('toString', 'equals', 'hashCode')
                                                     and not d.owner.startswith('ext:') and d.owner):
            present.append(d); continue
        if d.kind == 'class':
            full = (d.owner + '.' if d.owner else '') + d.name
            ok = (d.pkg, full) in mini_class_set or (d.sig == 'typealias' and d.name in class_pkgs)
        else:
            ok = False
            if d.owner == 'ext:*':
                ok = any(m.pkg in pkg_family(d.pkg) and m.owner.startswith('ext:') and arity_ok(d, [m]) for m in by_name.get((d.kind, d.name), []))
            for own in ([] if ok else owner_candidates(d, supers)):
                for pp in pkg_family(d.pkg):
                    c = midx.get((d.kind, pp, own, d.name), [])
                    if c and (d.kind == 'val' or arity_ok(d, c)): ok = True; break
                if ok: break
            if not ok and not d.owner.startswith('ext:'):
                for s in supers(d.owner.split('.')[-1], set()):
                    for pp in class_pkgs.get(s, ()):
                        cc = midx.get((d.kind, pp, s, d.name), [])
                        if cc and (d.kind == 'val' or arity_ok(d, cc)): ok = True
                    if ok: break
        (present if ok else missing).append(d)

    words = collections.Counter()
    if a.freq and os.path.isdir(a.freq):
        for dp, dn, fn in os.walk(a.freq):
            for f in fn:
                if f.endswith('.kt'):
                    words.update(re.findall(r'\b[A-Za-z_]\w*\b', open(os.path.join(dp, f), encoding='utf-8', errors='ignore').read()))
    def score(d):
        return words.get(d.owner.split('.')[-1] if d.name == '<init>' else d.name, 0)

    tot, have = collections.Counter(), collections.Counter()
    for d in ref: tot[d.pkg] += 1
    for d in present: have[d.pkg] += 1
    lines = ['| package | reference | present | missing | coverage |', '|---|---:|---:|---:|---:|']
    T = H = 0
    for p in sorted(tot, key=lambda p: -tot[p]):
        lines.append('| `%s` | %d | %d | %d | %.1f%% |' % (p, tot[p], have[p], tot[p] - have[p], 100.0 * have[p] / tot[p]))
        T += tot[p]; H += have[p]
    lines.append('| **total** | **%d** | **%d** | **%d** | **%.1f%%** |' % (T, H, T - H, 100.0 * H / max(1, T)))
    print('\n'.join(lines))
    if a.md: open(a.md, 'w').write('\n'.join(lines) + '\n')
    for d in sorted(missing, key=lambda d: (d.pkg, d.file, d.line)):
        if a.list and ('all' in a.list or d.pkg in a.list):
            print('%s  %s  [%s] uses=%d' % (d.pkg, fmt(d), os.path.basename(d.file), score(d)))
    if a.top:
        print('\nmost used missing declarations (identifier count in %s):' % a.freq)
        for d in sorted(missing, key=lambda d: -score(d))[:a.top]:
            print('%6d  %s  %s' % (score(d), d.pkg, fmt(d)))
    if a.json:
        json.dump([{'pkg': d.pkg, 'decl': fmt(d), 'file': d.file, 'uses': score(d), 'experimental': d.opt} for d in missing],
                  open(a.json, 'w'), indent=0)

if __name__ == '__main__':
    main()
