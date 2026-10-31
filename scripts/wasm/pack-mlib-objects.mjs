#!/usr/bin/env node
// Build the reusable wasm32 object for each target-independent .mlib.
// Usage: node pack-mlib-objects.mjs <dist-dir> <minic-native>
import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { readFileSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { commands } from '@yowasp/clang'

const [distArg, compilerArg] = process.argv.slice(2)
if (!distArg || !compilerArg) {
    console.error('usage: pack-mlib-objects.mjs <dist-dir> <minic-native>')
    process.exit(2)
}
const dist = resolve(distArg)
const compiler = resolve(compilerArg)
const clangVersion = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8')).dependencies['@yowasp/clang']
const manifestPath = join(dist, 'libs.json')
const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'))
const libs = manifest.libs ?? []
const byName = new Map(libs.map(lib => [lib.name, lib]))
const sha256 = value => createHash('sha256').update(value).digest('hex')
const runtimeHash = sha256(Buffer.concat([
    readFileSync(join(dist, 'libminirt-wasm32.a')),
    readFileSync(join(dist, 'minirt-entry-wasm32.o')),
]))

function mlibInfo(path) {
    const lines = readFileSync(path, 'utf8').split('\n')
    return {
        compilerId: lines.find(line => line.startsWith('compiler '))?.slice(9) ?? '',
        hash: lines.find(line => line.startsWith('hash '))?.slice(5) ?? '',
        dependencies: lines.filter(line => line.startsWith('dep ')).map(line => {
            const [, name, hash] = line.split(' ')
            return { name, hash }
        }),
    }
}

function objectMeta(info) {
    const identity = JSON.stringify({
        format: 1, target: 'wasm32-wasip1', compilerId: info.compilerId, clangVersion, mlibHash: info.hash,
        dependencies: info.dependencies, runtimeHash, rcOpt: true, optLevel: '1',
    })
    return {
        key: sha256(identity), target: 'wasm32-wasip1', compilerId: info.compilerId, clangVersion,
        mlibHash: info.hash, dependencies: info.dependencies, runtimeHash, rcOpt: true, optLevel: '1',
    }
}

async function buildObject(name, mlibPath, deps) {
    const llPath = join(dist, `${name}.wasm.ll`)
    const objectPath = join(dist, `${name}.wasm.o`)
    const args = ['--target=wasm32', '--mlib-object', mlibPath, '--emit-mlib-object-ll', llPath]
    for (const dep of deps) args.push('--lib', dep)
    execFileSync(compiler, args, { stdio: 'inherit' })
    const started = Date.now()
    const tree = await commands.clang(
        ['--target=wasm32-wasip1', '-O1', '-Wno-override-module', '-c', llPath.slice(dist.length + 1), '-o', objectPath.slice(dist.length + 1)],
        { [llPath.slice(dist.length + 1)]: readFileSync(llPath) },
    )
    const object = tree?.[objectPath.slice(dist.length + 1)]
    if (!(object instanceof Uint8Array)) throw new Error(`clang did not produce ${objectPath}`)
    writeFileSync(objectPath, object)
    console.log(`pack-mlib-objects: ${name} ${object.length} bytes, clang ${Date.now() - started} ms`)
    return objectMeta(mlibInfo(mlibPath))
}

const stdlib = join(dist, 'stdlib.mlib')
manifest.stdlibObjectPath = 'stdlib.wasm.o'
manifest.stdlibObject = await buildObject('stdlib', stdlib, [])
for (const lib of libs) {
    if (!lib.path) throw new Error(`preinstalled library '${lib.name}' has no .mlib path`)
    const deps = [stdlib]
    for (const depName of lib.dependencies ?? []) {
        const dep = byName.get(depName)
        if (!dep?.path) throw new Error(`preinstalled library '${lib.name}' is missing dependency '${depName}'`)
        deps.push(join(dist, dep.path))
    }
    lib.objectMeta = await buildObject(lib.name, join(dist, lib.path), deps)
    lib.objectPath = `${lib.name}.wasm.o`
}
writeFileSync(manifestPath, JSON.stringify(manifest))
