// EntryAbility.ts —— OpenHarmony Flutter 入口，注册三个原生通道。
import { FlutterAbility, FlutterEngine } from '@ohos/flutter_ohos';
import { KeepScreenOnPlugin } from '../plugin/KeepScreenOnPlugin';
import { BatteryWhitelistPlugin } from '../plugin/BatteryWhitelistPlugin';
import { ForegroundInspectService } from '../service/ForegroundInspectService';

const CH_KEEP = 'com.facemark.camhub/keepScreenOn';
const CH_BATTERY = 'com.facemark.camhub/batteryWhitelist';
const CH_IGNORE = 'com.facemark.camhub/ignoreBattery';
const CH_BG = 'com.facemark.camhub/background';

export default class EntryAbility extends FlutterAbility {
  configureFlutterEngine(flutterEngine: FlutterEngine): void {
    super.configureFlutterEngine(flutterEngine);
    KeepScreenOnPlugin.register(this, flutterEngine, CH_KEEP);
    BatteryWhitelistPlugin.register(this, flutterEngine, CH_BATTERY, CH_IGNORE);
    ForegroundInspectService.register(this, flutterEngine, CH_BG);
  }
}
