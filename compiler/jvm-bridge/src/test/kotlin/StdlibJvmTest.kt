import mini.bridge.FrontendDriver
import mini.bridge.Reachability
import mini.bridge.Stdlib
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files

/**
 * Tests of the Mini stdlib sources (std/kotlin-mini) that do not need the native backend:
 *
 *  1. [stdlibGivesIrForEverything]: all stdlib sources go through K2 in the JVM-free configuration used by
 *     `minic build` (no JDK, no kotlin-stdlib jar) and every function that is not abstract/external/builtin has an
 *     IR body.
 *  2. [stdlibUnitTestsOnJvm]: the stdlib sources (builtins excluded, `package kotlin.x` renamed to `mkt.x`,
 *     `mini.internal` replaced by the JVM implementation) are compiled for the JVM together with the unit tests in
 *     std/kotlin-mini-test and the tests are run. [testsAgreeWithRealStdlib] runs the same tests against the real
 *     kotlin-stdlib to show that the expectations are real Kotlin behaviour.
 *  3. [intrinsicsAreInSync]: every `@CSymbol` of the stdlib exists in minirt.h and in the JVM implementation.
 */
class StdlibJvmTest {
    private val repoRoot = File(System.getProperty("mini.repoRoot") ?: error("mini.repoRoot not set"))
    private val stdDir = File(repoRoot, "std/kotlin-mini")
    private val testDir = File(repoRoot, "std/kotlin-mini-test")
    private val work = Files.createTempDirectory("minic-stdlib-test-").toFile().also { it.deleteOnExit() }

    @Test
    fun stdlibGivesIrForEverything() {
        val user = File(work, "empty.kt").also { it.writeText("fun main() {}\n") }
        var checked = 0
        val bodiless = mutableListOf<String>()
        FrontendDriver.runFrontend(listOf(user), Stdlib.sources(stdDir), pruneStdlib = false) { module, _ ->
            val remaining = module.files.filterNot { Reachability.isBuiltinFile(it) }
            assertTrue(remaining.any { it.fileEntry.name.endsWith("ArrayList.kt") })
            for (file in remaining) {
                file.acceptChildrenVoid(object : IrVisitorVoid() {
                    override fun visitElement(element: org.jetbrains.kotlin.ir.IrElement) {
                        if (element is IrSimpleFunction && !element.isFakeOverride && !element.isExternal) {
                            val parent = element.parent
                            val abstract = element.modality == org.jetbrains.kotlin.descriptors.Modality.ABSTRACT ||
                                (parent is IrClass && parent.kind == org.jetbrains.kotlin.descriptors.ClassKind.INTERFACE)
                            if (!abstract && element.body == null) bodiless += "${file.fileEntry.name}: ${element.name}"
                            checked++
                        }
                        element.acceptChildrenVoid(this)
                    }
                })
            }
        }
        assertTrue(checked > 500, "expected a large stdlib, saw $checked functions")
        assertEquals(emptyList<String>(), bodiless, "functions without IR body")
    }

    // ---- JVM run ------------------------------------------------------------------------------

    private fun isBuiltinSource(f: File) = f.readText().contains("@file:kotlin.internal.Builtin")

    /** Stdlib files that exist only for the Mini frontend/backend and have JVM counterparts on the JVM. */
    private val jvmExcluded = listOf("kotlin/Throwable.kt", "kotlin/Platform.kt", "kotlin/jvm/", "kotlin/reflect/", "kotlin/io/",
        "java/", "mini/")

    /** `package.Name` of every non-private top-level class/interface/object declared in [files]. */
    private fun classifiers(files: List<File>): List<String> {
        val decl = Regex("(?m)^(?:(?:public|internal|abstract|open|data|fun|enum|sealed|annotation)\\s+)*(?:class|interface|object)\\s+(\\w+)")
        val pkg = Regex("(?m)^package ([\\w.]+)")
        val out = mutableListOf<String>()
        for (f in files) {
            val text = f.readText()
            val p = pkg.find(text)?.groupValues?.get(1) ?: continue
            if (!p.startsWith("kotlin") || p.startsWith("kotlin.internal")) continue
            for (m in decl.findAll(text)) {
                if (Regex("(?m)^private\\s[^\\n]*\\b${m.groupValues[1]}\\b").containsMatchIn(text)) continue
                out += "mkt" + p.removePrefix("kotlin") + "." + m.groupValues[1]
            }
        }
        return out
    }

    /**
     * `package kotlin.x` becomes `package mkt.x`. Types such as `List` of the default imports would win over star
     * imports, so every classifier of the stdlib sources is imported by name (explicit imports win).
     */
    private fun rename(text: String, names: List<String>): String {
        val header = "import mkt.*\nimport mkt.collections.*\nimport mkt.text.*\nimport mkt.comparisons.*\nimport mkt.ranges.*\nimport mkt.math.*\n" +
            names.joinToString("") { "import $it\n" }
        var out = text.replace(Regex("(?m)^package kotlin(\\.\\w+)?\\s*$")) { m -> "package mkt${m.groupValues[1]}\n$header" }
        out = out.replace("@OverloadResolutionByLambdaReturnType", "@OptIn(kotlin.experimental.ExperimentalTypeInference::class) @kotlin.OverloadResolutionByLambdaReturnType")
        out = out.replace(Regex("import kotlin\\.(comparisons|collections|text|ranges|math)\\."), "import mkt.\$1.")
        return out
    }

