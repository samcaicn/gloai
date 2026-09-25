/// device_discovery.dart — 局域网摄像头发现。
///
/// 两条路并行：
///  1) WS-Discovery UDP 多播探测（标准 ONVIF 发现）
///  2) 网段扫描兜底（当前主机同网段常见摄像头地址 + 可选全段扫描）
///
/// ⚠️ 安卓上接收多播需 WifiManager.MulticastLock（原生持有），纯 Dart 多播发送能成功、
///    但收不到回包时自动降级到网段扫描。鸿蒙同理需申请 ohos.permission.GET_WIFI_INFO 等。
///    真正可靠的入口始终是「手动输入 IP」，发现只是辅助。
library;

import 'dart:async';
import 'dart:io';
import 'package:xml/xml.dart';
import 'vendor_preset.dart';

class DiscoveredDevice {
  final String ip;
  final int port;
  final String? model;
  DiscoveredDevice(this.ip, this.port, [this.model]);
}

const _multicastGroup = '239.255.255.250';
const _multicastPort = 3702;

/// 执行发现，返回探测到的候选设备（去重）。
Future<List<DiscoveredDevice>> discover({
  Duration timeout = const Duration(seconds: 3),
  bool fullSubnetScan = false,
}) async {
  final results = <DiscoveredDevice>{};
  final ws = await _wsDiscovery(timeout);
  results.addAll(ws);
  final scan = await _subnetScan(timeout, fullSubnetScan);
  results.addAll(scan);
  return results.toList();
}

/// WS-Discovery：发 Probe，尽力收回应（安卓无 MulticastLock 时收不到，自动空返回）。
Future<List<DiscoveredDevice>> _wsDiscovery(Duration timeout) async {
  final found = <DiscoveredDevice>[];
  try {
    final sock = await RawDatagramSocket.bind(InternetAddress.anyIPv4, 0);
    sock.multicastHops = 2;
    final probe = _probeEnvelope();
    sock.send(probe, InternetAddress(_multicastGroup), _multicastPort);
    final ctrl = Completer<void>();
    final timer = Timer(timeout, () => ctrl.complete());
    sock.listen((ev) {
      if (ev == RawSocketEvent.read) {
        final dg = sock.receive();
        if (dg == null) return;
        final text = String.fromCharCodes(dg.data);
        if (text.contains('ProbeMatch')) {
          try {
            final doc = XmlDocument.parse(text);
            for (final xaddr in doc.findAllElements('XAddrs')) {
              final uri = xaddr.innerText.trim().split(' ').first;
              final u = Uri.tryParse(uri);
              if (u != null) found.add(DiscoveredDevice(u.host, u.port));
            }
          } catch (_) {}
        }
      }
    });
    await ctrl.future;
    sock.close();
  } catch (_) {
    // 多播不可用，静默降级
  }
  return found;
}

/// 网段扫描：先扫当前主机同段的常见摄像头地址，full 时扫全 /24。
Future<List<DiscoveredDevice>> _subnetScan(
    Duration timeout, bool full) async {
  final found = <DiscoveredDevice>[];
  final localIps = await _localIPv4();
  for (final local in localIps) {
    final prefix = _prefix24(local);
    if (prefix == null) continue;
    final candidates = full
        ? List.generate(254, (i) => '${prefix}${i + 1}')
        : ['${prefix}1', '${prefix}2', '${prefix}100', '${prefix}101', '${prefix}254', local];
    final tasks = <Future<void>>[];
    for (final ip in candidates) {
      for (final port in onvifPorts) {
        tasks.add(_probeIp(ip, port, timeout, found));
        if (tasks.length >= 64) {
          await Future.wait(tasks);
          tasks.clear();
        }
      }
    }
    await Future.wait(tasks);
  }
  return found;
}

Future<void> _probeIp(
  String ip,
  int port,
  Duration timeout,
  List<DiscoveredDevice> out,
) async {
  try {
    final sock = await Socket.connect(ip, port, timeout: timeout);
    sock.destroy();
    out.add(DiscoveredDevice(ip, port));
  } catch (_) {}
}

Future<List<String>> _localIPv4() async {
  try {
    final ifaces = await NetworkInterface.list(
      type: InternetAddressType.IPv4,
      includeLoopback: false,
    );
    return ifaces
        .expand((i) => i.addresses)
        .map((a) => a.address)
        .where((a) => a.contains('.'))
        .toList();
  } catch (_) {
    return [];
  }
}

String? _prefix24(String ip) {
  final parts = ip.split('.');
  if (parts.length != 4) return null;
  return '${parts[0]}.${parts[1]}.${parts[2]}.';
}

List<int> _probeEnvelope() => '''
<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://www.w3.org/2003/soap-envelope"
  xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing"
  xmlns:tns="http://www.onvif.org/ver10/network/wsdl">
  <soap:Header>
    <wsa:MessageID>uuid:${DateTime.now().microsecondsSinceEpoch}</wsa:MessageID>
    <wsa:To soap:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</wsa:To>
    <wsa:Action soap:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</wsa:Action>
  </soap:Header>
  <soap:Body>
    <tns:Probe><tns:Types>dn:NetworkVideoRecorder</tns:Types></tns:Probe>
  </soap:Body>
</soap:Envelope>'''
    .codeUnits
    .toList();
