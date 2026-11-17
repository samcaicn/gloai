plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aizhibo.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aizhibo.app"
        minSdk = 26
        targetSdk = 35
        // CI 通过 -PciRun=<run_number> 注入构建序号，便于热更新比对；本地回落为 1。
        versionCode = (project.findProperty("ciRun") as? String)?.toIntOrNull() ?: 1
        versionName = "1.0.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
