// Root project: shared configuration only. The Kotlin version is pinned in
// gradle.properties and in the plugin declaration below (docs/plan-kotlin.md, section 12).
plugins {
    kotlin("jvm") version "2.4.21" apply false
}

allprojects {
    group = "mini"
    version = "0.0.1"
}
