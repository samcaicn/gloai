package com.facemark.camhub

import android.os.Bundle
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * MainActivity —— 注册三个原生通道：
 *  - keepScreenOn：窗口常亮（自查/预览页）
 *  - batteryWhitelist：厂商 ROM 白名单跳转
 *  - background：前台服务启停（见 ForegroundInspectService）
 */
class MainActivity : FlutterActivity() {

    companion object {
        const val CH_KEEP = "com.facemark.camhub/keepScreenOn"
        const val CH_BATTERY = "com.facemark.camhub/batteryWhitelist"
        const val CH_BG = "com.facemark.camhub/background"
        const val CH_IGNORE = "com.facemark.camhub/ignoreBattery"
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        KeepScreenOnPlugin.register(this, flutterEngine, CH_KEEP)
        BatteryWhitelistPlugin.register(this, flutterEngine, CH_BATTERY, CH_IGNORE)
        ForegroundInspectService.register(this, flutterEngine, CH_BG)
    }
}
