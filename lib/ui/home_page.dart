/// home_page.dart — 首页（门店概览 + 一键自查入口）。
library;

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../state/session.dart';
import '../data/models.dart';
import '../app/theme.dart';

class HomePage extends StatefulWidget {
  const HomePage({super.key});
  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  List<Store> _stores = [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final session = context.read<Session>();
    try {
      _stores = await session.repo.getStores();
      if (session.store == null && _stores.isNotEmpty) {
        await session.selectStore(_stores.first);
      }
    } catch (e) {
      // 无网络时允许只用本地设备
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final session = context.watch<Session>();
    return Scaffold(
      appBar: AppBar(
        title: const Text('店长自查'),
        actions: [
          IconButton(
            icon: const Icon(Icons.logout),
            onPressed: () async {
              await session.repo.local.setToken(null);
              if (mounted) Navigator.of(context).pushReplacementNamed('/login');
            },
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.all(16),
              children: [
                if (_stores.length > 1)
                  DropdownButton<Store>(
                    value: session.store,
                    isExpanded: true,
                    items: _stores
                        .map((s) => DropdownMenuItem(value: s, child: Text(s.name)))
                        .toList(),
                    onChanged: (s) async {
                      if (s != null) await session.selectStore(s);
                    },
                  ),
                const SizedBox(height: 16),
                _BigButton(
                  icon: Icons.camera_alt,
                  label: '一键自查',
                  onTap: () => Navigator.of(context).pushNamed('/inspect'),
                ),
                const SizedBox(height: 12),
                _BigButton(
                  icon: Icons.videocam,
                  label: '摄像头管理',
                  onTap: () => Navigator.of(context).pushNamed('/camera'),
                ),
                const SizedBox(height: 12),
                _BigButton(
                  icon: Icons.assessment,
                  label: '自查报告',
                  onTap: () => Navigator.of(context).pushNamed('/report'),
                ),
                const SizedBox(height: 24),
                Text('已配置摄像头：${session.devices.length} 个',
                    style: const TextStyle(color: Colors.grey)),
              ],
            ),
    );
  }
}

class _BigButton extends StatelessWidget {
  final IconData icon;
  final String label;
  final VoidCallback onTap;
  const _BigButton({required this.icon, required this.label, required this.onTap});
  @override
  Widget build(BuildContext context) {
    return Card(
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(10),
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 22, horizontal: 18),
          child: Row(
            children: [
              Icon(icon, color: AppTheme.primary, size: 28),
              const SizedBox(width: 16),
              Text(label, style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w600)),
              const Spacer(),
              const Icon(Icons.chevron_right, color: Colors.grey),
            ],
          ),
        ),
      ),
    );
  }
}
