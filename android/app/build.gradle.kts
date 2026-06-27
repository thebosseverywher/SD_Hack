plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.flow.app"
    // Snapdragon 8 Elite target: Android 14/15. compileSdk 35, minSdk 31.
    compileSdk = 35

    defaultConfig {
        applicationId = "com.flow.app"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ARM64 only — Snapdragon 8 Elite. QNN / ONNX Runtime native libs are arm64-v8a.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // Minify enabled so R8 shrinks/optimizes the release APK (spec §7.1). This is
            // what makes the kotlinx.serialization keep-rules in proguard-rules.pro live —
            // without it R8 could strip/rename the generated serializer members the wire
            // protocol depends on.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // No signingConfig here on purpose: assembleRelease produces an UNSIGNED APK.
            // Add `signingConfigs { create("release") { … } }` referencing your keystore and
            // set `signingConfig = signingConfigs.getByName("release")` before shipping
            // (see android/README.md §Release APK).
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        // Compatible with Kotlin 1.9.24.
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // ONNX Runtime / QNN ship multiple native libs; keep them uncompressed for mmap.
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // ---- Jetpack Compose (BOM keeps all compose artifacts on one version) ----
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ---- Activity / lifecycle ----
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // ---- Background work (Trove backfill, indexing) ----
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // ---- Networking: OkHttp WebSocket (federation transport) ----
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ---- Serialization (wire protocol; field names must match shared/protocol.md) ----
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // ---- Secure storage of pairing PSK / peer records ----
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // AEAD for the federation channel (XChaCha20-Poly1305 per shared/config.json) is
    // provided by Tink, which security-crypto depends on. We use it directly in Federation.kt.
    implementation("com.google.crypto.tink:tink-android:1.13.0")

    // ---- CameraX + ML Kit barcode (QR pairing scan) ----
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // ====================================================================
    // INTEGRATION POINTS (commented; enable when the model/runtime is wired)
    // ====================================================================
    // On-device inference (embeddings / OCR / LLM) on the Snapdragon NPU via the
    // QNN Execution Provider. See Inference.kt for the EP-switching stubs.
    //   implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
    // For the QNN (HTP/NPU) EP you typically need a QNN-enabled ORT build; either:
    //   - a vendored .aar under app/libs (add flatDir in settings.gradle.kts), or
    //   - the Qualcomm AI Hub / QNN SDK delegate libraries.
    //
    // Local vector store. Pick ONE; Index.kt ships an in-memory cosine fallback so
    // the skeleton is coherent without either of these present:
    //   ObjectBox (vector search via HNSW):
    //     // apply plugin "io.objectbox" and add objectbox-android + objectbox-kotlin
    //   sqlite-vec (vec0 virtual tables, matches the desktop engine's schema):
    //     implementation("androidx.sqlite:sqlite-framework:2.4.0")
    //     // load the sqlite-vec extension .so at runtime
    // ====================================================================

    // ---- Tests ----
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
