/// store_repository.dart — 业务编排层。
/// 组合 api_client（服务端）、local_store（本地缓存）、cam 能力层（抓图）。
library;

import 'api_client.dart';
import 'local_store.dart';
import 'models.dart';

class StoreRepository {
  final ApiClient api;
  final LocalStore local;

  StoreRepository({required this.api, required this.local});

  /// 登录并把 token 落到本地。
  Future<void> login(String user, String pwd) async {
    final data = await api.login(user, pwd);
    await local.setToken(data['token']);
  }

  Future<List<Store>> getStores() async {
    final stores = await api.listStores();
    return stores;
  }

  /// 合并云端设备与本地 ONVIF 设备，统一返回。
  Future<List<CameraDevice>> getDevices(String storeId) async {
    final localDevices = await local.loadDevices();
    List<CameraDevice> cloud = [];
    try {
      cloud = await api.listCloudDevices(storeId);
    } catch (_) {
      // 无网络/无云端绑定时不阻断本地设备使用
    }
    return [...localDevices, ...cloud];
  }

  /// 云端抓图：返回图片字节（经服务端 camhub 代理）。
  Future<List<int>> captureCloudBytes(String storeId, CameraDevice d) async {
    final url = await api.captureCloud(storeId, d.id);
    return api.getBytes(url);
  }

  /// 提交一帧做自查，结果落本地缓存。
  Future<InspectResult> inspect(
    String storeId,
    CameraDevice d,
    List<int> bytes,
  ) async {
    final r = await api.inspectBytes(storeId, d.id, d.name, bytes);
    await local.saveReport(r);
    return r;
  }

  Future<List<InspectResult>> getReports() => local.loadReports();
}
