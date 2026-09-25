// ForegroundInspectService.ts —— 鸿蒙侧自查保活（长时任务）。
// 鸿蒙 NEXT 用 backgroundTaskManager 申请长时任务，避免上报/推理途中被回收。
import { FlutterEngine, MethodChannel, MethodResult } from '@ohos/flutter_ohos';
import { backgroundTaskManager } from '@kit.BackgroundTasksKit';

const TASK_ID = 1001;

export class ForegroundInspectService {
  static register(ability: any, engine: FlutterEngine, channelName: string): void {
    const channel = new MethodChannel(engine.dartExecutor.getBinaryMessenger(), channelName);
    channel.setMethodCallHandler((call: any, result: MethodResult) => {
      switch (call.method) {
        case 'start':
          ForegroundInspectService.start(result);
          break;
        case 'stop':
          ForegroundInspectService.stop(result);
          break;
        default:
          result.notImplemented();
      }
    });
  }

  private static start(result: MethodResult): void {
    try {
      backgroundTaskManager.startBackgroundRunning(
        TASK_ID,
        backgroundTaskManager.BackgroundMode.DATA_TRANSFER,
        (err: Error) => {
          if (err) { result.error('BG_FAIL', err.message, null); }
          else { result.success(null); }
        }
      );
    } catch (e) {
      result.error('BG_FAIL', (e as Error).message, null);
    }
  }

  private static stop(result: MethodResult): void {
    try {
      backgroundTaskManager.stopBackgroundRunning(TASK_ID, (err: Error) => {
        result.success(null); // 无论成败都视为已停止
      });
    } catch (e) {
      result.success(null);
    }
  }
}
