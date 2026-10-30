import mini.wasm.WasmExport

@WasmExport("same")
fun a(): Int = 1

@WasmExport("same")
fun b(): Int = 2

fun main() {
    println(a() + b())
}
