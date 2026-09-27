import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.mtrinh.fobalarm"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mtrinh.fobalarm"
        minSdk = 31
        // Do NOT raise targetSdk past 36 without re-running the Phase-3 transport spike:
        // Local Network Protection is triggered by targetSdk 37, not by an OTA.
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file("keystore/fobalarm.jks")
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    // Two variants, same applicationId so they replace each other and cannot coexist.
    // The update channel and debug screen are COMPILED OUT of live: a shipping alarm
    // that polls a LAN host for executables is a real hole. SPEC.md section 13.
    flavorDimensions += "channel"
    productFlavors {
        create("dev") {
            dimension = "channel"
            buildConfigField("boolean", "DEV_CHANNEL", "true")
            buildConfigField("String", "VARIANT", "\"DEV\"")
        }
        create("live") {
            dimension = "channel"
            buildConfigField("boolean", "DEV_CHANNEL", "false")
            buildConfigField("String", "VARIANT", "\"LIVE\"")
        }
    }

    buildTypes {
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("release") }
        debug { signingConfig = signingConfigs.getByName("release") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":service"))
    implementation(project(":ui"))
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.graphics)
    implementation(libs.compose.tooling)
    implementation(libs.compose.material3)
    implementation(libs.coroutines.android)
}
