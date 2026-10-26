plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":transport-serial"))
}

application {
    mainClass = "com.sistemadiagnosis.cli.MainKt"
    applicationName = "diag"
}
