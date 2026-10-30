#!/usr/bin/env python3
"""Regenerates compiler/mini-tool/src/ExplainData.kt (the `mini explain` texts) from docs/memory-model.md section 7.

The generated file is checked in (the native build needs no generator); run this whenever the document changes:

    python3 scripts/gen-explain.py          # rewrites the file when it differs
    python3 scripts/gen-explain.py --check  # exit 1 when the checked-in file is stale
"""
import os
import re
import sys

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
doc = os.path.join(root, 'docs/memory-model.md')
out_file = os.path.join(root, 'compiler/mini-tool/src/ExplainData.kt')

lines = open(doc, encoding='utf-8').read().split('\n')
start = next((i for i, l in enumerate(lines) if l.startswith('## 7. Diagnostic Catalog')), -1)
if start < 0:
    sys.exit('section 7 not found in ' + doc)
end = next((i for i in range(start + 1, len(lines)) if lines[i].startswith('## ')), len(lines))
heading = re.compile(r'^### 7\.(\d+a?) (.*)$')
code = re.compile(r'\bM\d{4}\b')
secs = []  # [num, title, body lines]
for l in lines[start + 1:end]:
    m = heading.match(l)
    if m:
        secs.append([m.group(1), m.group(2), []])
        continue
    if not secs or l.strip() == '---':
        continue
    secs[-1][2].append(l)


def lit(s):
    sb = ['"']
    for ch in s:
        if ch == '\\':
            sb.append('\\\\')
        elif ch == '"':
            sb.append('\\"')
        elif ch == '$':
            sb.append('\\$')
        elif ch == '\n':
            sb.append('\\n')
        elif ch == '\r':
            pass
        elif ch == '\t':
            sb.append('\\t')
        else:
            sb.append(ch)
    sb.append('"')
    return ''.join(sb)


sb = ['// GENERATED from docs/memory-model.md section 7 by scripts/gen-explain.py. Do not edit.\n',
      'package mini.tool\n\ninternal object ExplainData {\n',
      '    /** (codes, title, markdown body) per catalog section */\n',
      '    val sections: List<Triple<List<String>, String, String>> = listOf(\n']
for num, title, body in secs:
    codes = code.findall(title)
    if 'M0201' in title and 'M0203' in title:
        codes = ['M0201', 'M0202', 'M0203']
    if not codes:
        continue
    text = '\n'.join(body).strip('\n')
    sb.append('        Triple(listOf(%s), %s, %s),\n' % (', '.join(lit(c) for c in codes), lit(title), lit(text)))
sb.append('    )\n')
rows = []
for l in lines:
    if l.startswith('| M0'):
        cells = [c.strip() for c in l.strip('|').split('|')]
        if len(cells) >= 3:
            rows.append(cells)
sb.append('    /** (code, severity, short description) from the overview table */\n')
sb.append('    val overview: List<Triple<String, String, String>> = listOf(\n')
seen = set()
for r in sorted(rows, key=lambda r: r[0]):
    if r[0] in seen:
        continue
    seen.add(r[0])
    sb.append('        Triple(%s, %s, %s),\n' % (lit(r[0]), lit(r[1]), lit(r[2])))
sb.append('    )\n}\n')
text = ''.join(sb)
cur = open(out_file, encoding='utf-8').read() if os.path.isfile(out_file) else ''
if '--check' in sys.argv:
    sys.exit(0 if cur == text else 1)
if cur != text:
    open(out_file, 'w', encoding='utf-8').write(text)
    print('rewrote', out_file)
