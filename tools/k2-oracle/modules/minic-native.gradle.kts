plugins {
    kotlin("jvm")
    application
}

kotlin { jvmToolchain(21) }

// A Mini project: the sources are src/*.kt (no src/main/kotlin, no package directories).
sourceSets.main { kotlin.setSrcDirs(listOf(file("src"))) }

// Native REPL input uses Kotlin/Native cinterop; the JVM oracle supplies an equivalent platform shim.
val jvmReplShim = File(rootProject.projectDir, "modules/jvm-shims/minic-native")
sourceSets.main {
    kotlin.exclude("NativeReplIO.kt")
    kotlin.srcDir(jvmReplShim)
}

// File access comes from the Mini stdlib (`mini.io`, stdlib/mini/io/Files.kt) which sits on `@CSymbol` intrinsics natively.
// For the JVM run those two pieces are compiled from the real sources instead.
val repoRoot = rootProject.projectDir.parentFile.parentFile
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
}

application {
    mainClass.set("mini.minicnative.MainKt")
    applicationName = "minic-native"
    applicationDefaultJvmArgs = listOf("-Xss512m", "-Xmx3g")
}
