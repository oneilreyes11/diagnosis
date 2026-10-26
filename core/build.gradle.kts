plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(17)

    jvm()

    // Web (PWA). Los tests comunes se ejecutan también en Node.
    js {
        nodejs()
    }

    // Las apps iOS enlazan el framework DiagnosisCore. Solo se compila en macOS.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "DiagnosisCore"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
