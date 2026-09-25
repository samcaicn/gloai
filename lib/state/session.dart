/// session.dart — 全局会话状态（provider 模式）。
library;

import 'package:flutter/material.dart';
import '../data/api_client.dart';
import '../data/local_store.dart';
import '../data/store_repository.dart';
import '../data/models.dart';

class Session extends ChangeNotifier {
  final StoreRepository repo;
  Session(this.repo);

  bool _authed = false;
  bool get authed => _authed;

  Store? _store;
  Store? get store => _store;

  List<CameraDevice> _devices = [];
  List<CameraDevice> get devices => _devices;

  /// 启动时尝试用本地 token 恢复会话。
  Future<void> bootstrap() async {
    final t = repo.local.token;
    if (t != null && t.isNotEmpty) {
      repo.api.setToken(t);
      _authed = true;
    }
    notifyListeners();
  }

  Future<void> login(String user, String pwd) async {
    await repo.login(user, pwd);
    _authed = true;
    notifyListeners();
  }

  Future<void> selectStore(Store s) async {
    _store = s;
    await repo.local.setCurrentStore(s.id);
    await refreshDevices();
  }

  Future<void> refreshDevices() async {
    if (_store == null) return;
    _devices = await repo.getDevices(_store!.id);
    notifyListeners();
  }

  Future<void> addLocalDevice(CameraDevice d) async {
    await repo.local.saveDevice(d);
    await refreshDevices();
  }

  Future<void> removeDevice(String id) async {
    await repo.local.deleteDevice(id);
    await refreshDevices();
  }
}
