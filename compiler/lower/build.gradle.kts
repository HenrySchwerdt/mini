plugins {
    kotlin("jvm")
}

kotlin { jvmToolchain(21) }

dependencies {
    // Bootstrap stage B2-3: own typed AST -> MIR lowering. Pure Kotlin (LowerPurityTest) that depends on the other pure
    // modules only (frontend: parser + semantic analysis, backend: the MIR data model); compiled by minic itself.
    implementation(project(":frontend"))
    implementation(project(":backend"))
    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("mini.lowerMain", file("src/main").absolutePath)
    systemProperty("mini.lowerBuildFile", file("build.gradle.kts").absolutePath)
    testLogging { events("passed", "failed", "skipped") }
}
