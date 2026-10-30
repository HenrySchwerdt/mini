#!/usr/bin/env node
// Runs a wasm32-wasi command module under node:wasi (Node 22+).
//   node run-wasi.mjs [--dir <hostdir>=<guestpath>]... <prog.wasm> [args...]
// stdout/stderr/stdin are inherited, the exit code is the program's.
import { WASI } from 'node:wasi'
import { readFileSync } from 'node:fs'

const argv = process.argv.slice(2)
const preopens = {}
while (argv[0] === '--dir') {
    argv.shift()
    const [host, guest] = argv.shift().split('=')
    preopens[guest ?? host] = host
}
const file = argv.shift()
const wasi = new WASI({ version: 'preview1', args: [file, ...argv], env: {}, preopens, returnOnExit: true })
const { instance } = await WebAssembly.instantiate(readFileSync(file), wasi.getImportObject())
process.exitCode = wasi.start(instance)
