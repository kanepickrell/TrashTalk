// Macrobenchmark and Baseline Profile generation.
//
// Separate module because these run *against* an installed release build
// rather than inside it: the numbers are only meaningful on the build users
// actually get, with R8 on and the profile applied.
//
//   ./gradlew :benchmark:connectedBenchmarkAndroidTest   -- measure
//   ./gradlew :app:generateBaselineProfile               -- regenerate the profile
plugins {
    // No version: AGP 9 already has com.android.test on the build classpath,
    // and asking for it by version fails resolution rather than matching.
    id("com.android.test")
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.trashtalk.benchmark"
    compileSdk = 37

    defaultConfig {
        // Macrobenchmark needs 24+; the app's own floor is 26.
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
}

baselineProfile {
    // The profile is checked in and applied at build time, so an ordinary
    // build never needs a connected device.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
