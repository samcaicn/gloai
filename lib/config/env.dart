/// env.dart — 全局配置。
///
/// 与服务端 camhub（Cloudflare Workers）对接的契约：
///   手机 ──► POST /api?action=cam.devices   （服务端跑 camhub，返设备列表）
///   手机 ──► POST /api?action=cam.capture   （服务端抓图，返短期图片 URL）
///   手机 ──► POST /api?action=cam.inspect   （上传图片字节，云端推理）
///
/// 注意：厂商云 appId/appSecret 一律不下发手机，手机只拿服务端签发的短期 ticket。
library;

class Env {
  /// 后端 API 基地址。生产用你现有的 face.jukuai.net / face.tuptup.top。
  /// 本地联调可改成 http://<电脑IP>:8787（需 Workers 本地版或反代）。
  static const String apiBase =
      String.fromEnvironment('API_BASE', defaultValue: 'https://face.jukuai.net');

  /// 自查结果里默认开启的后厨合规项（五件套）。
  static const List<String> defaultInspectItems = [
    'mask', // 口罩
    'chef_hat', // 厨帽
    'smoking', // 抽烟
    'rodent', // 鼠患
    'leave_post', // 离岗
  ];

  /// 隐私红线：本应用只做人体检测与行为识别，绝不采集人脸/身份画像。
  static const bool privacyNoFaceRecognition = true;

  /// 抓图上传前压缩到的最大边（px），控制弱网带宽。
  static const int maxSnapshotEdge = 1080;
}
