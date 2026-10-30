import mini.wasm.WasmExport

@WasmExport
fun half(x: Int?): Int = (x ?: 0) / 2

fun main() {
    println(half(4))
}
