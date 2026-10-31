#!/usr/bin/env node
// Compile the browser's preinstalled libraries once and package their target-independent MIR as .mlib files.
// Usage: node pack-mlibs.mjs <repo> <out.json> <minic-native> <stdlib.mlib>
import { execFileSync } from 'node:child_process'
import { existsSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { join, relative, resolve } from 'node:path'

const [repoArg, outArg, compilerArg, stdlibArg] = process.argv.slice(2)
if (!repoArg || !outArg || !compilerArg || !stdlibArg) {
    console.error('usage: pack-mlibs.mjs <repo> <out.json> <minic-native> <stdlib.mlib>')
    process.exit(2)
}

const repo = resolve(repoArg)
const out = resolve(outArg)
const compiler = resolve(compilerArg)
const stdlib = resolve(stdlibArg)
const version = readFileSync(join(repo, 'VERSION'), 'utf8').trim()
const walk = d => readdirSync(d).sort().flatMap(e => {
    const p = join(d, e)
    return statSync(p).isDirectory() ? walk(p) : e.endsWith('.kt') ? [p] : []
})
const packages = new Map()
const roots = join(repo, 'libs')
for (const name of readdirSync(roots).sort()) {
    const root = join(roots, name)
    if (!statSync(root).isDirectory() || !existsSync(join(root, 'mini.toml')) || !existsSync(join(root, 'src'))) continue
    const manifest = readFileSync(join(root, 'mini.toml'), 'utf8')
    if (/^\s*kind\s*=\s*"app"/m.test(manifest)) continue
    const src = join(root, 'src')
    const alt = join(root, 'alt')
    const alts = existsSync(alt) ? walk(alt).map(p => relative(alt, p)) : []
    const rels = [...new Set([...walk(src).map(p => relative(src, p)), ...alts])].sort()
    const files = rels.map(p => join(alts.includes(p) ? alt : src, p))
    const pkgNames = [...new Set(files.map(p => /^package\s+([\w.]+)/m.exec(readFileSync(p, 'utf8'))?.[1]).filter(Boolean))].sort()
    const packageName = manifest.match(/^\s*name\s*=\s*"([^"]+)"/m)?.[1] ?? name
    const libVersion = manifest.match(/^\s*version\s*=\s*"([^"]+)"/m)?.[1] ?? '0.0.0'
    const imports = files.flatMap(p => [...readFileSync(p, 'utf8').matchAll(/^\s*import\s+([\w.]+)(?:\.\*)?\s*$/gm)].map(m => m[1]))
    packages.set(packageName, { root, files, pkgNames, libVersion, imports, dependencies: [] })
}

const ownerOfPackage = new Map()
for (const [name, lib] of packages) for (const p of lib.pkgNames) ownerOfPackage.set(p, name)
for (const [name, lib] of packages) {
    lib.dependencies = [...new Set(lib.imports.flatMap(imp => {
        const pkg = [...ownerOfPackage.keys()].filter(p => imp === p || imp.startsWith(`${p}.`)).sort((a, b) => b.length - a.length)[0]
        const owner = pkg && ownerOfPackage.get(pkg)
        return owner && owner !== name ? [owner] : []
    }))].sort()
}

// Compile dependencies first so each mlib records exact dependency hashes in its header.
const built = new Map()
const order = []
const visiting = new Set()
const visited = new Set()
function visit(name) {
    if (visited.has(name)) return
    if (visiting.has(name)) throw new Error(`preinstalled library dependency cycle at ${name}`)
    const lib = packages.get(name)
    if (!lib) throw new Error(`missing preinstalled library dependency ${name}`)
    visiting.add(name)
    for (const dep of lib.dependencies) visit(dep)
    visiting.delete(name)
    visited.add(name)
    order.push(name)
}
for (const name of packages.keys()) visit(name)

for (const name of order) {
    const lib = packages.get(name)
    const mlibPath = join(resolve(out, '..'), `${name}.mlib`)
    const args = ['--emit-mlib', mlibPath, '--name', name, '--lib-version', lib.libVersion,
        '--compiler-id', version, '--lib', stdlib, ...lib.files]
    for (const dep of lib.dependencies) args.push('--lib', join(resolve(out, '..'), `${dep}.mlib`))
    execFileSync(compiler, args, { cwd: repo, stdio: 'inherit' })
    const assetPath = `${name}.mlib`
    writeFileSync(join(resolve(out, '..'), assetPath), readFileSync(mlibPath))
    built.set(name, { name, packages: lib.pkgNames, dependencies: lib.dependencies, path: assetPath })
}
writeFileSync(out, JSON.stringify({ libs: [...built.values()] }))
console.log(`pack-mlibs: ${[...built.keys()].join(', ') || 'none'}`)
