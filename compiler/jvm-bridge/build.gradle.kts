plugins {
    kotlin("jvm")
    application
}

kotlin { jvmToolchain(21) }

val kotlinVersion: String by project

dependencies {
    implementation(project(":backend"))
    implementation(project(":lower"))
    implementation(project(":frontend"))
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
    testImplementation(project(":native-driver"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// v1-A: the self-hosting driver (compiler/selfhost) is compiled for the JVM as part of the tests, so that SelfHostTest
// can run it on the JVM and compare with the natively compiled one.
sourceSets {
    test {
        kotlin.srcDir(rootProject.file("selfhost"))
    }
}

application {
    mainClass.set("mini.bridge.MainKt")
    applicationName = "minic"
}

tasks.test {
    useJUnitPlatform()
    // The end-to-end tests build real executables from compiler/testdata.
    systemProperty("mini.testdata", rootProject.file("testdata").absolutePath)
    systemProperty("mini.repoRoot", rootProject.projectDir.parentFile.absolutePath)
    // Memory-model corpus (docs/memory-model.md) and the mini.memory API sources for MemoryCheckTest.
    systemProperty("mini.memoryCorpus", rootProject.file("testdata/memory").absolutePath)
    systemProperty("mini.memoryApi", rootProject.projectDir.parentFile.resolve("stdlib/mini/memory").absolutePath)
    systemProperty("mini.memoryExtra", rootProject.file("testdata/memory-extra").absolutePath)
    inputs.dir(rootProject.file("testdata/memory"))
    inputs.dir(rootProject.file("testdata/memory-extra"))
    inputs.dir(rootProject.projectDir.parentFile.resolve("stdlib/mini/memory"))
    // Declared as inputs so edits to test programs or the runtime re-run the test instead of hitting the cache.
    inputs.dir(rootProject.file("testdata"))
    inputs.dir(rootProject.projectDir.parentFile.resolve("runtime"))
    systemProperty("mini.backendSrc", rootProject.file("backend/src/main/kotlin").absolutePath)
    systemProperty("mini.selfhostSrc", rootProject.file("selfhost").absolutePath)
    inputs.dir(rootProject.file("selfhost"))
    inputs.dir(rootProject.file("backend/src/main/kotlin"))
    inputs.dir(rootProject.projectDir.parentFile.resolve("stdlib"))
    maxHeapSize = "2g"
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
        // Full assertion messages in CI logs (sanitizer reports are part of them).
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
