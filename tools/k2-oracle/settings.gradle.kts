// The K2 oracle: the cross-checks against the official Kotlin compiler on the JVM. Everything here is optional tooling; the
// main build (compiler/, mini.toml, scripts/) never needs a JVM. See README.md.
rootProject.name = "k2-oracle"

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo1.maven.org/maven2") // Maven Central (same content as mavenCentral())
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://repo1.maven.org/maven2") // Maven Central (same content as mavenCentral())
        mavenCentral()
    }
}

// The pure Mini packages are compiled for the JVM straight from their sources in compiler/<name>/src (a flat Mini project),
// with the build scripts of modules/ (their own Gradle files are not part of the Mini projects).
for (m in listOf("backend", "frontend", "lower", "minic-native", "native-driver")) {
    include(":$m")
    project(":$m").projectDir = file("../../compiler/$m")
    project(":$m").buildFileName = "../../tools/k2-oracle/modules/$m.gradle.kts"
}
include(":jvm-bridge", ":frontend-oracle", ":memory-spec")
