pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "diagnosis-system"

include(":core", ":transport-serial", ":cli", ":web")
// El módulo Android es un build aparte (android/) porque requiere el Android SDK.
