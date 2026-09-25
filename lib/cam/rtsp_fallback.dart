/// rtsp_fallback.dart — RTSP 取帧兜底（P1）。
///
/// 仅用于「不支持 snapshot 下载」的设备（如 TP-Link Tapo）。RTSP 解码需要原生播放器/解码器，
/// Dart 不擅长，故走平台通道：原生侧拉 RTSP → 取当前帧 → 回传 JPEG 字节。
/// 安卓/鸿蒙各自在原生实现 `com.facemark.camhub/rtsp` 通道的 `grabFrame`。
library;

import 'dart:typed_data';
import 'package:flutter/services.dart';
import 'models.dart';
import '../platform/channels.dart';

/// 通过原生通道从 RTSP 取一帧 JPEG。MVP 阶段为占位，未接原生时抛 UnsupportedError。
Future<Uint8List> grabRtspFrame(CameraDevice device) async {
  if (device.ip == null) throw UnsupportedError('RTSP 兜底需要设备 IP');
  try {
    final bytes = await const MethodChannel(Channels.keepScreenOn)
        .invokeMethod<Uint8List>('grabRtspFrame', {
      'ip': device.ip,
      'port': device.port ?? 554,
      'username': device.username,
      'password': device.passwordEnc, // 占位：真实应走原生安全存储
    });
    if (bytes == null) throw UnsupportedError('原生未实现 grabRtspFrame');
    return bytes;
  } on PlatformException catch (e) {
    throw UnsupportedError('RTSP 兜底未实现（${e.message}）—— 该设备建议换支持 snapshot 的摄像头或加装边缘盒子');
  }
}
