// 工程级 build.gradle.kts
//
// ⚠️ 注意：dev.flutter.flutter-plugin-loader 是 **Settings 插件**，只能在 settings.gradle.kts 的
// plugins {} 里声明。若在此处重复声明，Gradle 会报
// "DefaultProject_Decorated cannot be cast to Settings"。
// 所以这里只保留仓库配置，插件声明统一放 settings.gradle.kts。
allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
