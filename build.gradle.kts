import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
}

allprojects {
    group = "com.sistemadiagnosis"
    version = "0.1.0"
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> { jvmToolchain(17) }
        dependencies { "testImplementation"(kotlin("test")) }
        tasks.withType<Test>().configureEach { useJUnitPlatform() }
    }
}
