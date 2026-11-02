package mini.llvmapi

import mini.backend.MirModule
import mini.minicnative.LlvmApi
import mini.internal.CSymbol
import mini.io.deletePath

@CSymbol("mk_platform_spawn")
external fun spawnLinker(argv: String): Int

/** `minic-native` with the LLVM C API bound in: object files through the target machine, linking with the system C compiler, the ORC JIT. */
class LlvmApiImpl : LlvmApi {
    override fun emitObject(module: MirModule, path: String, llPath: String?, optLevel: Int, rcOpt: Boolean) {
        LlvmApiEmitter.compileToObject(module, path, llPath, optLevel, rcOpt, true, true, false)
    }

    override fun link(objects: List<String>, exe: String, libs: List<String>, linkArgs: List<String>, cc: String): Int {
        val args = ArrayList<String>()
        args += cc; args += "-o"; args += exe
        args += objects; args += linkArgs
        if (LlvmApiEmitter.defaultTriple().contains("windows")) args += "-Wl,/include:wmain" else args += "-lm"
        for (l in libs) args += "-l" + l
        require(args.none { it.contains('\u0000') || it.contains('\u0001') }) { "linker arguments contain a reserved separator" }
        val status = spawnLinker(args.joinToString("\u0001"))
        if (status == 0) {
            for (o in objects) if (o == exe + ".o") deletePath(o)
        }
        return status
    }

    override fun run(module: MirModule, programArgs: List<String>, optLevel: Int, rcOpt: Boolean, timing: Boolean): Int =
        LlvmJit.run(module, programArgs, optLevel, rcOpt, timing)

    override fun createSession(optLevel: Int): mini.minicnative.LlvmApiSession = SessionAdapter(LlvmJitSession(optLevel))

    private class SessionAdapter(private val jit: LlvmJitSession) : mini.minicnative.LlvmApiSession {
        private var nextEntry = 0

        override fun add(module: MirModule, rcOpt: Boolean, entrySymbol: String?) {
            jit.addModule(module, rcOpt, entrySymbol)
        }

        override fun run(module: MirModule, rcOpt: Boolean, entrySymbol: String) {
            val symbol = if (entrySymbol.isEmpty()) "mini_repl_entry_${nextEntry++}" else entrySymbol
            jit.runModule(module, rcOpt, symbol)
        }

        override fun close() = jit.close()
    }

}
