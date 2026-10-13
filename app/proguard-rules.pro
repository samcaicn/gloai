# WeAuto release 混淆规则（L1 反马甲包 / 提高改壳定位成本）
#
# 目标：混淆/压缩绝大部分代码，但保留运行时必须「名字不可变」或「被外部反射/JNI 调用」的类。
# R8 默认会移除认为不可达的类；以下规则保证关键路径不被误伤。

# ---- JNI 桥（Integrity）：类名 + 原生方法签名必须原样，否则 System.loadLibrary 后
#      JVM 按 Java_com_jev_probe_core_Integrity_nativeXxx 查找会 UnsatisfiedLinkError。
-keep class com.jev.probe.core.Integrity
-keepclassmembers class * {
    native <methods>;
}

# ---- 应用入口 / 组件（Manifest 通过 android:name 引用，类名变更会让系统找不到）
-keep public class com.jev.probe.App
-keep public class com.jev.probe.MainActivity
-keep public class com.jev.probe.SettingsActivity
-keep public class com.jev.probe.KnowledgeActivity
-keep public class com.jev.probe.CheckoutActivity
-keep public class com.google.android.accessibility.selecttospeak.SelectToSpeakService
-keep public class com.jev.probe.core.KeepAliveService
-keep public class com.jev.probe.core.WxNotificationListener

# ---- 资源/数据类（org.json 手动读写，无需反射，但保留以防被 shrink 误删）
-keep class com.jev.probe.core.BillingState { *; }

# ---- 保留注解与行号（崩溃栈仍可定位，方便 CI 排障）
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# ---- 第三方 native AAR 的告警压住（onnxruntime / opencv 已自带 keep，这里只防 warn 致死）
-dontwarn ai.onnxruntime.**
-dontwarn org.opencv.**
-dontwarn com.microsoft.**

# ---- 不优化掉枚举（部分 SDK 枚举序列化假设）
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
