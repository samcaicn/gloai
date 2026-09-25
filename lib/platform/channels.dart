/// channels.dart — 平台通道（MethodChannel）常量定义。
/// Dart 侧统一通过这些通道调用原生能力；Android 走 Kotlin、鸿蒙走 ArkTS，
/// 两端实现同一套方法名，Dart 不关心底层。
library;

class Channels {
  /// 前台/后台保活服务：start / stop 一次自查保活任务。
  static const String background = 'com.facemark.camhub/background';

  /// 屏幕常亮：enable / disable（自查执行页与预览页开启）。
  static const String keepScreenOn = 'com.facemark.camhub/keepScreenOn';

  /// 厂商 ROM 电池白名单引导：openSettings(romName)。
  static const String batteryWhitelist =
      'com.facemark.camhub/batteryWhitelist';

  /// 忽略电池优化引导：requestIgnoreBatteryOptimizations()。
  static const String ignoreBattery = 'com.facemark.camhub/ignoreBattery';
}
