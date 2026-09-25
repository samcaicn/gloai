// KeepScreenOnPlugin.ts —— 鸿蒙侧屏幕常亮通道。
// 通过 window.setWindowKeepScreenOn 实现，自查/预览页 enable，离开 disable。
import { FlutterEngine, MethodChannel, MethodResult } from '@ohos/flutter_ohos';
import window from '@ohos.window';

export class KeepScreenOnPlugin {
  static register(ability: any, engine: FlutterEngine, channelName: string): void {
    const channel = new MethodChannel(engine.dartExecutor.getBinaryMessenger(), channelName);
    channel.setMethodCallHandler((call: any, result: MethodResult) => {
      switch (call.method) {
        case 'enable':
          KeepScreenOnPlugin.setKeepScreenOn(true, result);
          break;
        case 'disable':
          KeepScreenOnPlugin.setKeepScreenOn(false, result);
          break;
        default:
          result.notImplemented();
      }
    });
  }

  private static setKeepScreenOn(on: boolean, result: MethodResult): void {
    try {
      const win = ability_getWindow(); // 见下方说明
      win.setWindowKeepScreenOn(on).then(() => result.success(null))
        .catch((e: Error) => result.error('KEEP_FAIL', e.message, null));
    } catch (e) {
      result.error('KEEP_FAIL', (e as Error).message, null);
    }
  }
}

// 获取当前窗口：实际应通过 EntryAbility 的 this.windowStage 持有，
// 这里用静态桥接避免与 FlutterAbility 生命周期耦合。
let _winStage: window.WindowStage | undefined;
export function bindWindowStage(stage: window.WindowStage): void {
  _winStage = stage;
}
function ability_getWindow(): window.Window {
  const win = _winStage!.getMainWindowSync();
  return win;
}
