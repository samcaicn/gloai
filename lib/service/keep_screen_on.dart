/// keep_screen_on.dart — 屏幕常亮通道封装。
///
/// 「不黑屏」分两层：
///  - 前台息屏（店长举手机看结果时灭屏）→ 用窗口 FLAG_KEEP_SCREEN_ON（仅自查/预览页开启）
///  - 后台被杀（见 background_service.dart）
/// 这里只管前台常亮；安卓用 WindowManager，鸿蒙用 window.setWindowKeepScreenOn。
library;

import 'package:flutter/services.dart';
import '../platform/channels.dart';

class KeepScreenOn {
  static const _ch = MethodChannel(Channels.keepScreenOn);

  /// 开启常亮（自查执行页 / 预览页 onResume 调）。
  static Future<void> enable() async {
    try {
      await _ch.invokeMethod('enable');
    } on PlatformException {
      // 降级：无原生时依赖 Flutter 默认行为
    }
  }

  /// 关闭常亮（离开页面 onPause 调）。
  static Future<void> disable() async {
    try {
      await _ch.invokeMethod('disable');
    } on PlatformException {
      // 忽略
    }
  }
}
