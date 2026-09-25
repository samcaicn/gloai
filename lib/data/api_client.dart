/// api_client.dart — 与服务端 camhub（Cloudflare Workers）对接。
///
/// 鉴权模型：
///   1. 店长用 store 账号登录 → 拿到长期 token（存在本地）
///   2. 调用 cam.* 接口时，部分需要「服务端临时 ticket」——由 Workers 用长期 token 签发，
///     手机只拿 2 小时 JWT，绝不持有厂商云 appId/appSecret。
///
/// 统一错误：抛 [ApiException]，含 code / message。
library;

import 'dart:convert';
import 'package:http/http.dart' as http;
import '../config/env.dart';
import 'models.dart';

class ApiException implements Exception {
  final String code;
  final String message;
  ApiException(this.code, this.message);
  @override
  String toString() => '[$code] $message';
}

class ApiClient {
  final String base;
  String? _token;

  ApiClient({this.base = Env.apiBase});

  void setToken(String? t) => _token = t;
  String? get token => _token;

  Future<dynamic> _post(String action, Map<String, dynamic> body) async {
    final uri = Uri.parse('$base/api?action=$action');
    final res = await http.post(
      uri,
      headers: {
        'content-type': 'application/json',
        if (_token != null) 'authorization': 'Bearer $_token',
      },
      body: jsonEncode(body),
    );
    final Map<String, dynamic> data;
    try {
      data = jsonDecode(utf8.decode(res.bodyBytes));
    } catch (_) {
      throw ApiException('BAD_RESPONSE', '服务端返回非 JSON：HTTP ${res.statusCode}');
    }
    if (data['ok'] != true) {
      throw ApiException(
        data['code']?.toString() ?? 'ERR',
        data['error']?.toString() ?? '未知错误',
      );
    }
    return data['data'];
  }

  /// 店长登录（复用 facemark 现有账号体系，store 角色）。
  Future<Map<String, dynamic>> login(String user, String pwd) async {
    final data = await _post('auth.login', {'user': user, 'pwd': pwd});
    _token = data['token'];
    return data;
  }

  /// 拉取当前店长可管理的门店列表。
  Future<List<Store>> listStores() async {
    final data = await _post('store.list', {});
    return (data['stores'] as List).map((e) => Store.fromJson(e)).toList();
  }

  /// 列出已绑定到本门店的云端摄像头（走 camhub 厂商云代理通道）。
  Future<List<CameraDevice>> listCloudDevices(String storeId) async {
    final data = await _post('cam.devices', {'storeId': storeId});
    final list = data['devices'] as List;
    return list.map((e) => CameraDevice(
          id: e['id'],
          source: CameraSourceKind.cloudProxy,
          provider: e['provider'],
          name: e['name'] ?? e['id'],
          online: e['online'] ?? false,
        )).toList();
  }

  /// 让服务端抓一帧（云端代理通道），返回短期图片 URL。
  Future<String> captureCloud(String storeId, String deviceId) async {
    final data = await _post('cam.capture', {
      'storeId': storeId,
      'deviceId': deviceId,
    });
    return data['url'];
  }

  /// 上传一帧 JPEG 字节做云端推理，返回自查结果。
  Future<InspectResult> inspectBytes(
    String storeId,
    String deviceId,
    String deviceName,
    List<int> bytes,
  ) async {
    final uri = Uri.parse('$base/api?action=cam.inspect');
    final req = http.MultipartRequest('POST', uri)
      ..headers['authorization'] = 'Bearer $_token'
      ..fields['storeId'] = storeId
      ..fields['deviceId'] = deviceId
      ..fields['deviceName'] = deviceName
      ..files.add(http.MultipartFile.fromBytes(
        'image',
        bytes,
        filename: 'snapshot.jpg',
      ));
    final res = await http.Response.fromStream(await req.send());
    final data = jsonDecode(utf8.decode(res.bodyBytes));
    if (data['ok'] != true) {
      throw ApiException(
        data['code']?.toString() ?? 'ERR',
        data['error']?.toString() ?? '推理失败',
      );
    }
    return InspectResult.fromJson(data['data']);
  }

  /// 拉取历史自查报告列表。
  Future<List<InspectResult>> listReports(String storeId) async {
    final data = await _post('cam.reports', {'storeId': storeId});
    return (data['reports'] as List)
        .map((e) => InspectResult.fromJson(e))
        .toList();
  }

  /// 下载图片字节（带鉴权头）。用于云端代理返回的短期图片 URL。
  Future<List<int>> getBytes(String url) async {
    final res = await http.get(
      Uri.parse(url),
      headers: _token != null ? {'authorization': 'Bearer $_token'} : null,
    );
    if (res.statusCode != 200) {
      throw ApiException('IMAGE_FETCH_FAILED', '下载图片失败 HTTP ${res.statusCode}');
    }
    return res.bodyBytes;
  }
}
