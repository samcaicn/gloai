/// background_service.dart — 前台/后台保活（跨端通道封装）。
///
/// 自查执行期间需要在前台常驻（显示通知 + 持有 PARTIAL_WAKE_LOCK），
/// 避免上传/推理途中进程被杀。Android 走 ForegroundService(Kotlin)，
/// 鸿蒙走 ForegroundAbility(ArkTS)，Dart 侧只调同一组方法。
library;

import 'package:flutter/services.dart';
import '../platform/channels.dart';

class BackgroundService {
  static const _ch = MethodChannel(Channels.background);

  /// 开始一次自查保活任务（启动前台服务）。
  static Future<void> start(String taskName) async {
    try {
      await _ch.invokeMethod('start', {'task': taskName});
    } on PlatformException catch (e) {
      // 通道未实现时静默降级为「打开即用」模式
      throw _Unsupported(e.message);
    }
  }

  /// 结束任务（停止前台服务，释放 WakeLock）。
  static Future<void> stop() async {
    try {
      await _ch.invokeMethod('stop');
    } on PlatformException {
      // 忽略
    }
  }
}

class _Unsupported implements Exception {
  final String? msg;
  _Unsupported(this.msg);
  @override
  String toString() => '保活服务未在该平台实现：$msg';
}
