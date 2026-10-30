import mini.frontend.*
import org.junit.jupiter.api.Test
import java.io.File

/** Developer tool: prints our outline and the PSI outline of the file named by -Dmini.probe (skipped otherwise). */
class ProbeTest {
    @Test
    fun probe() {
        val path = System.getProperty("mini.probe") ?: System.getenv("MINI_PROBE") ?: return
        val f = File(path)
        if (!f.isFile) return
        val text = f.readText()
        val psi = PsiOracle.parse("probe.kt", text)
        val ours = parseKotlin(text)
        println("PSI errors: " + PsiOracle.errors(psi).map { it.errorDescription + "@" + it.textOffset })
        println("our errors: " + ours.errors)
        println("OURS:\n" + Outline().file(ours.file).joinToString("\n"))
        println("PSI:\n" + PsiOutline().file(psi).joinToString("\n"))
    }
}
