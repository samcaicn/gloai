/// report_page.dart — 自查报告列表 + 详情（明厨亮灶自查刚需）。
library;

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../../state/session.dart';
import '../../data/models.dart';
import '../../app/theme.dart';

class ReportPage extends StatefulWidget {
  const ReportPage({super.key});
  @override
  State<ReportPage> createState() => _ReportPageState();
}

class _ReportPageState extends State<ReportPage> {
  List<InspectResult> _reports = [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      _reports = await context.read<Session>().repo.getReports();
    } catch (_) {}
    if (mounted) setState(() => _loading = false);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('自查报告')),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _reports.isEmpty
              ? const Center(child: Text('暂无报告，去「一键自查」生成第一份'))
              : ListView.separated(
                  padding: const EdgeInsets.all(16),
                  itemCount: _reports.length,
                  separatorBuilder: (_, __) => const SizedBox(height: 10),
                  itemBuilder: (_, i) {
                    final r = _reports[i];
                    return Card(
                      child: ListTile(
                        leading: Icon(
                          r.passed ? Icons.verified : Icons.warning_amber,
                          color: r.passed ? AppTheme.ok : AppTheme.danger,
                        ),
                        title: Text(r.deviceName),
                        subtitle: Text(
                          '${r.takenAt.toLocal()} · ${r.passed ? '通过' : '${r.items.where((e) => e.detected).length} 项待整改'}',
                        ),
                        onTap: () => _showDetail(r),
                      ),
                    );
                  },
                ),
    );
  }

  void _showDetail(InspectResult r) {
    showModalBottomSheet(
      context: context,
      builder: (_) => Padding(
        padding: const EdgeInsets.all(20),
        child: ListView(
          shrinkWrap: true,
          children: [
            Text(r.deviceName, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),
            Text('时间：${r.takenAt.toLocal()}'),
            const SizedBox(height: 12),
            ...r.items.map((i) => ListTile(
                  dense: true,
                  leading: Icon(i.detected ? Icons.close : Icons.check,
                      color: i.detected ? AppTheme.danger : AppTheme.ok),
                  title: Text('${i.label}${i.detected ? '（命中）' : '（正常）'}'),
                  subtitle: i.detected && i.suggestion.isNotEmpty ? Text(i.suggestion) : null,
                )),
          ],
        ),
      ),
    );
  }
}
