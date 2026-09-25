/// digest_auth.dart — 纯 Dart 的 HTTP Digest 认证拦截器。
///
/// ONVIF 与多数 IPC 的管理口走 Digest（不是 Basic）。`http` 包本身不处理 Digest，
/// 这里实现一个 [DigestClient]：首次请求收到 401 时解析 `WWW-Authenticate`，
/// 计算响应头并自动重试一次。支持 qop=auth 与无 qop 两种形态。
library;

import 'dart:convert';
import 'dart:math';
import 'package:http/http.dart' as http;
import 'package:crypto/crypto.dart';

/// 带 Digest 认证的 HTTP 客户端。
class DigestClient extends http.BaseClient {
  final http.Client _inner;
  final String username;
  final String password;

  String? _realm;
  String? _nonce;
  String? _qop;
  String? _opaque;
  int _nc = 0;

  DigestClient(this.username, this.password, [http.Client? inner])
      : _inner = inner ?? http.Client();

  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) async {
    final resp1 = await _inner.send(request);
    if (resp1.statusCode != 401) return resp1;

    final authHeader = resp1.headers['www-authenticate'];
    if (authHeader == null || !authHeader.toLowerCase().startsWith('digest')) {
      return resp1;
    }
    _parseChallenge(authHeader);

    final bodyBytes =
        request is http.Request ? (request as http.Request).bodyBytes : <int>[];

    final newReq = http.Request(request.method, request.url)
      ..headers.addAll(request.headers)
      ..headers['authorization'] =
          _authorization(request.method, request.url.toString());
    if (bodyBytes.isNotEmpty) newReq.bodyBytes = bodyBytes;
    return _inner.send(newReq);
  }

  void _parseChallenge(String header) {
    final h = header.substring(7).trim(); // 去掉 "Digest "
    for (final part in h.split(',')) {
      final idx = part.indexOf('=');
      if (idx < 0) continue;
      final k = part.substring(0, idx).trim();
      final v = part.substring(idx + 1).trim().replaceAll('"', '');
      if (k == 'realm') _realm = v;
      else if (k == 'nonce') _nonce = v;
      else if (k == 'qop') _qop = v.split(',').first;
      else if (k == 'opaque') _opaque = v;
    }
  }

  String _authorization(String method, String uri) {
    _nc++;
    final cnonce = _randomHex(8);
    final ha1 = _md5('$username:$_realm:$password');
    final ha2 = _md5('$method:$uri');
    final nc = _nc.toRadixString(16).padLeft(8, '0');
    final String response;
    if (_qop != null && _qop!.isNotEmpty) {
      response = _md5('$ha1:$_nonce:$nc:$cnonce:$_qop:$ha2');
    } else {
      response = _md5('$ha1:$_nonce:$ha2');
    }
    final fields = [
      'Digest username="$username"',
      'realm="$_realm"',
      'nonce="$_nonce"',
      'uri="$uri"',
      'response="$response"',
    ];
    if (_qop != null && _qop!.isNotEmpty) {
      fields.add('qop=$_qop');
      fields.add('nc=$nc');
      fields.add('cnonce="$cnonce"');
    }
    if (_opaque != null) fields.add('opaque="$_opaque"');
    return fields.join(', ');
  }

  String _randomHex(int len) {
    final r = Random();
    return List.generate(len, (_) => r.nextInt(16).toRadixString(16)).join();
  }

  String _md5(String s) => md5.convert(utf8.encode(s)).toString();

  @override
  void close() => _inner.close();
}
