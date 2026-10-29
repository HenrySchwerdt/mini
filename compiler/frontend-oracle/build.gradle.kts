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

tasks.test {
    useJUnitPlatform()
    systemProperty("mini.repoRoot", rootProject.projectDir.parentFile.absolutePath)
    systemProperty("mini.testdata", rootProject.file("testdata").absolutePath)
    systemProperty("mini.frontendSrc", rootProject.file("frontend/src/main/kotlin").absolutePath)
    inputs.dir(rootProject.file("frontend/src/main/kotlin"))
    inputs.dir(rootProject.file("testdata"))
    maxHeapSize = "2g"
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
