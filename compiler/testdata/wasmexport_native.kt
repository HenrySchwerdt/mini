import mini.wasm.WasmExport

class Box(val n: Int)

@WasmExport
fun make(n: Int): Box = Box(n)

@WasmExport
fun open(b: Box, s: String, xs: IntArray): Int = b.n + s.length + xs.size

// outside a wasm library build the annotation does nothing
fun main() {
    println(open(make(1), "ab", intArrayOf(1, 2, 3)))
}
