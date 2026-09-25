/// battery_whitelist.dart — 厂商 ROM 电池白名单引导。
///
/// 国产 ROM（小米/华为/OPPO/vivo/荣耀/三星/一加）会杀后台，保活的第一抓手是
/// 引导用户把本应用加入「自启动 / 省电策略白名单」。各 ROM 的跳转 intent 不同，
/// 由原生侧实现跳转；Dart 侧只负责触发 + 展示图文教程。
library;

import 'package:flutter/services.dart';
import '../platform/channels.dart';

class BatteryWhitelist {
  static const _ch = MethodChannel(Channels.batteryWhitelist);

  /// 跳转到当前 ROM 的电池优化/自启动设置页。
  static Future<bool> openSettings() async {
    try {
      final ok = await _ch.invokeMethod<bool>('openSettings');
      return ok ?? false;
    } on PlatformException {
      return false;
    }
  }

  /// 申请忽略电池优化（Android ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS）。
  static Future<bool> requestIgnore() async {
    try {
      final ok = await const MethodChannel(Channels.ignoreBattery)
          .invokeMethod<bool>('request');
      return ok ?? false;
    } on PlatformException {
      return false;
    }
  }

  /// 常见 ROM 的引导文案（离线可用，无需联网）。
  static const Map<String, String> guide = {
    'xiaomi': '小米：设置 → 应用设置 → 授权管理 → 自启动管理 → 允许本应用自启动；电池与性能 → 锁屏清理 → 不清理',
    'huawei': '华为/鸿蒙：手机管家 → 应用启动管理 → 本应用 → 手动管理（允许自启动/关联启动/后台活动）',
    'oppo': 'OPPO/一加：设置 → 电池 → 应用耗电管理 → 本应用 → 允许后台运行/允许自启动',
    'vivo': 'vivo：i管家 → 应用管理 → 自启动 → 允许；后台高耗电 → 加入白名单',
    'honor': '荣耀：手机管家 → 应用启动管理 → 本应用 → 手动管理（三项全开）',
    'samsung': '三星：设置 → 电池 → 后台使用限制 → 本应用 → 不限制',
  };

  static String? guideFor(String rom) => guide[rom];
}
