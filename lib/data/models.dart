/// models.dart — 跨端共用的数据模型。
///
/// 这些模型同时服务「本地直连（ONVIF）」与「云端代理（camhub）」两条通道，
/// 上层不关心图从哪来。
library;

/// 摄像头来源。
enum CameraSourceKind {
  /// 本地同一 WiFi 通过 ONVIF 直连
  localOnvif,
  /// 通过服务端 camhub 走厂商云代理
  cloudProxy,
  /// 手动录入（仅 IP/账号，无发现）
  manual,
}

/// 归一化后的摄像头设备（字段对齐 camhub 的 device 归一结构）。
class CameraDevice {
  final String id;
  final CameraSourceKind source;
  final String? provider; // cloudProxy 时：ezviz / imou / uniview
  final String name; // 前厅/后厨/出餐口
  final String? ip;
  final int? port;
  final String? onvifUrl;
  final String? snapshotUrl; // 直连时本地缓存的抓图地址
  final String? username;
  final String? passwordEnc; // 加密存储，绝不明文
  final bool online;
  final DateTime? lastSeen;

  const CameraDevice({
    required this.id,
    required this.source,
    this.provider,
    required this.name,
    this.ip,
    this.port,
    this.onvifUrl,
    this.snapshotUrl,
    this.username,
    this.passwordEnc,
    this.online = false,
    this.lastSeen,
  });

  CameraDevice copyWith({
    String? name,
    bool? online,
    String? snapshotUrl,
    DateTime? lastSeen,
  }) =>
      CameraDevice(
        id: id,
        source: source,
        provider: provider,
        name: name ?? this.name,
        ip: ip,
        port: port,
        onvifUrl: onvifUrl,
        snapshotUrl: snapshotUrl ?? this.snapshotUrl,
        username: username,
        passwordEnc: passwordEnc,
        online: online ?? this.online,
        lastSeen: lastSeen ?? this.lastSeen,
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'source': source.name,
        'provider': provider,
        'name': name,
        'ip': ip,
        'port': port,
        'onvifUrl': onvifUrl,
        'snapshotUrl': snapshotUrl,
        'username': username,
        'passwordEnc': passwordEnc,
        'online': online,
        'lastSeen': lastSeen?.toIso8601String(),
      };

  factory CameraDevice.fromJson(Map<String, dynamic> j) => CameraDevice(
        id: j['id'],
        source: CameraSourceKind.values.byName(j['source']),
        provider: j['provider'],
        name: j['name'],
        ip: j['ip'],
        port: j['port'],
        onvifUrl: j['onvifUrl'],
        snapshotUrl: j['snapshotUrl'],
        username: j['username'],
        passwordEnc: j['passwordEnc'],
        online: j['online'] ?? false,
        lastSeen:
            j['lastSeen'] == null ? null : DateTime.parse(j['lastSeen']),
      );
}

/// 自查违规项。
class ViolationItem {
  final String code; // mask/chef_hat/...
  final String label; // 口罩/厨帽/...
  final bool detected;
  final double? confidence;
  final String suggestion; // 整改建议

  const ViolationItem({
    required this.code,
    required this.label,
    required this.detected,
    this.confidence,
    required this.suggestion,
  });

  factory ViolationItem.fromJson(Map<String, dynamic> j) => ViolationItem(
        code: j['code'],
        label: j['label'],
        detected: j['detected'] ?? false,
        confidence: j['confidence']?.toDouble(),
        suggestion: j['suggestion'] ?? '',
      );
}

/// 一次自查的结果。
class InspectResult {
  final String reportId;
  final String deviceId;
  final String deviceName;
  final DateTime takenAt;
  final String? imageUrl; // 证据截图（短期 URL 或本地路径）
  final bool passed; // 全部违规项均为 false 视为通过
  final List<ViolationItem> items;
  final String? storeId;

  const InspectResult({
    required this.reportId,
    required this.deviceId,
    required this.deviceName,
    required this.takenAt,
    this.imageUrl,
    required this.passed,
    required this.items,
    this.storeId,
  });

  factory InspectResult.fromJson(Map<String, dynamic> j) => InspectResult(
        reportId: j['reportId'],
        deviceId: j['deviceId'],
        deviceName: j['deviceName'] ?? '',
        takenAt: DateTime.parse(j['takenAt']),
        imageUrl: j['imageUrl'],
        passed: j['passed'] ?? false,
        items: (j['items'] as List)
            .map((e) => ViolationItem.fromJson(e))
            .toList(),
        storeId: j['storeId'],
      );
}

/// 门店（店长可管理的范围）。
class Store {
  final String id;
  final String name;
  final String? address;

  const Store({required this.id, required this.name, this.address});

  factory Store.fromJson(Map<String, dynamic> j) =>
      Store(id: j['id'], name: j['name'], address: j['address']);
}
