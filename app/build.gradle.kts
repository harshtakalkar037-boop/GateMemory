plugins {
    id("com.android.application")
}

android {
    namespace = "com.gatememory"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gatememory"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        noCompress += "bin"
    }
}

dependencies {
    val cameraXVersion = "1.6.1"

    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")
    implementation("org.opencv:opencv:5.0.0.1")
    implementation("dev.ffmpegkit-maintained:whisper-android:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
