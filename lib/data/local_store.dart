/// local_store.dart — 本地持久化。
/// 配置/票据用 shared_preferences；摄像头与报告缓存用 sqflite。
library;

import 'dart:convert';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqflite/sqflite.dart';
import 'package:path/path.dart' as p;
import 'models.dart';

class LocalStore {
  static const _spToken = 'auth_token';
  static const _spStore = 'current_store';

  SharedPreferences? _sp;
  Database? _db;

  Future<void> init() async {
    _sp ??= await SharedPreferences.getInstance();
    _db ??= await openDatabase(
      p.join((await getDatabasesPath()), 'camhub.db'),
      version: 1,
      onCreate: (db, _) async {
        await db.execute('''
          CREATE TABLE cam_device(
            id TEXT PRIMARY KEY, source TEXT, provider TEXT, name TEXT,
            ip TEXT, port INTEGER, onvifUrl TEXT, snapshotUrl TEXT,
            username TEXT, passwordEnc TEXT, online INTEGER, lastSeen TEXT
          )''');
        await db.execute('''
          CREATE TABLE cam_report(
            reportId TEXT PRIMARY KEY, deviceId TEXT, deviceName TEXT,
            takenAt TEXT, imageUrl TEXT, passed INTEGER, items TEXT, storeId TEXT
          )''');
      },
    );
  }

  // —— 配置/票据 ——
  String? get token => _sp?.getString(_spToken);
  Future<void> setToken(String? t) => _sp!.setString(_spToken, t ?? '');
  String? get currentStoreId => _sp?.getString(_spStore);
  Future<void> setCurrentStore(String? id) =>
      _sp!.setString(_spStore, id ?? '');

  // —— 摄像头缓存 ——
  Future<List<CameraDevice>> loadDevices() async {
    final rows = await _db!.query('cam_device');
    return rows.map((r) => CameraDevice(
          id: r['id'] as String,
          source: CameraSourceKind.values.byName(r['source'] as String),
          provider: r['provider'] as String?,
          name: r['name'] as String,
          ip: r['ip'] as String?,
          port: r['port'] as int?,
          onvifUrl: r['onvifUrl'] as String?,
          snapshotUrl: r['snapshotUrl'] as String?,
          username: r['username'] as String?,
          passwordEnc: r['passwordEnc'] as String?,
          online: (r['online'] as int?) == 1,
          lastSeen: r['lastSeen'] == null
              ? null
              : DateTime.parse(r['lastSeen'] as String),
        )).toList();
  }

  Future<void> saveDevice(CameraDevice d) async {
    await _db!.insert(
      'cam_device',
      {
        'id': d.id,
        'source': d.source.name,
        'provider': d.provider,
        'name': d.name,
        'ip': d.ip,
        'port': d.port,
        'onvifUrl': d.onvifUrl,
        'snapshotUrl': d.snapshotUrl,
        'username': d.username,
        'passwordEnc': d.passwordEnc,
        'online': d.online ? 1 : 0,
        'lastSeen': d.lastSeen?.toIso8601String(),
      },
      conflictAlgorithm: ConflictAlgorithm.replace,
    );
  }

  Future<void> deleteDevice(String id) async =>
      _db!.delete('cam_device', where: 'id = ?', whereArgs: [id]);

  // —— 报告缓存 ——
  Future<void> saveReport(InspectResult r) async {
    await _db!.insert(
      'cam_report',
      {
        'reportId': r.reportId,
        'deviceId': r.deviceId,
        'deviceName': r.deviceName,
        'takenAt': r.takenAt.toIso8601String(),
        'imageUrl': r.imageUrl,
        'passed': r.passed ? 1 : 0,
        'items': jsonEncode(r.items.map((i) => {
              'code': i.code,
              'label': i.label,
              'detected': i.detected,
              'confidence': i.confidence,
              'suggestion': i.suggestion,
            }).toList()),
        'storeId': r.storeId,
      },
      conflictAlgorithm: ConflictAlgorithm.replace,
    );
  }

  Future<List<InspectResult>> loadReports() async {
    final rows = await _db!
        .query('cam_report', orderBy: 'takenAt DESC');
    return rows.map((r) => InspectResult(
          reportId: r['reportId'] as String,
          deviceId: r['deviceId'] as String,
          deviceName: r['deviceName'] as String,
          takenAt: DateTime.parse(r['takenAt'] as String),
          imageUrl: r['imageUrl'] as String?,
          passed: (r['passed'] as int?) == 1,
          items: (jsonDecode(r['items'] as String) as List)
              .map((e) => ViolationItem.fromJson(e))
              .toList(),
          storeId: r['storeId'] as String?,
        )).toList();
  }
}
