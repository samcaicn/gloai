import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.tuptup.speech"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tuptup.speech"
        minSdk = 26              // Android 8.0+，覆盖 99% 设备；NNAPI 需要 27+ 可用
        targetSdk = 35
        // CI 用 -PciRun=<最终 versionCode> 注入，与 artifact 名 speech-apk-<versionCode> 同口径
        versionCode = (project.findProperty("ciRun") as? String)?.toIntOrNull() ?: 1
        versionName = "1.0"
        ndk {
            abiFilters += listOf("arm64-v8a")   // 安卓优化：只打 arm64，体积小、性能最佳
        }
    }

    // 固定 keystore 签名：CI 从 SIGNING_* 环境变量解开 secret，本地可用 keystore.properties。
    // 这样 install -r 能直接覆盖已装版本、不丢数据；没有签名材料时 release 保持未签名（仅本地编译检查）。
    signingConfigs {
        val envStore = System.getenv("SIGNING_STORE_FILE")
        val propsFile = rootProject.file("keystore.properties")
        if (!envStore.isNullOrBlank() || propsFile.exists()) {
            create("releaseKey") {
                if (!envStore.isNullOrBlank()) {
                    storeFile = file(envStore)
                    storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                    keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                    keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
                } else {
                    val p = Properties()
                    propsFile.inputStream().use { p.load(it) }
                    storeFile = rootProject.file(p.getProperty("storeFile"))
                    storePassword = p.getProperty("storePassword")
                    keyAlias = p.getProperty("keyAlias")
                    keyPassword = p.getProperty("keyPassword")
                }
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfigs.findByName("releaseKey")?.let { signingConfig = it }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // debug 也用同一把 key，便于覆盖安装调试版本
        getByName("debug") {
            signingConfigs.findByName("releaseKey")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        jniLibs { pickFirsts += listOf("**/libonnxruntime.so") }
    }
}

dependencies {
    // Compose
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Lifecycle / Coroutines
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Room（本地数据：店铺/产品/音色）
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // sherpa-onnx —— 端侧 ZipVoice 零样本声音克隆（本地 AAR，官方 v2.0.3 release）
    implementation(files("libs/sherpa-onnx-2.0.3.aar"))

    // 网络：OkHttp（OpenAI 兼容 API + 天气/热点）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
}
