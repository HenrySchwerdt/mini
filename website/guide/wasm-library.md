# WebAssembly libraries

Mini compiles Kotlin to a WebAssembly **library**: a module without `main` whose `@WasmExport` functions JavaScript calls directly, in a browser or in Node. The build also writes the JavaScript glue (an ES module) and TypeScript declarations, so the Kotlin code is used like an npm package. It is the counterpart of Kotlin/Wasm's `@JsExport` and of Rust's wasm-bindgen.

```text
src/*.kt  --mini build-->  build/wasm/mathlib.wasm   the module (about 60 KB for the example below)
                           build/wasm/mathlib.mjs    ES module: load(), one function per export, handle classes
                           build/wasm/mathlib.d.ts   TypeScript declarations
```

This is not the browser *playground*, which runs the compiler itself in the page: here your Kotlin code is the module and the compiler runs at build time.

## A complete example

Create the project with the template (it contains this example, a test, a Node script and an HTML page):

```sh
mini new mathlib --template wasm-lib
cd mathlib
mini test        # the tests in tests/ run natively, @WasmExport has no effect there
mini build       # build/wasm/mathlib.wasm, mathlib.mjs, mathlib.d.ts
node run.mjs
```

`src/lib.kt`:

```kotlin norun
import mini.wasm.WasmExport

val greeting = "Hello"                      // top-level properties are initialised by load()

class Counter(private var value: Int) {
    fun next(): Int {
        value += 1
        return value
    }
}

@WasmExport
fun add(a: Int, b: Int): Int = a + b

@WasmExport("addLong")                      // the name seen from JavaScript
fun add64(a: Long, b: Long): Long = a + b

@WasmExport
fun greet(name: String): String = greeting + ", " + name + "!"

@WasmExport
fun scale(values: DoubleArray, factor: Double): DoubleArray {
    val out = DoubleArray(values.size)
    for (i in values.indices) out[i] = values[i] * factor
    return out
}

@WasmExport
fun newCounter(start: Int): Counter = Counter(start)

@WasmExport
fun nextValue(counter: Counter): Int = counter.next()

@WasmExport
fun parse(text: String): Int = text.toInt()   // throws NumberFormatException for "abc"
```

`mini.toml`:

```toml
[package]
name = "mathlib"
version = "0.1.0"
kind = "wasm-lib"
```

Use it from JavaScript (Node 20+, or any browser that supports ES modules and WebAssembly):

```js
import { load, MiniError } from './build/wasm/mathlib.mjs'

const lib = await load()                  // fetches mathlib.wasm next to the module, initialises the runtime

lib.add(2, 3)                             // 5
lib.addLong(2n ** 40n, 1n)                // 1099511627777n  (Long is a bigint)
lib.greet('JS')                           // "Hello, JS!"
lib.scale([1, 2, 3], 2.5)                 // Float64Array [ 2.5, 5, 7.5 ]

const counter = lib.newCounter(10)        // an object that lives inside the module
lib.nextValue(counter)                    // 11
lib.nextValue(counter)                    // 12
counter.dispose()                         // release it (it is also released when the wrapper is garbage collected)

try {
  lib.parse('abc')
} catch (e) {
  e instanceof MiniError                  // true; e.message is 'java.lang.NumberFormatException: For input string: "abc"'
}
```

TypeScript sees `mathlib.d.ts` next to the module:

```ts
import { load, Counter, type Library } from './build/wasm/mathlib.mjs'

const lib: Library = await load()
const n: number = lib.add(1, 2)
const c: Counter = lib.newCounter(0)        // Counter is a class with dispose() and [Symbol.dispose]
const big: bigint = lib.addLong(1, 2)       // Long parameters take a bigint or a number, results are bigint
```

In a browser, serve the directory over HTTP (`application/wasm` gives streaming compilation, other types still work) and import the module from a `<script type="module">`; `index.html` of the template does that.

## Types

| Kotlin | JavaScript parameter | JavaScript result |
|---|---|---|
| `Int`, `Float`, `Double` | `number` | `number` |
| `Long` | `bigint` (a `number` is converted) | `bigint` |
| `Boolean` | `boolean` | `boolean` |
| `Unit` | | `undefined` |
| `String` | `string`, copied as UTF-8 | `string` |
| `IntArray`, `LongArray`, `DoubleArray`, `FloatArray` | typed array or plain array, **copied** into the module | typed array (`Int32Array`, `BigInt64Array`, `Float64Array`, `Float32Array`), a copy |
| a class (not nullable) | the handle object a function returned | a handle object |

