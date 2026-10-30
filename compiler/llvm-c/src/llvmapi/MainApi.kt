package mini.llvmapi

import mini.minicnative.runDriver

/**
 * The entry of `minic-native` built with the LLVM C API (compiler/llvm-c): the plain driver (mini.minicnative.runDriver) plus the API
 * backend. The plain build has its own `main` in minic-native/.../Main.kt; a build uses exactly one of the two files.
 */
fun main(args: Array<String>) {
    runDriver(args, LlvmApiImpl())
}
