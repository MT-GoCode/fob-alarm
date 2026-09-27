plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}
android {
    namespace = "com.mtrinh.fobalarm.service"
    compileSdk = 36
    defaultConfig {
        minSdk = 31
        ksp { arg("room.schemaLocation", "$projectDir/schemas") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    api(project(":core"))
    api(project(":data"))
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.concurrent.futures)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
}
