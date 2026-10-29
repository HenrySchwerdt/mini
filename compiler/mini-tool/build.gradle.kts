plugins {
    kotlin("jvm")
    application
}

kotlin { jvmToolchain(21) }

val repoRoot: File = rootProject.projectDir.parentFile

dependencies {
    // The core of `mini` is pure Kotlin (MiniToolPurityTest); it reuses the pure-Kotlin parser of the own frontend
    // to find @Test functions. Only src/main/kotlin/mini/tool/jvm touches the JVM (the `Platform` implementation).
    implementation(project(":frontend"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:${property("kotlinVersion")}")
    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("mini.tool.jvm.MainKt")
    applicationName = "mini"
    applicationDefaultJvmArgs = listOf("-Xss16m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-Xshare:auto")
}

// `mini explain <code>`: the texts come from docs/memory-model.md section 7 and are compiled in as Kotlin source,
// so the (pure) core needs no resource loading. After B3 the same generator runs as a `mini` build step.
val generateExplain = tasks.register("generateExplainData") {
    val doc = repoRoot.resolve("docs/memory-model.md")
    val outDir = layout.buildDirectory.dir("generated/explain")
    inputs.file(doc)
    outputs.dir(outDir)
    doFirst {
        val lines = doc.readLines()
        val start = lines.indexOfFirst { it.startsWith("## 7. Diagnostic Catalog") }
        require(start >= 0) { "section 7 not found in $doc" }
        val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
        val sec = lines.subList(start, end)
        val heading = Regex("""^### 7\.(\d+a?) (.*)$""")
        val code = Regex("""\bM\d{4}\b""")
        class Sec(val num: String, val title: String, val body: MutableList<String>)
        val secs = ArrayList<Sec>()
        for (l in sec) {
            val m = heading.find(l)
            if (m != null) { secs += Sec(m.groupValues[1], m.groupValues[2], ArrayList()); continue }
            if (secs.isEmpty()) continue
            if (l.trim() == "---") continue
            secs.last().body += l
        }
        fun lit(s: String): String {
            val sb = StringBuilder("\"")
            for (ch in s) when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '$' -> sb.append("\\$")
                '\n' -> sb.append("\\n")
                '\r' -> {}
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
            return sb.append('"').toString()
        }
        val sb = StringBuilder("// GENERATED from docs/memory-model.md section 7 by :mini-tool:generateExplainData. Do not edit.\n")
        sb.append("package mini.tool\n\ninternal object ExplainData {\n")
        sb.append("    /** (codes, title, markdown body) per catalog section */\n")
        sb.append("    val sections: List<Triple<List<String>, String, String>> = listOf(\n")
        for (s in secs) {
            val codes = code.findAll(s.title).map { it.value }.toMutableList()
            if (s.title.contains("M0201") && s.title.contains("M0203")) { codes.clear(); codes += listOf("M0201", "M0202", "M0203") }
            if (codes.isEmpty()) continue
            val body = s.body.joinToString("\n").trim('\n')
            sb.append("        Triple(listOf(${codes.joinToString { lit(it) }}), ${lit(s.title)}, ${lit(body)}),\n")
        }
        sb.append("    )\n")
        // the overview table of 7.19 (code | severity | short)
        val rows = lines.filter { it.startsWith("| M0") }.map { r -> r.trim('|').split("|").map { it.trim() } }.filter { it.size >= 3 }
        sb.append("    /** (code, severity, short description) from the overview table */\n")
        sb.append("    val overview: List<Triple<String, String, String>> = listOf(\n")
        for (r in rows.distinctBy { it[0] }.sortedBy { it[0] }) sb.append("        Triple(${lit(r[0])}, ${lit(r[1])}, ${lit(r[2])}),\n")
        sb.append("    )\n}\n")
        val f = outDir.get().asFile.resolve("mini/tool/ExplainData.kt")
        f.parentFile.mkdirs()
        f.writeText(sb.toString())
    }
}
sourceSets.main { kotlin.srcDir(layout.buildDirectory.dir("generated/explain")) }
tasks.named("compileKotlin") { dependsOn(generateExplain) }

tasks.test {
    useJUnitPlatform()
    dependsOn(":jvm-bridge:installDist")
    systemProperty("mini.toolMain", file("src/main/kotlin/mini/tool").absolutePath)
    systemProperty("mini.repoRoot", repoRoot.absolutePath)
    inputs.dir(repoRoot.resolve("runtime"))
    inputs.dir(repoRoot.resolve("stdlib"))
    maxHeapSize = "1g"
    testLogging { events("passed", "failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
