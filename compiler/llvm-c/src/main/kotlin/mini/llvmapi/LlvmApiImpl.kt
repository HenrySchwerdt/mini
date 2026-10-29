package mini.llvmapi

import kotlin.native.CName
import kotlinx.cinterop.*
import mini.backend.MirModule
import mini.llvm.*
import mini.minicnative.LlvmApi

@CName("system") external fun cSystem(command: CPointer<ByteVar>): Int
@CName("remove") external fun cRemove(path: CPointer<ByteVar>): Int

/** `minic-native` with the LLVM C API bound in: object files through the target machine, linking with the system C compiler, the ORC JIT. */
class LlvmApiImpl : LlvmApi {
    override fun emitObject(module: MirModule, path: String, llPath: String?, optLevel: Int, rcOpt: Boolean) {
        LlvmApiEmitter.compileToObject(module, path, llPath, optLevel, rcOpt, true, true, false)
    }

    override fun link(objects: List<String>, exe: String, libs: List<String>, linkArgs: List<String>, cc: String): Int {
        val cmd = StringBuilder()
        cmd.append(shellQuote(cc)).append(" -o ").append(shellQuote(exe))
        for (o in objects) cmd.append(' ').append(shellQuote(o))
        for (a in linkArgs) cmd.append(' ').append(shellQuote(a))
        cmd.append(" -lm")
        for (l in libs) cmd.append(" -l").append(shellQuote(l))
        val status = memScoped { cSystem(cmd.toString().cstr.ptr) }
        if (status == 0) {
            // the temporary object `--exe` made next to the executable
            for (o in objects) if (o == exe + ".o") memScoped { cRemove(o.cstr.ptr) }
        }
        return (status shr 8) and 255
    }

    override fun run(module: MirModule, programArgs: List<String>, optLevel: Int, rcOpt: Boolean): Int {
        throw IllegalStateException("--run: not implemented yet")
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
