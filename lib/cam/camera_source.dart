/// camera_source.dart — 统一摄像头来源接口。
///
/// 上层（自查页）只调用 [captureFrame]，不关心图从「本地 ONVIF 直连」还是「云端代理」来。
/// 两条通道对上层暴露同一种签名。
library;

import 'dart:typed_data';
import '../data/models.dart';
import 'onvif_client.dart';
import 'snapshot_fetcher.dart';
import 'vendor_preset.dart';

/// 统一抓图接口。
abstract class CameraSource {
  Future<Uint8List> captureFrame(CameraDevice device);
}

/// 本地直连：手机 → 同 WiFi → IPC（ONVIF / 厂商私有）。
class LocalCameraSource implements CameraSource {
  @override
  Future<Uint8List> captureFrame(CameraDevice device) async {
    if (device.snapshotUrl != null && device.snapshotUrl!.isNotEmpty) {
      return SnapshotFetcher.fetch(
        device.snapshotUrl!,
        username: device.username,
        password: _decrypt(device.passwordEnc),
      );
    }
    if (device.ip == null) {
      throw Exception('本地设备缺少 IP 与抓图地址，无法直连');
    }
    // 走 ONVIF 发现抓图地址
    final client = OnvifClient(
      ip: device.ip!,
      port: device.port ?? 2020,
      username: device.username ?? 'admin',
      password: _decrypt(device.passwordEnc),
    );
    try {
      final profile = await client.getSnapshotUri();
      return SnapshotFetcher.fetch(
        profile.snapshotUri,
        username: device.username,
        password: _decrypt(device.passwordEnc),
      );
    } catch (_) {
      // 降级：厂商私有路径
      final uri = dahuaSnapshot(device.ip!, device.port ?? 80, 1);
      return SnapshotFetcher.fetch(uri);
    } finally {
      client.close();
    }
  }
}

/// 云端代理：手机 → Workers → camhub → 厂商云。
/// [cloudCapture] 由上层注入（调用 StoreRepository.captureCloudBytes）。
class CloudCameraSource implements CameraSource {
  final Future<Uint8List> Function(String deviceId) cloudCapture;
  CloudCameraSource(this.cloudCapture);

  @override
  Future<Uint8List> captureFrame(CameraDevice device) =>
      cloudCapture(device.id);
}

/// 密码解密（占位：真实用 Android Keystore / 鸿蒙 KeyStore 加密，Dart 侧仅存密文）。
/// 这里用可逆 base64 仅作演示，生产必须替换为平台安全存储，且不在 Dart 侧落地明文。
String _decrypt(String? enc) {
  if (enc == null || enc.isEmpty) return '';
  try {
    return String.fromCharCodes(_base64DecodeSafe(enc));
  } catch (_) {
    return '';
  }
}

List<int> _base64DecodeSafe(String s) {
  final padded = s.replaceAll('-', '+').replaceAll('_', '/');
  return _decodeBase64(padded);
}

List<int> _decodeBase64(String s) {
  // 极简 base64 解码（仅用于演示密文还原，生产应移除）
  const tbl = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  final buf = <int>[];
  for (var i = 0; i < s.length; i += 4) {
    final c0 = tbl.indexOf(s[i]);
    final c1 = tbl.indexOf(s[i + 1]);
    final c2 = s[i + 2] == '=' ? 0 : tbl.indexOf(s[i + 2]);
    final c3 = s[i + 3] == '=' ? 0 : tbl.indexOf(s[i + 3]);
    buf.add((c0 << 2) | (c1 >> 4));
    if (s[i + 2] != '=') buf.add(((c1 & 15) << 4) | (c2 >> 2));
    if (s[i + 3] != '=') buf.add(((c2 & 3) << 6) | c3);
  }
  return buf;
}
