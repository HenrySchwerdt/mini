import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory

/** The K2 compiler's PSI parser, used as the oracle for our own parser. */
@OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class, CompilerConfiguration.Internals::class, org.jetbrains.kotlin.CoreEnvironmentDeprecation::class)
object PsiOracle {
    private val disposable = Disposer.newDisposable()
    private val env: KotlinCoreEnvironment by lazy {
        val conf = CompilerConfiguration()
        conf.put(CommonConfigurationKeys.MODULE_NAME, "oracle")
        conf.extensionsStorage = org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar.ExtensionStorage()
        KotlinCoreEnvironment.createForProduction(disposable, conf, EnvironmentConfigFiles.JVM_CONFIG_FILES)
    }

    @Synchronized
    fun parse(name: String, text: String): KtFile = KtPsiFactory(env.project, false).createFile(name, text)

    fun errors(file: KtFile): List<PsiErrorElement> = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java).toList()

    fun line(text: String, offset: Int): Int {
        var l = 1
        var i = 0
        while (i < offset && i < text.length) {
            if (text[i] == '\n') l++
            i++
        }
        return l
    }
}

@Suppress("unused")
private fun PsiElement.dummy() {}
