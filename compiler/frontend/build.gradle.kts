plugins {
    kotlin("jvm")
}

kotlin { jvmToolchain(21) }

dependencies {
    // Main sources: only the Kotlin standard library (self-hosting rule, docs/plan-kotlin.md section 10a;
    // enforced by FrontendPurityTest). This is bootstrap stage B2: our own Kotlin frontend, compiled by minic itself.
    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("mini.frontendMain", file("src/main").absolutePath)
    systemProperty("mini.frontendBuildFile", file("build.gradle.kts").absolutePath)
    systemProperty("mini.repoRoot", rootProject.projectDir.parentFile.absolutePath)
    systemProperty("mini.testdata", rootProject.file("testdata").absolutePath)
    maxHeapSize = "1g"
    testLogging { events("passed", "failed", "skipped") }
}
