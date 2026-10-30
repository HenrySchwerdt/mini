plugins {
    kotlin("jvm")
}

kotlin { jvmToolchain(21) }

dependencies {
    // Main sources: only the Kotlin standard library. Do not add other dependencies here
    // (self-hosting rule, docs/plan-kotlin.md section 10a; enforced by PurityTest).
    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("mini.backendMain", file("src/main").absolutePath)
    systemProperty("mini.backendBuildFile", file("build.gradle.kts").absolutePath)
    systemProperty("mini.repoRoot", rootProject.projectDir.parentFile.absolutePath)
    testLogging { events("passed", "failed", "skipped") }
}
