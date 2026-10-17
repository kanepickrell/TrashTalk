import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.baselineprofile)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
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
    room {
        // Checked in, so a schema change shows up in review as a diff rather
        // than as a crash on somebody's phone after an update.
        schemaDirectory("$projectDir/schemas")
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

    implementation(libs.maplibre.compose)
    implementation(libs.maplibre.renderer.opengl)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.metrics.jankstats)
    baselineProfile(project(":benchmark"))

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
}

// ---------------------------------------------------------------- size gate
//
// The renderer and the bundled basemap put real weight in the download, and
// weight is the kind of thing that creeps a megabyte at a time until somebody
// on a cheap phone and a metered connection gives up installing. This fails
// the build rather than letting that happen quietly.
//
// The limit is on what a device actually downloads -- the base module plus one
// ABI's native libraries -- not on the universal artifact, which no user ever
// receives.
val maxDownloadMegabytes = 22.0

tasks.register("checkDownloadSize") {
    group = "verification"
    description = "Fails if any single-ABI download exceeds the size budget."

    val bundle = layout.buildDirectory.file("outputs/bundle/release/app-release.aab")
    inputs.file(bundle)

    // Captured into locals so the action closes over plain values rather than
    // over the build script, which the configuration cache cannot serialise.
    val limitMb = maxDownloadMegabytes

    doLast {
        val aab = bundle.get().asFile
        if (!aab.exists()) {
            throw GradleException("No bundle at $aab - run :app:bundleRelease first")
        }

        var shared = 0L
        val perAbi = mutableMapOf<String, Long>()
        ZipFile(aab).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val name = entry.name
                if (name.contains("/lib/")) {
                    val abi = name.substringAfter("/lib/").substringBefore('/')
                    perAbi[abi] = (perAbi[abi] ?: 0L) + entry.compressedSize
                } else {
                    shared += entry.compressedSize
                }
            }
        }

        val offenders = mutableListOf<String>()
        perAbi.toSortedMap().forEach { (abi, nativeBytes) ->
            val megabytes = (shared + nativeBytes) / 1_000_000.0
            val verdict = if (megabytes > limitMb) "OVER" else "ok"
            logger.lifecycle("  %-12s %5.1f MB  %s".format(abi, megabytes, verdict))
            if (megabytes > limitMb) {
                offenders += "%s at %.1f MB".format(abi, megabytes)
            }
        }

        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Download size budget is $limitMb MB; over on: " +
                    offenders.joinToString(", "),
            )
        }
    }
}
