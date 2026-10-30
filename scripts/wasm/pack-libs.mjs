#!/usr/bin/env node
// node pack-libs.mjs <libs-dir> <out.json>
// The libraries the browser toolchain has preinstalled (there is no package registry in the browser yet). Every directory
// <libs-dir>/<name>/ with a mini.toml and src/**/*.kt becomes
//   {"libs": [{"name": "mini-plot", "packages": ["mini.plot"], "files": [{"path": "Plot.kt", "text": "..."}]}]}
// `packages` are the Kotlin packages the library declares: the worker compiles the library together
// with a program that mentions one of them (`import mini.plot.*`), like a path dependency of `mini build`.
import { existsSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

const [dir, out] = process.argv.slice(2)
if (!dir || !out) {
    console.error('usage: pack-libs.mjs <libs-dir> <out.json>')
    process.exit(2)
}

const walk = (d, acc = []) => {
    for (const e of readdirSync(d).sort()) {
        const p = join(d, e)
        if (statSync(p).isDirectory()) walk(p, acc)
        else if (e.endsWith('.kt')) acc.push(p)
    }
    return acc
}

const libs = []
for (const name of readdirSync(dir).sort()) {
    const root = join(dir, name)
    if (!statSync(root).isDirectory() || !existsSync(join(root, 'mini.toml')) || !existsSync(join(root, 'src'))) continue
    const manifest = readFileSync(join(root, 'mini.toml'), 'utf8')
    if (/^\s*kind\s*=\s*"app"/m.test(manifest)) continue
    const src = join(root, 'src')
    const files = walk(src).sort((a, b) => (a < b ? -1 : a > b ? 1 : 0)).map(p => ({ path: p.slice(src.length + 1), text: readFileSync(p, 'utf8') }))
    const packages = [...new Set(files.map(f => /^package\s+([\w.]+)/m.exec(f.text)?.[1]).filter(Boolean))].sort()
    libs.push({ name: manifest.match(/^\s*name\s*=\s*"([^"]+)"/m)?.[1] ?? name, packages, files })
}
writeFileSync(out, JSON.stringify({ libs }))
console.log(`pack-libs: ${libs.map(l => `${l.name} (${l.files.length} files)`).join(', ') || 'none'}`)
