plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "com.mtrinh.fobalarm.data"
    compileSdk = 36
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    api(project(":core"))
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)
    // TEST ONLY. `org.json` is provided by Android at runtime but is a stub in unit tests, so the
    // at-rest settings encoding -- the one whose silent field loss erases the password and the
    // role -- could not otherwise be round-tripped off a device. Same library Android ships.
    testImplementation("org.json:json:20231013")
    testImplementation(kotlin("test"))
}
