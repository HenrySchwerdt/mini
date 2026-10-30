package mini.minicnative

/*
 * The plain `minic-native` (C and textual LLVM output). The build with the LLVM C API bound in (compiler/llvm-c) has its own `main`
 * (mini.llvmapi) that calls [runDriver] with the API backend; bootstrap.sh and the tests choose which of the two files is compiled.
 */
fun main(args: Array<String>) {
    runDriver(args, null)
}
