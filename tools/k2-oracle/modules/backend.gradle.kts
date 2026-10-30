plugins {
    kotlin("jvm")
}

kotlin { jvmToolchain(21) }

// A Mini project: the sources are src/*.kt (no src/main/kotlin, no package directories).
sourceSets.main { kotlin.setSrcDirs(listOf(file("src"))) }
