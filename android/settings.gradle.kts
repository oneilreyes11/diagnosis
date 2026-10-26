// Build Android separado: requiere Android SDK (sdk.dir en local.properties o ANDROID_HOME).
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "diagnosis-android"

// Sustituye com.sistemadiagnosis:core por el módulo del build principal.
includeBuild("..")
include(":transport-android")
