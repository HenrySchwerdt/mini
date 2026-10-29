package mini.bridge

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import java.io.File
import java.nio.file.Files

/**
 * Runs the K2 frontend (FIR) and fir2ir on one source file and hands the resulting
 * post-fir2ir [IrModuleFragment] to [consumer].
 *
 * Supported entry point used: [K2JVMCompiler] driven programmatically plus an
 * [IrGenerationExtension] that is registered through the [CompilerPluginRegistrar]
 * service mechanism (`-Xplugin=<dir with META-INF/services>`). The extension runs right
 * after fir2ir and before any JVM lowering; the JVM class files the compiler produces
 * afterwards go to a temporary directory that is deleted again.
 */
object FrontendDriver {
    /** Receives the IR; must be set before the compiler is invoked (single-threaded use). */
    @Volatile
    internal var pending: ((IrModuleFragment, IrPluginContext) -> Unit)? = null

    class FrontendFailure(message: String) : Exception(message)

    fun runFrontend(source: File, consumer: (IrModuleFragment, IrPluginContext) -> Unit) {
        val work = Files.createTempDirectory("minic-").toFile()
        try {
            val services = File(work, "plugin/META-INF/services").also { it.mkdirs() }
            File(services, "org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar")
                .writeText(MiniPluginRegistrar::class.java.name + "\n")
            val out = File(work, "out").also { it.mkdirs() }

            val stdlib = File(kotlin.Unit::class.java.protectionDomain.codeSource.location.toURI())

            val compiler = K2JVMCompiler()
            val args = compiler.createArguments()
            args.freeArgs = listOf(source.absolutePath)
            args.destination = out.absolutePath
            args.noStdlib = true
            args.noReflect = true
            args.classpath = stdlib.absolutePath
            args.moduleName = "main"
            args.pluginClasspaths = arrayOf(File(work, "plugin").absolutePath)
            args.jvmTarget = "21"

            val collector = Collector()
            var captured: Throwable? = null
            pending = { m, c ->
                try {
                    consumer(m, c)
                } catch (t: Throwable) {
                    // do not let the Kotlin compiler wrap our diagnostics; rethrow after it returns
                    captured = t
                }
            }
            val code = try {
                compiler.exec(collector, Services.EMPTY, args)
            } finally {
                pending = null
            }
            captured?.let { throw it }
            if (collector.errors.isNotEmpty() || code != ExitCode.OK) {
                throw FrontendFailure(collector.errors.joinToString("\n").ifEmpty { "frontend failed: $code" })
            }
        } finally {
            work.deleteRecursively()
        }
    }

    private class Collector : MessageCollector {
        val errors = mutableListOf<String>()
        private var error = false

        override fun clear() { errors.clear(); error = false }
        override fun hasErrors(): Boolean = error

        override fun report(
            severity: CompilerMessageSeverity,
            message: String,
            location: CompilerMessageSourceLocation?,
        ) {
            val where = location?.let { "${File(it.path).name}:${it.line}:${it.column}: " } ?: ""
            when {
                severity.isError -> { error = true; errors += "${where}error: $message" }
                severity == CompilerMessageSeverity.WARNING || severity == CompilerMessageSeverity.STRONG_WARNING ->
                    System.err.println("${where}warning: $message")
                else -> {}
            }
        }
    }
}

@OptIn(ExperimentalCompilerApi::class)
class MiniPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = "mini.ir-bridge"
    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        IrGenerationExtension.registerExtension(object : IrGenerationExtension {
            override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
                FrontendDriver.pending?.invoke(moduleFragment, pluginContext)
            }
        })
    }
}
