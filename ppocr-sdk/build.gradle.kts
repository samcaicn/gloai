plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.paddle.ocr"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("proguard-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Match the app module's Kotlin toolchain (root pins 1.9.24). The SDK
    // source is plain Kotlin 1.9-compatible; avoid the compilerOptions DSL.
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // On-device OCR. ONNX Runtime ships 16 KB-page-aligned arm64 .so, so it
    // loads fine on Android 15+ (our targetSdk is 35).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.21.1")
    // Official OpenCV Android AAR (Maven Central). quickbirdstudios:opencv:4.5.3
    // is built with an old NDK and dies on modern Android:
    // dlopen fails "cannot locate symbol __sfp_handle_exceptions".
    implementation("org.opencv:opencv:4.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.15.0")
}
