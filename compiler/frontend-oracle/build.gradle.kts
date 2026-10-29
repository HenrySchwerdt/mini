plugins {
    kotlin("jvm")
}

kotlin { jvmToolchain(21) }

val kotlinVersion: String by project

// Test-only module: compares the pure-Kotlin frontend (:frontend) with the PSI parser of the K2 compiler
// (kotlin-compiler-embeddable), the oracle of bootstrap stage B2. It also hosts the native self-compilation test,
// which needs minic (:jvm-bridge). The frontend module itself stays free of any such dependency.
dependencies {
    testImplementation(project(":frontend"))
    testImplementation(kotlin("test"))
    testImplementation(project(":jvm-bridge"))
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// the JVM run of the driver (compiler/frontend/driver/common); `native/Main.kt` only exists for minic
sourceSets {
    test {
        kotlin.srcDir(rootProject.file("frontend/driver/common"))
    }
}

tasks.test {
    useJUnitPlatform()
    // developer probes (SemaProbeTest) depend on environment variables
    systemProperty("mini.semaBaseline", file("src/test/resources/sema-oracle-baseline.txt").absolutePath)
    systemProperty("mini.reports", layout.buildDirectory.dir("reports").get().asFile.absolutePath)
    systemProperty("mini.oracleCache", layout.buildDirectory.dir("sema-oracle-cache").get().asFile.absolutePath)
    inputs.property("semaAreas", System.getenv("MINI_SEMA_AREAS") ?: "")
    inputs.property("semaUpdate", System.getenv("MINI_SEMA_UPDATE") ?: "")
    inputs.property("probe", System.getenv("MINI_FIR_PROBE") ?: "")
    inputs.property("probeDump", System.getenv("MINI_SEMA_DUMP") ?: "")
    inputs.property("probeCompare", System.getenv("MINI_SEMA_COMPARE") ?: "")
    val probeFile = System.getenv("MINI_FIR_PROBE")
    if (probeFile != null && File(probeFile).isFile) inputs.file(probeFile)
    systemProperty("mini.repoRoot", rootProject.projectDir.parentFile.absolutePath)
    systemProperty("mini.testdata", rootProject.file("testdata").absolutePath)
    systemProperty("mini.frontendSrc", rootProject.file("frontend/src/main/kotlin").absolutePath)
    inputs.dir(rootProject.file("frontend/src/main/kotlin"))
    inputs.dir(rootProject.file("frontend/driver"))
    inputs.dir(rootProject.projectDir.parentFile.resolve("runtime"))
    inputs.dir(rootProject.projectDir.parentFile.resolve("stdlib"))
    inputs.dir(rootProject.file("testdata"))
    maxHeapSize = "2g"
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
