#!/usr/bin/env node
// Lays the toolchain out the way the website expects it (website/.vitepress/theme/playground/README.md, "Deployment layout"):
//
//   node scripts/wasm/pack-site.mjs <toolchain-dir> <out-dir>
//     <toolchain-dir>  build/wasm-toolchain (output of scripts/build-wasm-toolchain.sh)
//     <out-dir>        e.g. website/public/toolchain
//
//   <out-dir>/manifest.json                   ToolchainManifest (version = hash of all files)
//   <out-dir>/<version>/mini-toolchain.worker.js   the module worker (esbuild bundle of scripts/wasm/worker)
//   <out-dir>/<version>/{minic.wasm,stdlib.json,libs.json (optional: preinstalled libraries, pack-libs.mjs),libminirt-wasm32.a,minirt-entry-wasm32.o}
//   <out-dir>/<version>/{llvm.core.wasm,llvm.core2.wasm,llvm.core3.wasm,llvm.core4.wasm,llvm-resources.tar}   (@yowasp/clang, next to the worker)
//
// Everything below <version>/ is immutable for that version; only manifest.json changes.
import { build } from 'esbuild'
import { createHash } from 'node:crypto'
import { copyFileSync, readdirSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync, existsSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const [dist, outDir] = process.argv.slice(2).map(p => resolve(p))
if (!dist || !outDir) { console.error('usage: pack-site.mjs <toolchain-dir> <out-dir>'); process.exit(2) }

const tmp = join(outDir, '.tmp')
rmSync(tmp, { recursive: true, force: true })
mkdirSync(tmp, { recursive: true })

await build({
    entryPoints: [join(here, 'worker/mini-toolchain.worker.mjs')],
    outfile: join(tmp, 'mini-toolchain.worker.js'),
    bundle: true, format: 'esm', platform: 'browser', minify: true, target: 'es2022',
    external: ['node:*'], logLevel: 'warning',
})

const clangGen = join(here, 'node_modules/@yowasp/clang/gen')
const sources = [
    ['minic.wasm', join(dist, 'minic.wasm'), 'check'],
    ['stdlib.json', join(dist, 'stdlib.json'), 'check'],
    ...(existsSync(join(dist, 'libs.json')) ? [['libs.json', join(dist, 'libs.json'), 'check']] : []),
    ['libminirt-wasm32.a', join(dist, 'libminirt-wasm32.a'), 'full'],
    ['minirt-entry-wasm32.o', join(dist, 'minirt-entry-wasm32.o'), 'full'],
    ...['llvm.core.wasm', 'llvm.core2.wasm', 'llvm.core3.wasm', 'llvm.core4.wasm', 'llvm-resources.tar'].map(f => [f, join(clangGen, f), 'full']),
]
for (const [, p] of sources) if (!existsSync(p)) { console.error(`missing ${p}`); process.exit(1) }

const hash = createHash('sha256')
for (const [name, p] of [['mini-toolchain.worker.js', join(tmp, 'mini-toolchain.worker.js')], ...sources]) {
    hash.update(name).update(readFileSync(p))
}
const version = hash.digest('hex').slice(0, 12)
const vdir = join(outDir, version)
for (const e of readdirSync(outDir)) if (e !== version && e !== '.tmp') rmSync(join(outDir, e), { recursive: true, force: true })
rmSync(vdir, { recursive: true, force: true })
mkdirSync(vdir, { recursive: true })
copyFileSync(join(tmp, 'mini-toolchain.worker.js'), join(vdir, 'mini-toolchain.worker.js'))
const files = []
for (const [name, p, stage] of sources) {
    copyFileSync(p, join(vdir, name))
    files.push({ path: `${version}/${name}`, bytes: statSync(p).size, stage })
}
rmSync(tmp, { recursive: true, force: true })
const manifest = {
    version,
    worker: `${version}/mini-toolchain.worker.js`,
    files,
    capabilities: { check: true, compile: true, mir: true, llvmIr: true },
}
writeFileSync(join(outDir, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n')
console.log(`toolchain ${version}: ${files.length} files, ${(files.reduce((n, f) => n + f.bytes, 0) / 1e6).toFixed(1)} MB in ${outDir}`)