* **Handles** are opaque: JavaScript cannot read fields or call methods, it passes the object back to exported functions (write exported functions that take the object as their first parameter). A returned handle holds one reference; `dispose()` gives it back, the module frees the object when its last reference is gone. A disposed handle throws when used. Handles of classes you did not write (`ArrayList`) work too.
* Arrays and strings are copied in both directions, so JavaScript and Kotlin never share memory. Large arrays that cross often are better kept in Kotlin behind a handle.
* Nullable types, `Char`, `Byte`, `Short`, `Array<T>`, `List<T>`, lambdas passed as parameters, generics, extension functions, `suspend` and `vararg` are rejected at build time with the position, the type and the list of what is allowed, for example: `error: @WasmExport function 'half': parameter 'x' has the type 'Int?', which cannot cross the boundary`.

## Errors

An exception that escapes an exported function never traps the module: the function returns normally, the glue reads the message from the module and throws a `MiniError` (a subclass of `Error`) whose message is the exception as Kotlin prints it (`java.lang.IllegalStateException: text`). The module stays usable. `println` output goes to `console.log` (Node: `process.stdout`); pass `load(source, { stdout, stderr })` to capture it.

## `load`

```ts
load(source?: string | URL | Response | Promise<Response> | BufferSource | WebAssembly.Module, options?: { stdout?, stderr? }): Promise<Library>
```

Without `source` the `.wasm` is found next to the module (`import.meta.url`); in Node it is read from disk, elsewhere fetched. Every call creates a new instance with its own memory. `library.raw` holds the raw wasm exports.

## Build

```sh
mini build                      # kind = "wasm-lib" in mini.toml
mini build --release            # -O2, stripped
mini build --target wasm32 --lib   # the same for a package of another kind
```

The build needs `clang` with the wasm32 target and `wasm-ld` (Debian/Ubuntu: `apt install clang lld`) and the wasm32 runtime plus wasi-libc, which `scripts/wasm/install-wasm32.sh` stages next to the toolchain in a source checkout (`MINI_WASM_CLANG`, `MINI_WASM_LD`, `MINI_WASM_SYSROOT` and `MINI_WASM_RUNTIME` point elsewhere). Dependencies of the package and the standard library are used as precompiled libraries, like in native builds. `mini test` runs the tests natively.

Without `mini`, the compiler does the same steps:

```sh
minic-native --target=wasm32 --emit=wasm-lib --emit-ll lib.ll --emit-js lib.mjs --emit-dts lib.d.ts --wasm-file lib.wasm \
    lib.kt --lib stdlib.mlib --compiler-id <id>
clang --target=wasm32-wasip1 -O1 -c lib.ll -o lib.o
wasm-ld -m wasm32 --no-entry -z stack-size=1048576 -L<sysroot>/lib/wasm32-wasip1 <sysroot>/lib/wasm32-wasip1/crt1-reactor.o lib.o \
    libminirt-wasm32.a -lc <sysroot>/lib/wasm32-unknown-wasip1/libclang_rt.builtins.a -o lib.wasm
```

## The module interface

For other hosts than the generated glue (Deno, a Rust or Python wasm runtime) the module is a WASI *reactor*: no `_start`, `_initialize` and `memory` are exported, and the WASI imports are `fd_write`, `fd_close`, `fd_seek` and `fd_fdstat_get`.

| export | |
|---|---|
| `mini_init()` | call once after `_initialize`: initialises the runtime and the top-level properties |
| `<name>(...)` | one per `@WasmExport`: `Int`, `Boolean` (0 / 1) are `i32`, `Long` `i64`, `Float` `f32`, `Double` `f64`; a `String` or array parameter takes two `i32` (pointer, length in bytes / elements) to bytes the caller wrote into memory from `mini_alloc`; a class parameter or result is an `i32` handle |
| `mini_alloc(n)`, `mini_free(p)` | memory for arguments; also frees string and array results |
| `mini_result_len()` | length (bytes / elements) of the string or array the last call returned |
| `mini_take_error()` | after every call: `0`, or a pointer to the NUL-terminated UTF-8 message of the exception that escaped (then the result is a dummy) |
| `mini_release(handle)` | gives a handle back |

## Limits

* Only top-level functions are exported, not classes or methods; functions of dependencies are not re-exported.
* Single threaded, no coroutines; the 1 MiB stack bounds the recursion depth.
* No `Char`, `Byte`/`ByteArray` (the language has no `ByteArray` yet), no nullable values and no JavaScript callbacks.
* Files are not available: WASI file access is not provided, `mini.io` functions fail.
