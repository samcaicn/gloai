/// snapshot_fetcher.dart — 抓取一帧 JPEG 字节。
///
/// 优先用 ONVIF 返回的 snapshotUri。该 URI 通常自带鉴权参数（user/pass 在 query 里），
/// 若带则直接 GET；否则用 DigestClient 走 Digest。返回压缩到 ≤1080P 的字节（见 compress）。
library;

import 'dart:typed_data';
import 'package:http/http.dart' as http;
import 'package:image/image.dart' as img;
import 'digest_auth.dart';
import '../config/env.dart';

class SnapshotFetcher {
  /// 抓一帧。
  /// [uri] 抓图地址；[username]/[password] 仅当 uri 不含鉴权参数时用于 Digest。
  static Future<Uint8List> fetch(
    String uri, {
    String? username,
    String? password,
  }) async {
    final u = Uri.parse(uri);
    final hasCred = u.queryParameters.containsKey('user') ||
        u.queryParameters.containsKey('usr') ||
        uri.contains('@');

    final client = (hasCred || username == null)
        ? http.Client()
        : DigestClient(username, password!);

    try {
      final res = await client.get(u);
      if (res.statusCode != 200) {
        throw Exception('抓图失败 HTTP ${res.statusCode}');
      }
      final bytes = res.bodyBytes;
      return compress(bytes);
    } finally {
      client.close();
    }
  }

  /// 压缩到最大边 ≤ [Env.maxSnapshotEdge]，控制弱网上传带宽。
  static Uint8List compress(Uint8List bytes) {
    final decoded = img.decodeImage(bytes);
    if (decoded == null) return bytes; // 非 JPEG（极少），原样返回
    int w = decoded.width, h = decoded.height;
    final maxEdge = Env.maxSnapshotEdge;
    if (w > maxEdge || h > maxEdge) {
      final scale = maxEdge / (w > h ? w : h);
      w = (w * scale).round();
      h = (h * scale).round();
      final resized = img.copyResize(decoded, width: w, height: h);
      return Uint8List.fromList(img.encodeJpg(resized, quality: 82));
    }
    return Uint8List.fromList(img.encodeJpg(decoded, quality: 82));
  }
}
