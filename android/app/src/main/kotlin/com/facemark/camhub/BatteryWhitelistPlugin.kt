package com.facemark.camhub

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * 厂商 ROM 电池白名单 / 自启动引导。
 * 国产 ROM 杀后台是保活第一杀手，这里负责跳到对应设置页 + 申请忽略电池优化。
 */
object BatteryWhitelistPlugin {

    fun register(
        ctx: FlutterActivity,
        engine: FlutterEngine,
        channel: String,
        ignoreChannel: String
    ) {
        MethodChannel(engine.dartExecutor.binaryMessenger, channel)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "openSettings" -> result.success(openRomSettings(ctx))
                    else -> result.notImplemented()
                }
            }
        MethodChannel(engine.dartExecutor.binaryMessenger, ignoreChannel)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "request" -> result.success(requestIgnore(ctx))
                    else -> result.notImplemented()
                }
            }
    }

    /** 跳转到当前 ROM 的自启动/电池策略页；无精确页时跳到应用详情页兜底。返回是否成功跳转。 */
    private fun openRomSettings(ctx: FlutterActivity): Boolean {
        val rom = detectRom()
        val intent = when (rom) {
            "xiaomi" -> Intent().setComponent(ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"))
            "huawei", "honor" -> Intent().setComponent(ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
            "oppo" -> Intent().setComponent(ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"))
            "vivo" -> Intent().setComponent(ComponentName(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"))
            else -> null
        }
        return try {
            if (intent != null && canResolve(ctx, intent)) {
                ctx.startActivity(intent)
                true
            } else {
                // 兜底：应用详情页
                val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${ctx.packageName}"))
                ctx.startActivity(i)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun requestIgnore(ctx: FlutterActivity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) return true
        return try {
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${ctx.packageName}"))
            ctx.startActivity(i)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun detectRom(): String {
        val brand = Build.BRAND.lowercase()
        return when {
            brand.contains("xiaomi") || brand.contains("redmi") -> "xiaomi"
            brand.contains("huawei") || brand.contains("harmony") -> "huawei"
            brand.contains("honor") -> "honor"
            brand.contains("oppo") || brand.contains("oneplus") -> "oppo"
            brand.contains("vivo") || brand.contains("iqoo") -> "vivo"
            brand.contains("samsung") -> "samsung"
            else -> "other"
        }
    }

    private fun canResolve(ctx: FlutterActivity, i: Intent): Boolean =
        ctx.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY) != null
}
