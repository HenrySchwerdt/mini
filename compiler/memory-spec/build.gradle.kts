// Validates the memory-model spec corpus (docs/memory-model.md): every program under
// testdata/memory and the mini.memory sources must be valid Kotlin (real kotlinc, K2),
// and every accepted program must produce its expected output on the JVM.
plugins {
    kotlin("jvm")
}

kotlin { jvmToolchain(21) }

val kotlinVersion: String by project

dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("mini.memoryCorpus", rootProject.file("testdata/memory").absolutePath)
    // Declared as inputs so edits to the corpus or the API re-run the test instead of hitting the cache.
    inputs.dir(rootProject.file("testdata/memory"))
    inputs.dir(rootProject.projectDir.parentFile.resolve("stdlib"))
    // only the mini.memory API: the rest of stdlib is the Mini stdlib (package kotlin.*, docs/stdlib.md) and cannot be compiled against the JVM stdlib
    systemProperty("mini.memoryApi", rootProject.projectDir.parentFile.resolve("stdlib/mini/memory").absolutePath)
    // regenerate expected outputs: gradle -p compiler :memory-spec:test -Pregen
    systemProperty("mini.regen", (project.findProperty("regen") != null).toString())
    maxHeapSize = "2g"
    testLogging {
        events("failed")
        showStandardStreams = false
    }
}
