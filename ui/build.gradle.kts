plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "com.mtrinh.fobalarm.ui"
    compileSdk = 36
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions {
        // Robolectric needs the merged resources to inflate a theme.
        unitTests {
            isIncludeAndroidResources = true
            // Roborazzi's capture is a no-op unless recording is on. Set here rather than passed
            // on the command line, so `./gradlew test` writes the sheet and there is no second
            // way to invoke the suite.
            all {
                it.systemProperty("roborazzi.test.record", "true")
                // Images from renamed or deleted cases would otherwise linger and be published
                // under a fresh date, so the page could show screens that no longer exist.
                if ((System.getProperty("fobalarm.screens") ?: "on") != "off") {
                    it.doFirst { delete(layout.buildDirectory.dir("screens")) }
                }
                // Forwarded so `tools/mutation-check` can switch the capture off for its 65 runs.
                it.systemProperty("fobalarm.screens",
                    System.getProperty("fobalarm.screens") ?: "on")
            }
        }
    }
}

// The screen tests run on the DEBUG variant only. `ui-test-manifest`, which supplies the
// ComponentActivity a Compose test hosts its tree in, is published for debug builds -- so a
// release unit-test variant would compile and then fail for want of a manifest. The screens are
// byte-identical across variants, so there is nothing a release run would observe that debug does
// not. `./gradlew test` remains the one command; this says there is no second thing to run.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enableUnitTest = false }
}
dependencies {
    // :ui depends on StateClient + Snapshot and nothing else. It must never know
    // whether it is talking to a local or a remote alarm phone.
    api(project(":data"))
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime)
    implementation(libs.activity.compose)
    implementation(libs.activity.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.graphics)
    implementation(libs.compose.tooling)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.coroutines.android)

    // Screen tests: real Compose trees on the JVM. Probed before adoption -- renders in 15-35 s,
    // and a deliberately wrong assertion fails, so the assertions observe the tree.
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    // PROBE: screenshot capture off-device. Roborazzi rides Robolectric's native graphics.
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
    testImplementation(kotlin("test"))
}
