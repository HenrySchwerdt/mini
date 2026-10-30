#!/usr/bin/env node
// node pack-stdlib.mjs <stdlib-dir> <out.json>
// {"files": [{"path": "mini/io/Files.kt", "text": "..."}]} in the order `find | sort` gives (the order bootstrap.sh
// and the compiler use; the compiler takes the stdlib files in the order they are listed).
import { readdirSync, readFileSync, writeFileSync, statSync } from 'node:fs'
import { join } from 'node:path'

const [dir, out] = process.argv.slice(2)
const files = []
const walk = d => {
    for (const e of readdirSync(d).sort()) {
        const p = join(d, e)
        if (statSync(p).isDirectory()) walk(p)
        else if (e.endsWith('.kt')) files.push(p)
    }
}
walk(dir)
// plain byte order of the full path, like `sort` in the C locale
files.sort((a, b) => (a < b ? -1 : a > b ? 1 : 0))
writeFileSync(out, JSON.stringify({ files: files.map(p => ({ path: p.slice(dir.length + 1), text: readFileSync(p, 'utf8') })) }))
