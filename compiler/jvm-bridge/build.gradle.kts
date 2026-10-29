plugins {
    kotlin("jvm")
    application
}

kotlin { jvmToolchain(21) }

val kotlinVersion: String by project

dependencies {
    implementation(project(":backend"))
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
    // Declared as inputs so edits to test programs or the runtime re-run the test instead of hitting the cache.
    inputs.dir(rootProject.file("testdata"))
    inputs.dir(rootProject.projectDir.parentFile.resolve("runtime/minirt"))
    maxHeapSize = "2g"
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
        // Full assertion messages in CI logs (sanitizer reports are part of them).
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
