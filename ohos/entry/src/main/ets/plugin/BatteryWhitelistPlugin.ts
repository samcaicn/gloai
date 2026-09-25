// BatteryWhitelistPlugin.ts —— 鸿蒙侧电池/自启动白名单引导。
// 鸿蒙 NEXT 下通过跳转「应用详情/电池优化」设置页引导用户放行后台。
import { FlutterEngine, MethodChannel, MethodResult } from '@ohos/flutter_ohos';
import { common, wantConstant } from '@kit.AbilityKit';

export class BatteryWhitelistPlugin {
  static register(ability: any, engine: FlutterEngine, chBattery: string, chIgnore: string): void {
    const battery = new MethodChannel(engine.dartExecutor.getBinaryMessenger(), chBattery);
    battery.setMethodCallHandler((call: any, result: MethodResult) => {
      if (call.method === 'openSettings') {
        BatteryWhitelistPlugin.openSettings(ability, result);
      } else {
        result.notImplemented();
      }
    });

    const ignore = new MethodChannel(engine.dartExecutor.getBinaryMessenger(), chIgnore);
    ignore.setMethodCallHandler((call: any, result: MethodResult) => {
      if (call.method === 'request') {
        // 鸿蒙 NEXT 由用户在设置页手动放行，这里引导跳转
        BatteryWhitelistPlugin.openSettings(ability, result);
      } else {
        result.notImplemented();
      }
    });
  }

  private static openSettings(ability: any, result: MethodResult): void {
    try {
      const ctx = ability.context as common.UIAbilityContext;
      const want = {
        bundleName: 'com.huawei.hmos.settings',
        abilityName: 'com.huawei.hmos.settings.MainAbility',
        uri: 'application_info_entry',
        parameters: { pushParams: ctx.abilityInfo.bundleName }
      };
      ctx.startAbility(want).then(() => result.success(true))
        .catch((e: Error) => result.success(false));
    } catch (e) {
      result.success(false);
    }
  }
}
