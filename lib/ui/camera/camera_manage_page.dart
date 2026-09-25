/// camera_manage_page.dart — 摄像头管理：发现 / 手动添加 / 测试抓图 / 命名。
library;

import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../../state/session.dart';
import '../../data/models.dart';
import '../../cam/device_discovery.dart';
import '../../cam/onvif_client.dart';
import '../../cam/snapshot_fetcher.dart';
import '../../cam/vendor_preset.dart';
import '../../app/theme.dart';

class CameraManagePage extends StatefulWidget {
  const CameraManagePage({super.key});
  @override
  State<CameraManagePage> createState() => _CameraManagePageState();
}

class _CameraManagePageState extends State<CameraManagePage> {
  List<DiscoveredDevice> _found = [];
  bool _scanning = false;
  final _ip = TextEditingController();
  final _user = TextEditingController(text: 'admin');
  final _pass = TextEditingController();

  @override
  void initState() {
    super.initState();
    _scan();
  }

  Future<void> _scan() async {
    setState(() => _scanning = true);
    try {
      _found = await discover();
    } catch (e) {
      _found = [];
    } finally {
      if (mounted) setState(() => _scanning = false);
    }
  }

  /// 测试某 IP 是否可抓图（自动尝试常见弱口令）。
  Future<void> _addByIp() async {
    final ip = _ip.text.trim();
    if (ip.isEmpty) return;
    final creds = [
      if (_user.text.isNotEmpty)
        {'u': _user.text, 'p': _pass.text}
      else
        ...commonCredentials,
    ];
    for (final c in creds) {
      try {
        final client = OnvifClient(
          ip: ip,
          port: 2020,
          username: c['u']!,
          password: c['p']!,
        );
        final profile = await client.getSnapshotUri();
        final bytes = await SnapshotFetcher.fetch(profile.snapshotUri,
            username: c['u'], password: c['p']);
        client.close();
        if (mounted) await _save(CameraDevice(
          id: 'local_$ip',
          source: CameraSourceKind.localOnvif,
          name: '摄像头-$ip',
          ip: ip,
          port: 2020,
          onvifUrl: profile.snapshotUri,
          snapshotUrl: profile.snapshotUri,
          username: c['u'],
          passwordEnc: c['p'], // 占位：生产应走 Keystore 加密
          online: true,
          lastSeen: DateTime.now(),
        ), previewBytes: bytes);
        if (c['p']!.isEmpty || commonCredentials.contains(c)) {
          _warnWeak();
        }
        return;
      } catch (_) {
        // 试下一个凭据
      }
    }
    if (mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('无法连接：请确认 IP / 端口 / 账号密码，或走云端授权')));
    }
  }

  Future<void> _addFromDiscovery(DiscoveredDevice d) async {
    _ip.text = d.ip;
    await _addByIp();
  }

  Future<void> _save(CameraDevice dev, {Uint8List? previewBytes}) async {
    await context.read<Session>().addLocalDevice(dev);
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('已添加：${dev.name}')),
      );
      Navigator.of(context).pop();
    }
  }

  void _warnWeak() {
    showDialog(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('检测到弱口令'),
        content: const Text('该摄像头使用常见弱口令，存在被入侵风险，请尽快在摄像头管理后台修改密码。'),
        actions: [TextButton(onPressed: () => Navigator.pop(context), child: const Text('知道了'))],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final session = context.watch<Session>();
    return Scaffold(
      appBar: AppBar(title: const Text('摄像头管理')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('手动添加（同一 WiFi 下）', style: TextStyle(fontWeight: FontWeight.bold)),
                  const SizedBox(height: 8),
                  TextField(controller: _ip, decoration: const InputDecoration(labelText: '摄像头 IP', hintText: '如 192.168.1.64')),
                  Row(children: [
                    Expanded(child: TextField(controller: _user, decoration: const InputDecoration(labelText: '账号'))),
                    const SizedBox(width: 8),
                    Expanded(child: TextField(controller: _pass, obscureText: true, decoration: const InputDecoration(labelText: '密码'))),
                  ]),
                  const SizedBox(height: 12),
                  ElevatedButton(onPressed: _addByIp, child: const Text('测试并添加')),
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),
          Row(
            children: [
              const Text('局域网发现', style: TextStyle(fontWeight: FontWeight.bold)),
              const Spacer(),
              _scanning
                  ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                  : TextButton(onPressed: _scan, child: const Text('重新扫描')),
            ],
          ),
          ..._found.map((d) => ListTile(
                leading: const Icon(Icons.wifi),
                title: Text(d.ip),
                subtitle: Text('端口 ${d.port}'),
                trailing: TextButton(onPressed: () => _addFromDiscovery(d), child: const Text('添加')),
              )),
          const SizedBox(height: 16),
          const Text('已配置', style: TextStyle(fontWeight: FontWeight.bold)),
          ...session.devices.map((d) => ListTile(
                leading: Icon(d.online ? Icons.check_circle : Icons.circle_outlined,
                    color: d.online ? AppTheme.ok : Colors.grey),
                title: Text(d.name),
                subtitle: Text(d.ip ?? d.id),
                trailing: IconButton(
                  icon: const Icon(Icons.delete_outline),
                  onPressed: () => context.read<Session>().removeDevice(d.id),
                ),
              )),
        ],
      ),
    );
  }
}
