rootProject.name = "minic"

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

include(":backend", ":jvm-bridge", ":memory-spec", ":native-driver", ":frontend", ":frontend-oracle", ":lower")
