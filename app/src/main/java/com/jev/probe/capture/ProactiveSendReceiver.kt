package com.jev.probe.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.jev.probe.core.Prefs
import com.jev.probe.core.SendQueueStore

/**
 * 主动发送入口（方案 A/B）：用户在设置页或 adb 显式给某个旧会话发内容。
 *
 * 为什么是 export 广播：设置页按钮、adb 调试、未来的自动化脚本都要能触发它，而它们
 * 不都在 App 进程内（adb 之外、或设置页已退出后服务仍活着）。但 export = 任意 App 可发，
 * 所以带一道轻量令牌校验（[Prefs.proactiveToken]）：校验失败一律静默丢弃，不弹 toast、
 * 不写账本——有人拿它对着"最近来过通知的会话"冒发消息是真实攻击面，令牌拦掉 99%。
 *
 * 令牌不完美（存在本机 prefs，root 可读取），但它足够挡住"随便哪个App都能借用微信发消息"，
 * 且设置页随时可见、用户可手动换一个（写入 [Prefs.proactiveToken]）。要更强可改签名/限时。
 *
 * 流程：校验通过 → 入持久化待发队列（force=true）→ 发内部 [ChatCaptureService.ACTION_DRAIN_PENDING]
 * 唤醒探针服务补发。服务活着立刻发；服务没跑则队列在 onServiceConnected(+3s) / 亮屏 tick 时补发，
 * 不丢。
 */
class ProactiveSendReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ChatCaptureService.ACTION_SEND_TO_CHAT) return
        val conv = intent.getStringExtra("conv")?.trim().orEmpty()
        val text = intent.getStringExtra("text")?.trim().orEmpty()
        val token = intent.getStringExtra("token").orEmpty()
        if (conv.isBlank() || text.isBlank()) {
            Log.w(TAG, "proactive: blank conv/text -> ignore")
            return
        }
        val expected = runCatching { Prefs(ctx).proactiveToken }.getOrNull().orEmpty()
        if (token != expected || expected.isBlank()) {
            // 校验失败：静默丢弃，不泄露任何 UI 反馈（防扫频刷提醒）。
            Log.w(TAG, "proactive: token mismatch -> ignore (possible spoofed broadcast)")
            return
        }
        // 服务可能还没初始化 SendQueueStore，这里兜底 init（幂等）。
        runCatching { SendQueueStore.init(ctx) }
        SendQueueStore.add(SendQueueStore.PendingSend(conv, text, tries = 0,
            firstAt = System.currentTimeMillis(), force = true))
        Log.i(TAG, "proactive: queued force conv='$conv' textLen=${text.length}")
        // 唤醒探针服务立即补发（仅本 App，NOT_EXPORTED）。服务没运行则队列稍后由
        // onServiceConnected(+3s) / 亮屏 tick 补发。
        runCatching {
            ctx.sendBroadcast(Intent(ChatCaptureService.ACTION_DRAIN_PENDING).apply {
                setPackage(ctx.packageName)
            })
        }
        Toast.makeText(ctx, ctx.getString(R.string.proactive_queued, conv), Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "ProactiveSend"
    }
}
