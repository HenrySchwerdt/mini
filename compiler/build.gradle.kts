// Root project: shared configuration only. The Kotlin version is pinned in
// gradle.properties and in the plugin declaration below (docs/plan-kotlin.md, section 12).
plugins {
    kotlin("jvm") version "2.4.21" apply false
}

allprojects {
    group = "mini"
    version = "0.0.1"
}

// D1 benchmarks (docs/benchmarks.md): micro programs and the self-hosted backend, baseline vs RC elision.
tasks.register<Exec>("bench") {
    group = "verification"
    description = "Runs compiler/bench/run.sh (time and MK_STATS counters with and without RcOpt)"
    dependsOn(":jvm-bridge:installDist", ":native-driver:installDist")
    workingDir = rootDir.parentFile
    environment("BENCH_NO_BUILD", "1")
    val extra = (findProperty("bench.args") as String?)?.split(" ")?.filter { it.isNotEmpty() } ?: emptyList()
    commandLine(listOf("bash", "compiler/bench/run.sh") + extra)
}
