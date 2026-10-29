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
    maxHeapSize = "2g"
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}
