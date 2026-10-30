// Calls the wasm library built from scripts/wasm/wasm-lib/app (see test-wasm-lib.sh) from Node and compares the results.
//   node test.mjs <dir with mathlib.mjs, mathlib.wasm, mathlib.d.ts>
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { pathToFileURL } from 'node:url'
import { join, resolve } from 'node:path'

const dir = resolve(process.argv[2])
const { load, MiniError, Counter } = await import(pathToFileURL(join(dir, 'mathlib.mjs')).href)

let out = ''
const lib = await load(undefined, { stdout: s => { out += s } })
let tests = 0
const t = (name, f) => { f(); tests++ }

t('numbers', () => {
    assert.equal(lib.add(2, 3), 5)
    assert.equal(lib.add(2147483647, 1), -2147483648)           // Int wraps like Kotlin
    assert.equal(lib.addLong(2n ** 40n, 1n), 2n ** 40n + 1n)    // Long is a bigint
    assert.equal(lib.addLong(5, 6), 11n)                        // plain numbers are accepted
    assert.equal(lib.hypot(3, 4), 5)
    assert.equal(lib.halve(5), 2.5)
    assert.equal(lib.isEven(4), true)
    assert.equal(lib.isEven(7), false)
})
t('strings', () => {
    assert.equal(lib.reverse('abc'), 'cba')
    assert.equal(lib.reverse(''), '')
    assert.equal(lib.greet('JS'), 'Hello, wasm, JS!')           // top-level property initialised by mini_init
    assert.equal(lib.reverse('héllo 世界'), '界世 olléh')   // UTF-8 <-> UTF-16
    assert.equal(lib.reverse('x'.repeat(100000)).length, 100000)
})
t('arrays', () => {
    assert.equal(lib.sum([1, 2, 3, 4]), 10)
    assert.equal(lib.sum(new Int32Array([10, 20, 30])), 60)
    assert.equal(lib.sum(new Int32Array(0)), 0)
    assert.deepEqual(Array.from(lib.scale([1, 2, 3], 2.5)), [2.5, 5, 7.5])
    assert.ok(lib.scale(new Float64Array([1]), 2) instanceof Float64Array)
    assert.deepEqual(Array.from(lib.squares(6)), [0, 1, 4, 9, 16, 25])
    assert.equal(lib.squares(0).length, 0)
})
t('handles', () => {
    const c = lib.newCounter(10)
    assert.ok(c instanceof Counter)
    assert.equal(lib.increment(c), 11)
    assert.equal(lib.increment(c), 12)
    const d = lib.newCounter(100)
    assert.equal(lib.increment(d), 101)
    assert.equal(lib.increment(c), 13)
    c.dispose()
    assert.equal(c.disposed, true)
    assert.throws(() => lib.increment(c), /disposed/)
    c.dispose()                                                // twice is fine
    assert.throws(() => lib.increment({}), TypeError)
    d.dispose()
})
t('exceptions', () => {
    assert.throws(() => lib.fail('boom'), e => e instanceof MiniError && e instanceof Error && e.message === 'java.lang.IllegalStateException: boom')
    assert.throws(() => lib.parse('xyz'), /NumberFormatException/)
    assert.equal(lib.fail(''), 0)                              // the module keeps working after an error
    assert.equal(lib.parse('42'), 42)
    assert.throws(() => lib.add('x', 1), TypeError)
})
t('state and output', () => {
    const before = lib.callCount()
    for (let i = 0; i < 1000; i++) lib.add(i, i)
    assert.equal(lib.callCount(), before + 1000)
    lib.log('hello')
    assert.equal(out, 'log: hello\n')
})
t('libraries', () => {
    assert.equal(lib.shoutIt('hi'), 'HI!')
    assert.equal(lib.words('a bc  def'), 3)
})
t('stress: no leaks of the wasm memory', () => {
    const pages = lib.raw.memory.buffer.byteLength
    for (let i = 0; i < 20000; i++) {
        lib.reverse('some text to copy back and forth')
        lib.scale([1, 2, 3, 4], 2)
        lib.newCounter(i).dispose()
    }
    assert.ok(lib.raw.memory.buffer.byteLength <= pages * 2, 'memory grew from ' + pages + ' to ' + lib.raw.memory.buffer.byteLength)
})
t('raw ABI', () => {
    const x = lib.raw
    assert.ok(x.memory instanceof WebAssembly.Memory)
    assert.equal(typeof x._start, 'undefined')                  // a reactor: no entry point
    assert.equal(typeof x.mini_init, 'function')
    assert.equal(x.add(40, 2), 42)
})
t('declarations', () => {
    const d = readFileSync(join(dir, 'mathlib.d.ts'), 'utf8')
    for (const line of [
        'add(a: number, b: number): number',
        'addLong(a: bigint | number, b: bigint | number): bigint',
        'isEven(n: number): boolean',
        'reverse(s: string): string',
        'sum(a: Int32Array | number[]): number',
        'scale(a: Float64Array | number[], k: number): Float64Array',
        'squares(n: number): Int32Array',
        'newCounter(start: number): Counter',
        'increment(c: Counter): number',
        'log(msg: string): void',
        'export declare class Counter {',
        'export declare function load(source?: Source, options?: LoadOptions): Promise<Library>',
    ]) assert.ok(d.includes(line), 'mathlib.d.ts lacks: ' + line)
})

console.log('wasm-lib node tests: ' + tests + ' groups ok')
