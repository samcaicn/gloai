package com.facemark.camhub

import android.view.WindowManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * 屏幕常亮通道：自查执行页/预览页调用 enable/disable。
 * 用 WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON，离开页面即清。
 */
object KeepScreenOnPlugin {
    fun register(ctx: FlutterActivity, engine: FlutterEngine, channel: String) {
        MethodChannel(engine.dartExecutor.binaryMessenger, channel)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "enable" -> {
                        ctx.runOnUiThread {
                            ctx.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                        result.success(null)
                    }
                    "disable" -> {
                        ctx.runOnUiThread {
                            ctx.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }
    }
}
