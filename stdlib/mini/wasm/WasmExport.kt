/*
 * WebAssembly libraries: top-level functions that JavaScript can call (website/guide/wasm-library.md).
 */
package mini.wasm

import kotlin.annotation.AnnotationTarget.*

/**
 * `@WasmExport fun add(a: Int, b: Int): Int = a + b` exports the function from a wasm library (`minic-native --target=wasm32
 * --emit=wasm-lib`, `mini build --target wasm32`) under [name] (default: the function's name). Parameters and results may be
 * Int, Long, Double, Float, Boolean, String, IntArray, LongArray, DoubleArray, FloatArray and non-null class types (opaque handles);
 * the result may also be Unit. Exceptions that escape the function reach JavaScript as an `Error`. Native builds ignore the annotation.
 */
@Target(FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class WasmExport(val name: String = "")
