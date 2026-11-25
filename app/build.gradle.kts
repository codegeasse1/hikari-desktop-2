import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.javafx)
}

version = "1.0.0"

javafx {
    version = libs.versions.javafx.get()
    modules = listOf("javafx.controls", "javafx.media")
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlin.reflect)
    implementation(libs.okhttp)
    implementation(libs.rhino)
    implementation(libs.jsoup)
    implementation(libs.dex.tools)
    implementation(libs.asm)
    implementation(libs.asm.tree)
    implementation(libs.json.java)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
}

tasks.jar {
    archiveFileName.set("hikari.jar")
    manifest {
        attributes["Main-Class"] = "codegeasse.hikari.desktop.MainKt"
    }
}

/** Flat input dir for jpackage (see .github/workflows/build.yml). */
val jpackageInput by tasks.registering(Sync::class) {
    dependsOn(tasks.jar)
    from(configurations.runtimeClasspath)
    from(tasks.jar)
    into(layout.buildDirectory.dir("jpackage-input"))
}