    private fun jvmClasspath(): String = File(kotlin.Unit::class.java.protectionDomain.codeSource.location.toURI()).absolutePath

    private fun compileJvm(sources: List<File>, out: File): List<String> {
        out.mkdirs()
        val args = K2JVMCompiler().createArguments()
        args.freeArgs = sources.map { it.absolutePath }
        args.destination = out.absolutePath
        args.noStdlib = true
        args.noReflect = true
        args.classpath = jvmClasspath()
        args.moduleName = "stdlibtest"
        args.allowKotlinPackage = true
        args.jvmTarget = "21"
        val errors = mutableListOf<String>()
        val collector = object : MessageCollector {
            override fun clear() {}
            override fun hasErrors() = errors.isNotEmpty()
            override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
                if (severity.isError) errors += "${location?.path?.let { File(it).name }}:${location?.line}: $message"
            }
        }
        val rc = K2JVMCompiler().exec(collector, Services.EMPTY, args)
        if (rc != ExitCode.OK && errors.isEmpty()) errors += "exit code $rc"
        return errors
    }

    private fun runTests(classes: File): List<String> {
        val loader = URLClassLoader(arrayOf(classes.toURI().toURL()), javaClass.classLoader)
        val failures = mutableListOf<String>()
        var ran = 0
        classes.walkTopDown().filter { it.isFile && it.name.endsWith("Kt.class") && it.path.contains("mkt/test") }.forEach { f ->
            val name = f.relativeTo(classes).path.removeSuffix(".class").replace('/', '.')
            val cls = loader.loadClass(name)
            for (m in cls.methods.filter { it.name.startsWith("test") && it.parameterCount == 0 }.sortedBy { it.name }) {
                ran++
                try {
                    m.invoke(null)
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    failures += "${cls.simpleName}.${m.name}: ${e.targetException}"
                }
            }
        }
        assertTrue(ran >= 30, "expected the stdlib unit tests to run, ran $ran")
        return failures
    }

    private fun testSources(dir: File, referenceStdlib: Boolean): List<File> {
        dir.mkdirs()
        return File(testDir, "tests").listFiles { f -> f.extension == "kt" }!!.sortedBy { it.name }.map { f ->
            val text = f.readText().let { if (referenceStdlib) it.replace(Regex("import mkt\\."), "import kotlin.") else it }
            File(dir, f.name).also { it.writeText(text) }
        }
    }

    @Test
    fun stdlibUnitTestsOnJvm() {
        val src = File(work, "jvm-src")
        val files = mutableListOf<File>()
        val included = Stdlib.sources(stdDir).filter { f ->
            val rel = f.relativeTo(stdDir).path
            !isBuiltinSource(f) && jvmExcluded.none { rel.startsWith(it) }
        }
        val names = classifiers(included)
        for (f in included) {
            val dest = File(src, f.relativeTo(stdDir).path)
            dest.parentFile.mkdirs()
            // kotlin.internal.* annotations stay in package kotlin.internal: the compiler recognises them by name
            dest.writeText(if (f.relativeTo(stdDir).path.startsWith("kotlin/internal/")) f.readText() else rename(f.readText(), names))
            files += dest
        }
        for (f in File(testDir, "jvm").walkTopDown().filter { it.isFile && it.extension == "kt" }) files += f
        files += testSources(File(work, "jvm-tests"), referenceStdlib = false)
        val out = File(work, "jvm-classes")
        val errors = compileJvm(files, out)
        assertEquals(emptyList<String>(), errors, "the stdlib sources must compile for the JVM")
        assertEquals(emptyList<String>(), runTests(out))
    }

    @Test
    fun testsAgreeWithRealStdlib() {
        val files = testSources(File(work, "ref-tests"), referenceStdlib = true)
        val out = File(work, "ref-classes")
        val errors = compileJvm(files, out)
        assertEquals(emptyList<String>(), errors)
        assertEquals(emptyList<String>(), runTests(out))
    }

    // ---- intrinsics -----------------------------------------------------------------------------

    @Test
    fun intrinsicsAreInSync() {
        val native = File(stdDir, "mini/internal/Intrinsics.kt").readText()
        val jvm = File(testDir, "jvm/mini/internal/Intrinsics.kt").readText()
        val header = File(repoRoot, "runtime/minirt/include/minirt.h").readText()
        val decl = Regex("@CSymbol\\(\"(\\w+)\"\\)\\s*public external fun (\\w+)\\(([^)]*)\\)(?::\\s*(\\w+\\??))?")
        val all = decl.findAll(native).toList()
        assertTrue(all.size >= 15, "expected the intrinsic declarations")
        for (m in all) {
            val (symbol, name, params, ret) = m.destructured
            val jvmDecl = Regex("fun $name\\(([^)]*)\\)(?::\\s*(\\w+\\??))?").find(jvm)
            assertTrue(jvmDecl != null, "JVM implementation of $name missing")
            assertEquals(params.trim(), jvmDecl!!.groupValues[1].trim(), "parameters of $name")
            assertEquals(ret, jvmDecl.groupValues[2], "result of $name")
            assertTrue(Regex("\\b$symbol\\(").containsMatchIn(header), "$symbol is not declared in minirt.h")
        }
    }
}
