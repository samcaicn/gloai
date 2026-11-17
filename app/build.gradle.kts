import java.io.File
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 正式包签名（与 weauto / jev-chat-jarvis 同一套 keystore，证书 SHA1 0E:66:...:20:55）。
// 来源优先级：
//   1) CI：secrets.SIGNING_KEY(base64 keystore.p12) 解码到 $SIGNING_STORE_FILE，
//      alias/口令在 SIGNING_KEY_ALIAS / SIGNING_KEY_PASSWORD / SIGNING_STORE_PASSWORD。
//   2) 本地：AIZhibo_KEYSTORE_PROPS 指向的 .properties（默认 ../keys/release.properties）。
// 两者都没有时 release 不签名——CI 的 apksigner verify 步骤会兜底失败，防止未签名包流出。
val signStoreFile = System.getenv("SIGNING_STORE_FILE")?.takeIf { File(it).exists() }
val signAlias = System.getenv("SIGNING_KEY_ALIAS")
val signKeyPass = System.getenv("SIGNING_KEY_PASSWORD")
val signStorePass = System.getenv("SIGNING_STORE_PASSWORD")

val releaseProps = Properties().apply {
    val f = File(
        System.getenv("AIZhibo_KEYSTORE_PROPS")
            ?: File(rootProject.projectDir.parentFile, "keys/release.properties").absolutePath
    )
    if (f.exists()) FileInputStream(f).use { load(it) }
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
        versionName = "1.1.0"
    }

    signingConfigs {
        if (signStoreFile != null && !signAlias.isNullOrBlank()
            && !signKeyPass.isNullOrBlank() && !signStorePass.isNullOrBlank()
        ) {
            create("release") {
                storeFile = File(signStoreFile)
                storePassword = signStorePass
                keyAlias = signAlias
                keyPassword = signKeyPass
                // openssl 生成的 p12，pin 住类型避免 AGP 误判格式拒密码。
                storeType = "PKCS12"
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        } else if (releaseProps.isNotEmpty()) {
            create("release") {
                storeFile = File(releaseProps.getProperty("storeFile"))
                storePassword = releaseProps.getProperty("storePassword")
                keyAlias = releaseProps.getProperty("keyAlias")
                keyPassword = releaseProps.getProperty("keyPassword")
                storeType = releaseProps.getProperty("storeType", "PKCS12")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    // 只保留 release：交付给用户的正式包，去掉 debug 构建类型。
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            signingConfig = signingConfigs.findByName("release")
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
