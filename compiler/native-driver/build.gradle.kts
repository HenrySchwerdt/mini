plugins {
    kotlin("jvm")
    application
}

kotlin { jvmToolchain(21) }

// Bootstrap stage B1 (docs/plan-kotlin.md section 10b): the driver of the native `minic-backend`.
// It reads a MIR text file written by `minic emit-mir` and writes the C file. Its main sources are
// pure Kotlin (PurityTest) and get compiled by minic itself; on the JVM it runs like any Gradle application.
//
// File access comes from the Mini stdlib (`mini.io`, stdlib/mini/io/Files.kt) which sits on `@CSymbol`
// intrinsics natively. For the JVM run those two pieces are compiled from the real sources instead:
// Files.kt itself plus the JVM implementation of the intrinsics from stdlib-test.
val repoRoot = rootProject.projectDir.parentFile
val jvmShim = layout.buildDirectory.dir("generated/jvm-shim")

val copyJvmShim by tasks.registering(Copy::class) {
    from(File(repoRoot, "stdlib/mini/io/Files.kt")) { into("mini/io") }
    from(File(repoRoot, "stdlib-test/jvm/mini/internal/B1Intrinsics.kt")) { into("mini/internal") }
    into(jvmShim)
}

sourceSets.main { kotlin.srcDir(jvmShim) }
tasks.named("compileKotlin") { dependsOn(copyJvmShim) }

dependencies {
    implementation(project(":backend"))
    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("mini.nativedriver.NativeDriverKt")
    applicationName = "native-driver"
}

tasks.test {
    useJUnitPlatform()
    systemProperty("mini.driverMain", file("src/main").absolutePath)
    systemProperty("mini.driverBuildFile", file("build.gradle.kts").absolutePath)
    testLogging { events("passed", "failed", "skipped") }
}
