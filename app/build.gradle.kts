plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.baselineprofile)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// TrashTalk ships in two modes. Without a Firebase config the app runs on a
// seeded local backend so it is explorable straight after install; drop a real
// google-services.json into app/ and the Google Services plugin kicks in,
// FirebaseApp initialises, and the repository switches to live Firestore.
// See docs/FIREBASE_SETUP.md.
val hasFirebaseConfig = file("google-services.json").exists()
if (hasFirebaseConfig) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.trashtalk"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.trashtalk"
        minSdk = 26
        targetSdk = 36
        ndk {
            // The MapLibre renderer core publishes these three and no more.
            // Without this filter AGP would still package lib/x86/ for the
            // other native deps, so a 32-bit x86 device would install happily
            // and then die on the first map frame with UnsatisfiedLinkError.
            abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        versionCode = 1
        versionName = "1.0"
        vectorDrawables.useSupportLibrary = true
        buildConfigField("boolean", "FIREBASE_CONFIGURED", hasFirebaseConfig.toString())
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            // Material 3's bottom sheets and a few layout APIs are still marked
            // experimental; opting in once here beats annotating every call site.
            freeCompilerArgs.addAll(
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            )
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
        // The PMTiles archive is already gzip-internally; deflating it again in
        // the APK buys nothing and costs a decompress on every read.
        jniLibs.useLegacyPackaging = false
    }
    androidResources {
        noCompress += "pmtiles"
    }
    bundle {
        // The renderer core is 9-14 MB per ABI and the basemap package is
        // another 1.8 MB. Splitting means a phone downloads one ABI's worth
        // rather than all three, which is the difference between a ~37 MB and
        // a ~62 MB install.
        abi { enableSplit = true }
        density { enableSplit = true }
        language {
            // maplibre-compose ships UI strings in 130-odd locales. Leaving
            // these unsplit would hand every device all of them.
            enableSplit = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.storage)

    // Phase 2 spike: evaluating Path A (maplibre-compose) against Path B
    // (MapLibre Android SDK in an AndroidView).
    implementation(libs.maplibre.compose)
    implementation(libs.maplibre.renderer.opengl)
    implementation(libs.androidx.metrics.jankstats)
    baselineProfile(project(":benchmark"))

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
}
