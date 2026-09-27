plugins {
    alias(libs.plugins.kotlin.jvm)
}
// :core is pure Kotlin/JVM with ZERO Android dependencies. That is the point: the entire
// state machine runs in unit tests on the Mac in seconds, with no phone and no 04:00.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
dependencies {
    testImplementation(kotlin("test"))
}
tasks.test { useJUnitPlatform() }
