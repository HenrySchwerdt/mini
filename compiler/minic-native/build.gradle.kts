plugins {
    kotlin("jvm")
    application
}

kotlin { jvmToolchain(21) }

// Bootstrap stage B2-3 (docs/plan-kotlin.md section 10b): `minic-native`, the compiler without K2. Our own frontend
// (parser, semantic analysis), the lowering to MIR and the C backend behind one driver. Its main sources are pure Kotlin
// (MinicNativePurityTest) and get compiled by minic itself; on the JVM it runs like any Gradle application.
//
// File access comes from the Mini stdlib (`mini.io`, stdlib/mini/io/Files.kt) which sits on `@CSymbol` intrinsics
// natively. For the JVM run those two pieces are compiled from the real sources instead (as in native-driver).
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
    implementation(project(":lower"))
    implementation(project(":frontend"))
    implementation(project(":backend"))
    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("mini.minicnative.MainKt")
    applicationName = "minic-native"
    applicationDefaultJvmArgs = listOf("-Xss512m", "-Xmx3g")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("mini.driverMain", file("src/main").absolutePath)
    systemProperty("mini.driverBuildFile", file("build.gradle.kts").absolutePath)
    testLogging { events("passed", "failed", "skipped") }
}
