/// inspect_page.dart — 自查执行 + 结果卡片（产品核心）。
library;

import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../../state/session.dart';
import '../../data/models.dart';
import '../../cam/camera_source.dart';
import '../../service/keep_screen_on.dart';
import '../../service/background_service.dart';
import '../../app/theme.dart';

class InspectPage extends StatefulWidget {
  const InspectPage({super.key});
  @override
  State<InspectPage> createState() => _InspectPageState();
}

class _InspectPageState extends State<InspectPage> {
  CameraDevice? _device;
  bool _running = false;
  InspectResult? _result;
  String? _err;

  @override
  void dispose() {
    KeepScreenOn.disable();
    super.dispose();
  }

  CameraSource _sourceFor(CameraDevice d) {
    if (d.source == CameraSourceKind.cloudProxy) {
      final repo = context.read<Session>().repo;
      // repo 返回 Future<List<int>>，统一转成 Uint8List 以符合 CameraSource 契约
      return CloudCameraSource((id) async {
        final bytes = await repo.captureCloudBytes(
          context.read<Session>().store!.id,
          d,
        );
        return Uint8List.fromList(bytes);
      });
    }
    return LocalCameraSource();
  }

  Future<void> _run() async {
    if (_device == null) {
      setState(() => _err = '请先选择摄像头');
      return;
    }
    setState(() => _running = true);
    KeepScreenOn.enable();
    await BackgroundService.start('self-inspect');
    try {
      final source = _sourceFor(_device!);
      final bytes = await source.captureFrame(_device!);
      final session = context.read<Session>();
      _result = await session.repo.inspect(session.store!.id, _device!, bytes);
    } catch (e) {
      setState(() => _err = '自查失败：$e');
    } finally {
      await BackgroundService.stop();
      KeepScreenOn.disable();
      if (mounted) setState(() => _running = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final session = context.watch<Session>();
    return Scaffold(
      appBar: AppBar(title: const Text('一键自查')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          DropdownButton<CameraDevice>(
            isExpanded: true,
            hint: const Text('选择摄像头'),
            value: _device,
            items: session.devices
                .map((d) => DropdownMenuItem(value: d, child: Text(d.name)))
                .toList(),
            onChanged: (d) => setState(() => _device = d),
          ),
          const SizedBox(height: 16),
          ElevatedButton(
            onPressed: _running ? null : _run,
            child: _running
                ? const Row(mainAxisAlignment: MainAxisAlignment.center, children: [
                    SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white)),
                    SizedBox(width: 10),
                    Text('自查中…'),
                  ])
                : const Text('开始自查'),
          ),
          if (_err != null) ...[
            const SizedBox(height: 12),
            Text(_err!, style: const TextStyle(color: AppTheme.danger)),
          ],
          const SizedBox(height: 16),
          if (_result != null) _ResultCard(result: _result!),
        ],
      ),
    );
  }
}

class _ResultCard extends StatelessWidget {
  final InspectResult result;
  const _ResultCard({required this.result});

  @override
  Widget build(BuildContext context) {
    return Card(
      color: result.passed ? AppTheme.ok.withOpacity(0.08) : AppTheme.danger.withOpacity(0.08),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(result.passed ? Icons.verified : Icons.warning,
                    color: result.passed ? AppTheme.ok : AppTheme.danger),
                const SizedBox(width: 8),
                Text(result.passed ? '本次自查通过' : '发现 ${result.items.where((i) => i.detected).length} 项需整改',
                    style: const TextStyle(fontSize: 17, fontWeight: FontWeight.bold)),
              ],
            ),
            const SizedBox(height: 12),
            ...result.items.map((i) => _ItemRow(item: i)),
            const SizedBox(height: 8),
            Text('时间：${result.takenAt.toLocal()}', style: const TextStyle(fontSize: 12, color: Colors.grey)),
          ],
        ),
      ),
    );
  }
}

class _ItemRow extends StatelessWidget {
  final ViolationItem item;
  const _ItemRow({required this.item});
  @override
  Widget build(BuildContext context) {
    final bad = item.detected;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(bad ? Icons.close : Icons.check,
              color: bad ? AppTheme.danger : AppTheme.ok, size: 18),
          const SizedBox(width: 8),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('${item.label}${bad ? '（命中）' : '（正常）'}',
                    style: const TextStyle(fontWeight: FontWeight.w600)),
                if (bad && item.suggestion.isNotEmpty)
                  Text(item.suggestion, style: const TextStyle(fontSize: 12, color: Colors.grey)),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
