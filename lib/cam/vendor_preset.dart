/// vendor_preset.dart — 厂商私有抓图路径预设 + 常见弱口令。
///
/// 当 ONVIF 不可用（老设备/被阉割）时，按厂商私有 HTTP 接口兜底抓图。
/// 也内置常见弱口令，用于「店长不知道密码」场景的自动尝试（仅用于帮助店长连上自己的设备，
/// 连上后主动提示修改弱口令，绝不偷偷留存弱口令）。
library;

/// 海康 ISAPI 抓图：/ISAPI/Streaming/channels/<ch>/picture
String hikvisionSnapshot(String ip, int port, int channel, String user, String pass) =>
    'http://$ip:$port/ISAPI/Streaming/channels/${channel}/picture?username=$user&password=$pass';

/// 大华 cgi 抓图：/cgi-bin/snapshot.cgi?channel=<ch>
String dahuaSnapshot(String ip, int port, int channel) =>
    'http://$ip:$port/cgi-bin/snapshot.cgi?channel=$channel&subtype=0';

/// TP-Link Tapo 无 snapshot 下载，只能 RTSP 取帧（见 rtsp_fallback.dart）。
const bool tapoSupportsSnapshot = false;

/// 常见弱口令（用于自动尝试，命中即提示用户修改）。
const List<Map<String, String>> commonCredentials = [
  {'u': 'admin', 'p': 'admin'},
  {'u': 'admin', 'p': '12345'},
  {'u': 'admin', 'p': '123456'},
  {'u': 'admin', 'p': ''},
  {'u': 'admin', 'p': 'admin12345'},
  {'u': 'admin', 'p': '888888'},
  {'u': 'root', 'p': 'root'},
];

/// 常见 ONVIF / 管理端口。
const List<int> onvifPorts = [2020, 8080, 80, 8899, 37777];
const int rtspPort = 554;
