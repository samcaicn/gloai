package com.jev.probe.core.license

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.jev.probe.core.t

/**
 * 本地免费计数重置通道（2026-10-16）：设置页按钮之外，另开一条 adb 广播口子，
 * 方便不摸手机就清本地配额门（装机调试 / CI 验收 / 远程排障）：
 *
 *   adb shell am broadcast -a com.jev.probe.action.RESET_QUOTA \
 *       -n com.jev.probe/.core.license.QuotaResetReceiver
 *
 * 安全边界：只写本地当日 count（PackLedger.resetDaily）。云端 KV
 * （free:<utcday>:<inst>）仍是 30 条/天/设备的硬墙——乱刷广播最多把
 * 客户端的提前拦截关掉，换不来超额云端额度。话术包授权账本不动。
 */
class QuotaResetReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESET_QUOTA) return
        val ok = PackLedger.resetDaily(context)
        Log.i(TAG, "reset daily quota via broadcast ok=$ok")
        Toast.makeText(
            context,
            context.t("今日免费计数已重置", "Daily free count reset"),
            Toast.LENGTH_SHORT
        ).show()
    }

    companion object {
        private const val TAG = "QuotaReset"
        const val ACTION_RESET_QUOTA = "com.jev.probe.action.RESET_QUOTA"
    }
}
