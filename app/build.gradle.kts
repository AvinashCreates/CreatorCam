plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.avinash.creatorcam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.avinash.creatorcam"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        // Phones only: skip x86 native libs from ML Kit to keep the APK small.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    val camerax = "1.4.1"
    val media3 = "1.5.1"
    val lifecycle = "2.8.7"

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.lifecycle:lifecycle-runtime-compose:$lifecycle")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:$lifecycle")

    // Camera: only core/camera2/lifecycle. Recording uses our own GL + MediaCodec pipeline,
    // so camera-video / camera-view are intentionally NOT used.
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")

    // Bundled (offline) face detector.
    implementation("com.google.mlkit:face-detection:16.1.7")

    // Recorded-video review.
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    testImplementation("junit:junit:4.13.2")
}
