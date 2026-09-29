plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kafkasl.phonewhisper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kafkasl.phonewhisper"
        minSdk = 30
        targetSdk = 34
        versionCode = 2
        versionName = "0.3.0"

        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    @Suppress("DEPRECATION")
    kotlinOptions { jvmTarget = "17" }

    testOptions { unitTests { isIncludeAndroidResources = true } }

    packaging {
        // sherpa-onnx's C/C++ API libs aren't used by the JNI bindings.
        jniLibs.excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
        // Compress native libs and dex so the APK is small enough to sideload/share easily.
        jniLibs.useLegacyPackaging = true
        dex.useLegacyPackaging = true
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("androidx.work:work-runtime:2.9.1")
    // sherpa-onnx: Kotlin API + native libs for on-device speech recognition
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
}
