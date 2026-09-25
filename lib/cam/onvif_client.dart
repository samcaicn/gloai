/// onvif_client.dart — 纯 Dart ONVIF 客户端。
///
/// 只用到 4 个接口（足够抓一帧）：
///   GetCapabilities → 拿 Media 服务地址
///   GetProfiles    → 拿第一个 ProfileToken
///   GetSnapshotUri → 拿抓图地址（通常带鉴权参数）
///   GetDeviceInformation → 拿设备名
///
/// 全程 HTTP Digest（见 digest_auth.dart），不依赖任何原生代码，Android/鸿蒙共用。
library;

import 'dart:convert';
import 'package:http/http.dart' as http;
import 'package:xml/xml.dart';
import 'digest_auth.dart';

/// 候选的设备服务路径（不同厂商/固件略有差异）。
const List<String> _devicePaths = [
  '/onvif/device_service',
  '/onvif/device',
  '/cgi-bin/onvif/device_service',
  '/ISAPI/onvif/device_service',
];

class OnvifException implements Exception {
  final String message;
  OnvifException(this.message);
  @override
  String toString() => 'OnvifException: $message';
}

class OnvifClient {
  final String ip;
  final int port;
  final String username;
  final String password;
  final http.Client _client;

  OnvifClient({
    required this.ip,
    required this.port,
    required this.username,
    required this.password,
  }) : _client = DigestClient(username, password);

  String get _base => 'http://$ip:$port';

  /// 探测可用的设备服务路径。
  Future<String> _probeDeviceEndpoint() async {
    for (final p in _devicePaths) {
      try {
        final res = await _soap('$p', _capabilitiesEnvelope());
        if (res.statusCode == 200) return p;
      } catch (_) {}
    }
    throw OnvifException('找不到 ONVIF 设备服务（路径探测失败）');
  }

  Future<http.Response> _soap(String path, String envelope) async {
    final url = '$path'.startsWith('http') ? path : '$_base$path';
    return _client.post(
      Uri.parse(url),
      headers: {
        'content-type': 'application/soap+xml; charset=utf-8',
      },
      body: envelope,
    );
  }

  /// 返回 { mediaXAddr, deviceInfo:{manufacturer,model,name} }。
  Future<OnvifProbe> probe() async {
    final path = await _probeDeviceEndpoint();
    final capRes = await _soap(path, _capabilitiesEnvelope());
    final cap = XmlDocument.parse(utf8.decode(capRes.bodyBytes));
    final mediaXAddr = _firstText(cap, 'Media');
    if (mediaXAddr == null) {
      throw OnvifException('GetCapabilities 未返回 Media 服务地址');
    }
    return OnvifProbe(devicePath: path, mediaXAddr: mediaXAddr);
  }

  /// 拿第一个 Profile 与抓图地址。
  Future<OnvifProfile> getSnapshotUri() async {
    final probe = await this.probe();
    final profRes = await _soap(probe.mediaXAddr, _profilesEnvelope());
    final profDoc = XmlDocument.parse(utf8.decode(profRes.bodyBytes));
    final token = _firstAttr(profDoc, 'Profile', 'token');
    if (token == null) throw OnvifException('GetProfiles 未返回 Profile');
    final snapRes =
        await _soap(probe.mediaXAddr, _snapshotEnvelope(token));
    final snapDoc = XmlDocument.parse(utf8.decode(snapRes.bodyBytes));
    final uri = _firstText(snapDoc, 'Uri');
    if (uri == null) throw OnvifException('GetSnapshotUri 未返回 Uri');
    return OnvifProfile(
      mediaXAddr: probe.mediaXAddr,
      profileToken: token,
      snapshotUri: uri,
    );
  }

  /// 设备名（用于自动命名摄像头）。
  Future<String?> deviceName() async {
    try {
      final probe = await this.probe();
      final res = await _soap(probe.devicePath, _deviceInfoEnvelope());
      final doc = XmlDocument.parse(utf8.decode(res.bodyBytes));
      return _firstText(doc, 'Model') ?? _firstText(doc, 'FirmwareVersion');
    } catch (_) {
      return null;
    }
  }

  void close() => _client.close();

  // —— 工具 ——
  String? _firstText(XmlDocument doc, String tag) {
    final node = doc.findAllElements(tag).firstOrNull;
    return node?.innerText.trim();
  }

  String? _firstAttr(XmlDocument doc, String tag, String attr) {
    final node = doc.findAllElements(tag).firstOrNull;
    return node?.getAttribute(attr);
  }

  // —— SOAP 信封 ——
  String _capabilitiesEnvelope() => _wrap('''
    <tds:GetCapabilities xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
      <tds:Category>Media</tds:Category>
    </tds:GetCapabilities>''');

  String _profilesEnvelope() => _wrap('''
    <trt:GetProfiles xmlns:trt="http://www.onvif.org/ver10/media/wsdl"/>''');

  String _snapshotEnvelope(String token) => _wrap('''
    <trt:GetSnapshotUri xmlns:trt="http://www.onvif.org/ver10/media/wsdl">
      <trt:ProfileToken>$token</trt:ProfileToken>
    </trt:GetSnapshotUri>''');

  String _deviceInfoEnvelope() => _wrap('''
    <tds:GetDeviceInformation xmlns:tds="http://www.onvif.org/ver10/device/wsdl"/>''');

  String _wrap(String body) => '''<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope">
  <soap:Body>$body</soap:Body>
</soap:Envelope>''';
}

class OnvifProbe {
  final String devicePath;
  final String mediaXAddr;
  OnvifProbe({required this.devicePath, required this.mediaXAddr});
}

class OnvifProfile {
  final String mediaXAddr;
  final String profileToken;
  final String snapshotUri;
  OnvifProfile({
    required this.mediaXAddr,
    required this.profileToken,
    required this.snapshotUri,
  });
}
