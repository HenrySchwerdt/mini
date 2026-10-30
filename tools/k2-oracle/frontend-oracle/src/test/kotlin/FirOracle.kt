import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.KtRealSourceElementKind
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import java.io.File
import java.nio.file.Files

/** One resolved FIR element: source range, FIR node class, rendered type and (for references/calls) the callee signature. */
data class FirRec(val start: Int, val end: Int, val kind: String, val type: String, val callee: String?, val extra: String = "")

/**
 * The oracle of bootstrap stage B2-2: runs the FIR resolution of the K2 compiler (same configuration as the jvm-bridge
 * `FrontendDriver`: our stdlib sources, `-no-stdlib -no-jdk -Xallow-kotlin-package`) over the given files and records, for
 * every expression of every file, the resolved type and the resolved callee. A FIR file checker sees the fully resolved
 * FIR of each file, so nothing but the public checker extension API is used.
 */
@OptIn(ExperimentalCompilerApi::class)
object FirOracle {
    @Volatile
    internal var sink: ((String, List<FirRec>) -> Unit)? = null

    private const val CACHE_VERSION = "v2"

    /** Like [analyze], but caches the records of the requested files on disk (keyed by the content of all inputs). */
    fun analyzeCached(files: List<File>, only: Set<String>): Pair<Map<String, List<FirRec>>, List<String>> {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(CACHE_VERSION.toByteArray())
        for (f in files) {
            md.update(f.absolutePath.toByteArray())
            md.update(f.readBytes())
        }
        for (o in only.sorted()) md.update(o.toByteArray())
        val key = md.digest().joinToString("") { "%02x".format(it) }
        val dir = File(System.getProperty("mini.oracleCache") ?: "build/sema-oracle-cache").also { it.mkdirs() }
        val cf = File(dir, "$key.tsv")
        if (cf.isFile) {
            val res = LinkedHashMap<String, ArrayList<FirRec>>()
            val errs = ArrayList<String>()
            var cur: ArrayList<FirRec>? = null
            for (line in cf.readLines()) {
                if (line.startsWith("#file\t")) {
                    cur = ArrayList()
                    res[line.substring(6)] = cur
                } else if (line.startsWith("#err\t")) {
                    errs.add(line.substring(5))
                } else if (cur != null) {
                    val p = line.split('\t')
                    cur.add(FirRec(p[0].toInt(), p[1].toInt(), p[2], p[3], if (p[4].isEmpty()) null else p[4], p.getOrElse(5) { "" }))
                }
            }
            return Pair(res, errs)
        }
        val (res, errs) = analyze(files, only)
        val sb = StringBuilder()
        for (e in errs) sb.append("#err\t").append(e.replace('\n', ' ')).append('\n')
        for ((p, l) in res) {
            sb.append("#file\t").append(p).append('\n')
            for (r in l) sb.append(r.start).append('\t').append(r.end).append('\t').append(r.kind).append('\t').append(r.type.replace('\t', ' ')).append('\t').append(r.callee?.replace('\t', ' ') ?: "").append('\t').append(r.extra).append('\n')
        }
        cf.writeText(sb.toString())
        return Pair(res, errs)
    }

    /** Runs K2 over [files] (absolute paths) and returns the records per absolute file path. */
    @Synchronized
    fun analyze(files: List<File>, only: Set<String>? = null): Pair<Map<String, List<FirRec>>, List<String>> {
        val result = LinkedHashMap<String, List<FirRec>>()
        val work = Files.createTempDirectory("sema-oracle-").toFile()
        val errors = ArrayList<String>()
        try {
            val services = File(work, "plugin/META-INF/services").also { it.mkdirs() }
            File(services, "org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar")
                .writeText(OracleRegistrar::class.java.name + "\n")
            val out = File(work, "out").also { it.mkdirs() }
            val compiler = K2JVMCompiler()
            val args = compiler.createArguments()
            args.freeArgs = files.map { it.absolutePath }
            args.destination = out.absolutePath
            args.noStdlib = true
            args.noReflect = true
            args.noJdk = true
            args.allowKotlinPackage = true
            args.stdlibCompilation = true
            args.moduleName = "main"
            args.pluginClasspaths = arrayOf(File(work, "plugin").absolutePath)
            args.jvmTarget = "21"
            val collector = object : MessageCollector {
                private var err = false
                override fun clear() { err = false }
                override fun hasErrors(): Boolean = err
                override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
                    if (severity.isError) {
                        err = true
                        errors += (location?.let { "${File(it.path).name}:${it.line}:${it.column}: " } ?: "") + message
                    }
                }
            }
            sink = { path, recs -> if (only == null || path in only) result[path] = recs }
            try {
                compiler.exec(collector, Services.EMPTY, args)
            } finally {
                sink = null
            }
        } finally {
            work.deleteRecursively()
        }
        return Pair(result, errors)
    }
}

@OptIn(ExperimentalCompilerApi::class)
class OracleRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = "mini.sema-oracle"
    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        FirExtensionRegistrarAdapter.registerExtension(OracleFirRegistrar())
        IrGenerationExtension.registerExtension(object : IrGenerationExtension {
            override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
                moduleFragment.files.clear() // nothing for the JVM backend to do
            }
        })
    }
}

class OracleFirRegistrar : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +FirAdditionalCheckersExtension.Factory { session: FirSession -> OracleCheckers(session) }
    }
}

class OracleCheckers(session: FirSession) : FirAdditionalCheckersExtension(session) {
    override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
        override val fileCheckers: Set<FirDeclarationChecker<FirFile>> = setOf(FileDump)
    }
}

object FileDump : FirDeclarationChecker<FirFile>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirFile) {
        val s = FirOracle.sink ?: return
        val path = declaration.sourceFile?.path ?: return
        s(File(path).absolutePath, FirDumper().dump(declaration))
    }
}

@Suppress("unused")
private fun KtSourceElement.real(): Boolean = kind is KtRealSourceElementKind

@Suppress("unused")
private fun FirElement.dummy() {}
